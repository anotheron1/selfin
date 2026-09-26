package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-108 + ANO-142: хотелка считается один раз — на Стратегии, в «Что с капиталом» и в
 * выборке планов, которую Стратегия делит с прогнозом кармашка.
 *
 * <p>Юнит-тесты сервисов этого не ловили: {@code StrategyTimelineServiceTest} мокает наложение,
 * {@code BaselineTimelineBuilderTest} — выборку. Здесь число меряется до и после смены статуса
 * через API, как его видит экран.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-25-wishlist-counted-once-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WishlistCountedOnceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FinancialEventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;

    private static final BigDecimal AMOUNT = new BigDecimal("50000");
    private static final String WISHLIST_CATEGORY = "Хотелки";

    /** Месяц хотелки — через два от текущего: будущая точка внутри горизонта. */
    private final LocalDate date = LocalDate.now().plusMonths(2).withDayOfMonth(15);
    private final YearMonth month = YearMonth.from(date);

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
    }

    @Test
    @DisplayName("ANO-108: выборка планов берёт хотелку, только если она зафиксирована и не сконвертирована")
    void plannedEventsQuery_takesWishlistOnlyFixedNotConverted() throws Exception {
        UUID open = createWishlist("Обсуждается");
        UUID fixed = createWishlist("Зафиксирована");
        setStatus(fixed, "FIXED");
        UUID dismissed = createWishlist("Отклонена");
        setStatus(dismissed, "DISMISSED");
        UUID toPlan = createWishlist("Сконвертирована в план");
        UUID artifact = convert(toPlan, "PLAN_EVENT");
        UUID toFund = createWishlist("Сконвертирована в копилку");
        convert(toFund, "FUND");

        var taken = eventRepository.findPlannedEventsByDateRange(month.atDay(1), month.atEndOfMonth())
                .stream().map(FinancialEvent::getId).collect(Collectors.toSet());

        assertThat(taken)
                .as("план из конверсии — обычный план; из хотелок — только зафиксированная")
                .containsExactlyInAnyOrder(fixed, artifact)
                .doesNotContain(open, dismissed, toPlan, toFund);
    }

    @Test
    @DisplayName("ANO-108: Стратегия — обсуждается 0, зафиксирована −сумма один раз (баланс, расход, разбивка, капитал), отклонена 0")
    void strategy_countsWishlistOnce_byStatus() throws Exception {
        JsonNode before = strategyPoint();

        UUID id = createWishlist("Хотелка ANO-108");
        JsonNode open = strategyPoint();
        assertThat(balance(open)).as("обсуждается: намерение не трата").isEqualByComparingTo(balance(before));
        assertThat(wishlistLine(open)).as("в разбивке месяца кандидата нет").isNull();

        setStatus(id, "FIXED");
        JsonNode fixed = strategyPoint();
        assertThat(balance(fixed)).as("зафиксирована: один раз, не дважды")
                .isEqualByComparingTo(balance(before).subtract(AMOUNT));
        assertThat(fixed.get("expense").decimalValue()).as("расход месяца — с хотелкой")
                .isEqualByComparingTo(before.get("expense").decimalValue().add(AMOUNT));
        assertThat(wishlistLine(fixed)).as("строка разбивки — на сумму хотелки")
                .isEqualByComparingTo(AMOUNT);
        assertThat(fixed.get("capital").decimalValue()).as("покупка видна и на графике капитала")
                .isEqualByComparingTo(before.get("capital").decimalValue().subtract(AMOUNT));

        setStatus(id, "DISMISSED");
        JsonNode dismissed = strategyPoint();
        assertThat(balance(dismissed)).isEqualByComparingTo(balance(before));
        assertThat(dismissed.get("capital").decimalValue()).isEqualByComparingTo(before.get("capital").decimalValue());
    }

    @Test
    @DisplayName("ANO-108: сконвертированная в план хотелка — на Стратегии один раз: деньги несёт план")
    void strategy_convertedToPlan_countedOnce() throws Exception {
        JsonNode before = strategyPoint();

        UUID id = createWishlist("Хотелка ANO-108");
        setStatus(id, "FIXED");
        convert(id, "PLAN_EVENT");

        assertThat(balance(strategyPoint())).isEqualByComparingTo(balance(before).subtract(AMOUNT));
    }

    @Test
    @DisplayName("ANO-142: «Что с капиталом» — baseline без хотелок в любом статусе, дельта включённой — один раз")
    void simulation_baselineWithoutWishlist_deltaOnce() throws Exception {
        JsonNode before = simulationBaselinePoint();

        UUID id = createWishlist("Хотелка ANO-142");
        assertThat(balance(simulationBaselinePoint())).isEqualByComparingTo(balance(before));
        JsonNode delta = simulationItem(id).get("delta");
        assertThat(delta).as("обсуждаемая: сумма один раз — дельтой").hasSize(1);
        assertThat(delta.get(0).get("accountDelta").decimalValue()).isEqualByComparingTo(AMOUNT.negate());

        setStatus(id, "FIXED");
        JsonNode fixed = simulationBaselinePoint();
        assertThat(balance(fixed)).as("зафиксированная тоже только дельтой").isEqualByComparingTo(balance(before));
        assertThat(wishlistLine(fixed)).as("и в разбивке baseline её нет").isNull();

        convert(id, "PLAN_EVENT");
        assertThat(balance(simulationBaselinePoint())).as("после конверсии деньги несёт план")
                .isEqualByComparingTo(balance(before).subtract(AMOUNT));
        assertThat(simulationItem(id).get("delta")).as("а у хотелки дельты нет — иначе второй раз")
                .isEmpty();
    }

    @Test
    @DisplayName("ANO-142: у сконвертированной хотелки-копилки дельты нет — покупку несёт копилка из конверсии")
    void simulation_convertedFund_noDelta() throws Exception {
        UUID src = createWishlistFund("Хотелка-копилка ANO-142");
        UUID saved = convert(src, "SAVINGS", "FUND");

        assertThat(simulationItem(src).get("delta")).as("исходная: второй раз посчитала бы покупку").isEmpty();
        assertThat(simulationItem(saved).get("delta")).as("копилка из конверсии — со своей дельтой").isNotEmpty();
    }

    // ── API, как им пользуется фронт ────────────────────────────────────────

    private UUID createWishlist(String description) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "description", description, "plannedAmount", AMOUNT, "date", date.toString()));
        String json = mockMvc.perform(post("/api/v1/events/wishlist")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private UUID createWishlistFund(String name) throws Exception {
        String json = mockMvc.perform(post("/api/v1/funds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "targetAmount", AMOUNT, "targetDate", date.toString(),
                                "purchaseType", "SAVINGS"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(json).get("id").asText());
        mockMvc.perform(patch("/api/v1/funds/{id}/wishlist-status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    private void setStatus(UUID id, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/events/{id}/wishlist-status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().is2xxSuccessful());
    }

    /** Конверсия хотелки-события; возвращает id артефакта (плана или копилки). */
    private UUID convert(UUID id, String target) throws Exception {
        return convert(id, "WISHLIST", target);
    }

    private UUID convert(UUID id, String sourceKind, String target) throws Exception {
        String json = mockMvc.perform(post("/api/v1/wishlist/items/{id}/convert", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceKind", sourceKind, "target", target))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("convertedTo").get("id").asText());
    }

    private JsonNode strategyPoint() throws Exception {
        String json = mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return pointOf(objectMapper.readTree(json).get("points"));
    }

    private JsonNode simulation() throws Exception {
        String json = mockMvc.perform(get("/api/v1/wishlist/simulation"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode simulationBaselinePoint() throws Exception {
        return pointOf(simulation().get("baseline").get("points"));
    }

    private JsonNode simulationItem(UUID id) throws Exception {
        for (JsonNode item : simulation().get("items")) {
            if (id.toString().equals(item.get("id").asText())) return item;
        }
        throw new AssertionError("хотелки нет в примерке: " + id);
    }

    private JsonNode pointOf(JsonNode points) {
        for (JsonNode p : points) {
            if (month.toString().equals(p.get("yearMonth").asText())) return p;
        }
        throw new AssertionError("нет точки месяца " + month);
    }

    private static BigDecimal balance(JsonNode point) {
        return point.get("balance").decimalValue();
    }

    /** Строка «Хотелки» в разбивке расходов месяца; {@code null}, если её нет. */
    private static BigDecimal wishlistLine(JsonNode point) {
        for (JsonNode item : point.get("breakdown").get("expenseItems")) {
            if (WISHLIST_CATEGORY.equals(item.get("category").asText())) return item.get("amount").decimalValue();
        }
        return null;
    }
}
