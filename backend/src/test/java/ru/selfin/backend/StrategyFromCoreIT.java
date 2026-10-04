package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Р1 (ANO-23, ANO-191): «Стратегия» и «Что с капиталом» берут остаток по месяцам из ядра.
 *
 * <p>Число меряется через API, как его видит экран: точка «Стратегии» против точки траектории
 * {@code GET /pocket} на последний день месяца. Юнит-тесты сборки на моках этого не видят —
 * расчёт идёт через базу.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-04-strategy-from-core-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class StrategyFromCoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TargetFundRepository fundRepo;
    @Autowired PlatformTransactionManager txManager;

    private final LocalDate today = LocalDate.now();
    private final YearMonth current = YearMonth.from(today);

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
    }

    @Test
    @DisplayName("Р1: точки «Стратегии» — точки ядра на конец месяца; текущий месяц держит бронь с прошедшей датой")
    void strategyPoints_areCoreMonthEnds() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        plan("EXPENSE", today.minusDays(3), 7_000, "HIGH", "Бронь IT");
        LocalDate planDate = today.plusDays(40);
        LocalDate incomeDate = today.plusDays(45);
        plan("EXPENSE", planDate, 20_000, "MEDIUM", "План IT");
        plan("INCOME", incomeDate, 50_000, "HIGH", "Доход IT");

        JsonNode strategy = strategy(36);
        JsonNode core = core();
        assertSameAsCore(strategy, core);

        JsonNode now = point(strategy, current);
        assertThat(now.get("phase").asText()).isEqualTo("CURRENT");
        assertThat(dec(now, "balance")).as("конец текущего месяца: на счёте минус бронь с прошедшей датой")
                .isEqualByComparingTo("93000");
        assertThat(dec(now, "expense")).isEqualByComparingTo("7000");
        assertThat(expenseLine(now, "Брони с прошедшей датой")).isEqualByComparingTo("7000");
        assertThat(dec(point(strategy, current.plusMonths(36)), "balance")).as("последний месяц — целиком")
                .isEqualByComparingTo("123000");
        assertThat(dec(point(strategy, YearMonth.from(planDate)), "expense")).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("Р1, C4: зафиксированная копилка с датой цели двигает «Стратегию» на взносы, в разбивке «Взносы в копилки»")
    void fixedSavingsFund_contributionsMoveStrategy() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        JsonNode before = strategy(36);

        createFixedSavingsFund("Копилка IT", 30_000, 0, current.plusMonths(3).atEndOfMonth());
        JsonNode after = strategy(36);

        assertSameAsCore(after, core());
        for (int k = 1; k <= 6; k++) {
            YearMonth m = current.plusMonths(k);
            long held = Math.min(k, 3) * 10_000L;
            assertThat(dec(point(after, m), "balance")).as(m + ": взносы по 10 000 три месяца")
                    .isEqualByComparingTo(dec(point(before, m), "balance").subtract(BigDecimal.valueOf(held)));
        }
        JsonNode next = point(after, current.plusMonths(1));
        assertThat(dec(next, "expense")).isEqualByComparingTo("10000");
        assertThat(expenseLine(next, "Взносы в копилки")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Р1: частично оплаченный план будущего месяца — непогашенным остатком, факт — в текущем месяце")
    void partiallyPaidFuturePlan_heldAsRemainder() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        LocalDate planDate = today.plusDays(40);
        UUID plan = plan("EXPENSE", planDate, 20_000, "MEDIUM", "Предоплата IT");
        fact(plan, 5_000);

        JsonNode strategy = strategy(36);

        assertSameAsCore(strategy, core());
        JsonNode month = point(strategy, YearMonth.from(planDate));
        assertThat(dec(month, "expense")).as("остаток плана, а не вся сумма").isEqualByComparingTo("15000");
        assertThat(dec(month, "balance")).isEqualByComparingTo("80000");
        assertThat(dec(point(strategy, current), "expense")).as("факт — в текущем месяце")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("Р1: прошлый месяц — остаток счетов, как «на счёте»: копилка без счёта не входит")
    void pastMonth_isAccountsBalance_withoutEnvelope() throws Exception {
        YearMonth previous = current.minusMonths(1);
        checkpoint(previous.atDay(1), 50_000);
        UUID envelope = envelopeFund("Конверт IT");
        transfer(envelope, 10_000, previous.atDay(2));

        JsonNode strategy = strategy(36);

        assertThat(dec(point(strategy, previous), "balance")).as("перевод в копилку уменьшает «на счёте»")
                .isEqualByComparingTo("40000");
        assertThat(dec(point(strategy, current), "balance")).isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("Р1: «Что с капиталом» — основа + дельты зафиксированного = «Стратегия»; основа = ядро без зафиксированного")
    void capitalWhatIf_baseWithoutFixed_plusFixedDeltas_isStrategy() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        plan("INCOME", today.plusDays(35), 50_000, "HIGH", "Доход IT");
        UUID wish = wishlist("Хотелка IT", 30_000, current.plusMonths(2).atDay(15));
        setWishlistStatus(wish, "FIXED");
        // Задаток 10 000 за хотелку: ядро держит остаток, 20 000, а формула примерки — всю сумму.
        fact(wish, 10_000);
        // Копилка наполнена на четверть: ядро держит остаток до цели, 45 000, по 15 000 в месяц, а
        // формула примерки взяла бы всю цель, по 20 000, — так дельта из ядра и из формулы различимы.
        UUID fund = createFixedSavingsFund("Копилка IT", 60_000, 15_000, current.plusMonths(3).atEndOfMonth());

        JsonNode simulation = simulation();
        JsonNode strategy = strategy(36);

        List<BigDecimal> base = new ArrayList<>();
        for (JsonNode p : simulation.get("baseline").get("points")) {
            if ("FUTURE".equals(p.get("phase").asText())) base.add(dec(p, "balance"));
        }
        assertThat(base).hasSize(36);
        // Как composeTimeline при открытии блока: включено только зафиксированное.
        List<BigDecimal> composed = new ArrayList<>(base);
        BigDecimal wishTotal = BigDecimal.ZERO;
        BigDecimal fundTotal = BigDecimal.ZERO;
        for (JsonNode item : simulation.get("items")) {
            if (!"FIXED".equals(item.get("status").asText())) continue;
            for (JsonNode d : item.get("delta")) {
                BigDecimal account = d.get("accountDelta").decimalValue();
                for (int i = d.get("monthIndex").asInt(); i < composed.size(); i++) {
                    composed.set(i, composed.get(i).add(account));
                }
                if (wish.toString().equals(item.get("id").asText())) wishTotal = wishTotal.add(account);
                if (fund.toString().equals(item.get("id").asText())) fundTotal = fundTotal.add(account);
            }
        }
        for (int i = 0; i < 36; i++) {
            YearMonth m = current.plusMonths(i + 1);
            assertThat(composed.get(i)).as(m + ": блок при открытии = «Стратегия»")
                    .isEqualByComparingTo(dec(point(strategy, m), "balance"));
        }
        assertThat(wishTotal).as("хотелка — один вычет, остатком после задатка").isEqualByComparingTo("-20000");
        assertThat(fundTotal).as("копилка — остаток до цели, сколько держит ядро").isEqualByComparingTo("-45000");
        assertThat(base.get(0).subtract(composed.get(0))).isEqualByComparingTo("15000");
        assertThat(base.get(1).subtract(composed.get(1))).as("месяц покупки: хотелка и два взноса")
                .isEqualByComparingTo("50000");
        assertThat(base.get(35).subtract(composed.get(35))).isEqualByComparingTo("65000");

        // Основа — то же, что примерка ядра с выключенным зафиксированным.
        JsonNode fitted = sandboxExcluding(wish, fund).get("fitted");
        for (int i = 0; i < 36; i++) {
            assertThat(base.get(i)).as(current.plusMonths(i + 1) + ": основа = ядро без зафиксированного")
                    .isEqualByComparingTo(mainLine(eom(fitted, current.plusMonths(i + 1))));
        }
    }

    @Test
    @DisplayName("Р1: горизонт «Стратегии» — не больше 36 месяцев: дальше ядро не считает")
    void horizonAbove36_isCappedAt36() throws Exception {
        assertThat(futurePoints(strategy(60))).isEqualTo(36);
        assertThat(futurePoints(getJson("/api/v1/wishlist/simulation?horizonMonths=60").get("baseline")))
                .as("«Что с капиталом» — та же граница").isEqualTo(36);
    }

    private static long futurePoints(JsonNode timeline) {
        long future = 0;
        for (JsonNode p : timeline.get("points")) {
            if ("FUTURE".equals(p.get("phase").asText())) future++;
        }
        return future;
    }

    // ── сверка с ядром ──────────────────────────────────────────────────────

    /** Каждая точка текущего и будущих месяцев — точка траектории ядра на последний день месяца. */
    private void assertSameAsCore(JsonNode strategy, JsonNode core) {
        for (YearMonth m = current; !m.isAfter(current.plusMonths(36)); m = m.plusMonths(1)) {
            JsonNode p = point(strategy, m);
            JsonNode c = eom(core, m);
            assertThat(dec(p, "balanceConfirmed")).as(m + ": линия по планам").isEqualByComparingTo(dec(c, "balance"));
            assertThat(dec(p, "balance")).as(m + ": главная линия").isEqualByComparingTo(mainLine(c));
        }
    }

    private JsonNode core() throws Exception {
        return getJson("/api/v1/pocket?scope=DATE:" + PocketScope.maxEnd(today));
    }

    /** Последняя точка траектории в месяце. */
    private static JsonNode eom(JsonNode result, YearMonth m) {
        JsonNode last = null;
        for (JsonNode t : result.get("trajectory")) {
            if (YearMonth.from(LocalDate.parse(t.get("date").asText())).equals(m)) last = t;
        }
        assertThat(last).as("нет точки траектории в " + m).isNotNull();
        return last;
    }

    /** Главная линия — «с обычными тратами», где прогноз накоплен, иначе по планам. */
    private static BigDecimal mainLine(JsonNode t) {
        JsonNode withForecast = t.get("balanceWithForecast");
        return withForecast == null || withForecast.isNull() ? dec(t, "balance") : withForecast.decimalValue();
    }

    private static JsonNode point(JsonNode timeline, YearMonth m) {
        for (JsonNode p : timeline.get("points")) {
            if (m.toString().equals(p.get("yearMonth").asText())) return p;
        }
        throw new AssertionError("нет точки месяца " + m);
    }

    private static BigDecimal expenseLine(JsonNode point, String category) {
        for (JsonNode item : point.get("breakdown").get("expenseItems")) {
            if (category.equals(item.get("category").asText())) return item.get("amount").decimalValue();
        }
        throw new AssertionError("нет строки «" + category + "» в " + point.get("yearMonth").asText());
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.get(field).decimalValue();
    }

    // ── API, как им пользуется фронт ────────────────────────────────────────

    private JsonNode strategy(int horizonMonths) throws Exception {
        return getJson("/api/v1/strategy/timeline?horizonMonths=" + horizonMonths);
    }

    private JsonNode simulation() throws Exception {
        return getJson("/api/v1/wishlist/simulation");
    }

    private JsonNode sandboxExcluding(UUID wish, UUID fund) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "scope", "DATE:" + PocketScope.maxEnd(today),
                "tryOn", List.of(),
                "exclude", List.of(Map.of("type", "EVENT", "id", wish), Map.of("type", "FUND", "id", fund))));
        String json = mockMvc.perform(post("/api/v1/pocket/sandbox")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode getJson(String url) throws Exception {
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private void checkpoint(LocalDate date, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("date", date.toString(), "amount", amount))))
                .andExpect(status().isCreated());
    }

    private UUID plan(String type, LocalDate date, long amount, String priority, String description) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "date", date.toString(), "categoryId", categoryId(type), "type", type,
                "plannedAmount", amount, "priority", priority, "description", description));
        String json = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    /** Факт к плану сегодняшним днём: в будущем деньги уйти не могли. */
    private void fact(UUID planId, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/facts", planId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("date", today.toString(), "factAmount", amount))))
                .andExpect(status().isOk());
    }

    private UUID wishlist(String description, long amount, LocalDate date) throws Exception {
        String json = mockMvc.perform(post("/api/v1/events/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", description, "plannedAmount", amount, "date", date.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private void setWishlistStatus(UUID id, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/events/{id}/wishlist-status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().is2xxSuccessful());
    }

    private UUID envelopeFund(String name) throws Exception {
        String json = mockMvc.perform(post("/api/v1/funds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "targetAmount", 100_000))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private void transfer(UUID fundId, long amount, LocalDate date) throws Exception {
        mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", amount, "confirm", true, "date", date.toString()))))
                .andExpect(status().isOk());
    }

    /** Зафиксированная копилка без счёта — как в {@code PocketControllerIT}. */
    private UUID createFixedSavingsFund(String name, long target, long balance, LocalDate targetDate) {
        return new TransactionTemplate(txManager).execute(s -> fundRepo.save(TargetFund.builder()
                .name(name)
                .targetAmount(BigDecimal.valueOf(target))
                .currentBalance(BigDecimal.valueOf(balance))
                .targetDate(targetDate)
                .purchaseType(FundPurchaseType.SAVINGS)
                .status(FundStatus.FUNDING)
                .wishlistStatus(WishlistStatus.FIXED)
                .build()).getId());
    }

    private String categoryId(String type) throws Exception {
        for (JsonNode c : getJson("/api/v1/categories")) {
            if (type.equals(c.get("type").asText())) return c.get("id").asText();
        }
        throw new AssertionError("нет категории типа " + type);
    }
}
