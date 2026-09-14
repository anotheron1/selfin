package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.dto.StandaloneFactCreateDto;
import ru.selfin.backend.model.enums.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-80: первая трата нового пользователя не обваливает кармашек.
 *
 * <p>Воспроизведение из тела задачи: чистая база, одна трата 23 444 ₽. До починки прогноз
 * достраивал её дневным темпом до месячной нормы — «сумма × дней в месяце / номер дня», —
 * и кармашек показывал −140 664 ₽ вместо −23 444 ₽.
 *
 * <p>Тест НЕ подменяет «сегодня», и это часть утверждения. До починки результат зависел от
 * номера дня: тест, написанный под пятое число, в другой день доказывал бы другое число.
 * После починки день не влияет ни на что, и тест обязан держаться любой датой.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-14-current-month-forecast-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CurrentMonthForecastIT {

    /** Имя из сидов V2. Просто «Продукты» — имя со стенда владельца, в чистой базе его нет. */
    private static final String FOOD = "Еда / Продукты";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    /** Контейнер один на класс: без уборки тесты протекают друг в друга. Сиды не трогаем. */
    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("UPDATE categories SET forecast_enabled = false");
    }

    @Test
    @DisplayName("ANO-80: первая трата нового пользователя не обваливает кармашек")
    void firstExpenseOfNewUser_doesNotCollapsePocket() throws Exception {
        // Галочка включается ВРУЧНУЮ — V21 сняла её у всех. Это делает тест строже:
        // он доказывает, что дело не в галочке, а в механизме.
        enableForecast(FOOD);
        anchor("0");
        postFact(FOOD, "23444", LocalDate.now());

        JsonNode pocket = getPocket();

        assertThat(pocket.get("pocket").decimalValue())
                .as("дневной темп давал −140 664: сумма × дней в месяце / номер дня")
                .isEqualByComparingTo("-23444");
        assertThat(pocket.get("pocketWithForecast").isNull())
                .as("наблюдений нет — оснований для второго числа тоже нет")
                .isTrue();
    }

    @Test
    @DisplayName("ANO-80: три месяца наблюдений — прогноз появляется, но главное число не двигает")
    void withThreeMonthsOfObservation_forecastAppearsButPocketStandsStill() throws Exception {
        anchor("300000");
        // Месяц первой траты отбрасывается как неполный: трат четыре, месяцев наблюдения
        // три. Ряд 36 000, 42 000, 36 000 → медиана 36 000.
        postFact(FOOD, "30000", monthsAgo(4));
        postFact(FOOD, "36000", monthsAgo(3));
        postFact(FOOD, "42000", monthsAgo(2));
        postFact(FOOD, "36000", monthsAgo(1));

        // Сравнение с самим собой при снятой галочке — единственное утверждение, которое
        // не зависит ни от даты прогона, ни от длины горизонта. Точное число тут назвать
        // нельзя: при фолбэк-горизонте «+30 дней» кармашек видит ещё и норму СЛЕДУЮЩЕГО
        // месяца (ANO-36), и разница складывается из двух норм, а не из одной.
        JsonNode without = getPocket();
        enableForecast(FOOD);
        JsonNode with = getPocket();

        assertThat(without.get("pocketWithForecast").isNull())
                .as("галочка снята — оговорки нет")
                .isTrue();
        assertThat(with.get("pocketWithForecast").isNull())
                .as("наблюдений хватает — оговорка обязана появиться")
                .isFalse();
        assertThat(with.get("pocket").decimalValue())
                .as("включение прогноза НЕ двигает главное число — в этом вся починка")
                .isEqualByComparingTo(without.get("pocket").decimalValue());
        assertThat(with.get("pocketWithForecast").decimalValue())
                .as("второе число ниже главного: обычные траты сверх плана ожидаются")
                .isLessThan(with.get("pocket").decimalValue());

        BigDecimal forecastLine = breakdownAmount(with, "UNPLANNED_FORECAST");
        assertThat(with.get("pocket").decimalValue().add(forecastLine))
                .as("POCKET + строка прогноза = второе число, иначе экран показывает два "
                        + "числа, которые не бьются")
                .isEqualByComparingTo(with.get("pocketWithForecast").decimalValue());
    }

    @Test
    @DisplayName("ANO-80: галочка снята — второго числа нет, сколько бы истории ни было")
    void forecastDisabled_noSecondNumber() throws Exception {
        anchor("300000");
        postFact(FOOD, "36000", monthsAgo(3));
        postFact(FOOD, "42000", monthsAgo(2));
        postFact(FOOD, "36000", monthsAgo(1));

        assertThat(getPocket().get("pocketWithForecast").isNull())
                .as("V21 сняла галочку у всех: прогноз не включается сам")
                .isTrue();
    }

    // ── хелперы ────────────────────────────────────────────────────────────────

    /** День фиксируем десятым: «минус месяц» от 31-го зажимается календарём и уезжает. */
    private static LocalDate monthsAgo(int months) {
        return LocalDate.now().minusMonths(months).withDayOfMonth(10);
    }

    private void enableForecast(String categoryName) {
        jdbc.update("UPDATE categories SET forecast_enabled = true WHERE name = ?", categoryName);
    }

    /** Якорь остатка вчерашним днём — как в FundMoneyFlowIT. */
    private void anchor(String amount) {
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 1, ?::numeric, ?::uuid, now())
                """, amount, accountId);
    }

    private void postFact(String categoryName, String amount, LocalDate date) throws Exception {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE name = ? AND is_deleted = false",
                String.class, categoryName);
        String body = objectMapper.writeValueAsString(new StandaloneFactCreateDto(
                date, UUID.fromString(categoryId), EventType.EXPENSE,
                new BigDecimal(amount), "IT", null, null));
        mockMvc.perform(post("/api/v1/events/facts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Сумма строки разбивки по типу; бросает, если строки нет — значит, тест не о том. */
    private static BigDecimal breakdownAmount(JsonNode pocket, String type) {
        for (JsonNode line : pocket.get("breakdown")) {
            if (type.equals(line.get("type").asText())) return line.get("amount").decimalValue();
        }
        throw new AssertionError("в разбивке нет строки " + type);
    }

    private JsonNode getPocket() throws Exception {
        String json = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }
}
