package ru.selfin.backend;

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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.CapitalService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-169, ANO-201: факт перевода в копилку и движение копилки меняются только вместе.
 *
 * <p>Как и {@code FundMoneyFlowIT}, проверяется ЗАКОН СОХРАНЕНИЯ, а не отдельные методы: спека
 * капитала ({@code 2026-05-10-capital-net-worth-design.md:63-66}) обещает, что факт
 * {@code FUND_TRANSFER} и движение {@code FundTransaction} гасят друг друга. До правки его
 * нарушали четыре пути — факт к плану, правка факта, удаление факта и факт без копилки через
 * {@code POST /events/facts}; кнопка «Пополнить» была единственной, кто писал обе записи.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-27-fund-transfer-fact-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FundTransferFactIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    /** Контейнер один на класс: без уборки деньги одного теста протекают в соседний. */
    @BeforeEach
    void resetMoneyState() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events WHERE type = 'FUND_TRANSFER'");
        jdbc.update("DELETE FROM fund_account_links");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
        // Якорь за три дня до сегодня: факты вчерашней и сегодняшней датой лежат после него
        // и уменьшают остаток счёта. Якорь «сегодня» или «вчера» спрятал бы их по правилу
        // дня якоря (ANO-125) — и закон сохранения сошёлся бы по совпадению.
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 3, 500000, ?::uuid, now())
                """, accountId);
    }

    // ── путь 2 (ANO-169): факт к плану перевода ─────────────────────────────

    @Test
    @DisplayName("факт к плану перевода кладёт деньги в копилку плана — датой факта")
    void linkedFact_movesMoneyIntoPlanFund() throws Exception {
        String fundId = createFund("Горнолыжка");
        String planId = createTransferPlan(fundId, "1000", TODAY);
        BigDecimal liquidToday = liquid(TODAY);
        BigDecimal liquidYesterday = liquid(YESTERDAY);

        String factId = idOf(linkedFact(planId, "1000", YESTERDAY).andExpect(status().isOk()));

        assertThat(fundBalance(fundId)).as("копилка получила перевод").isEqualByComparingTo("1000");
        assertThat(eventFund(factId)).as("факт помнит копилку плана").isEqualTo(fundId);
        assertThat(movementDate(factId)).as("движение — датой факта").isEqualTo(YESTERDAY);
        assertThat(liquid(TODAY)).as("перемещение между своими деньгами").isEqualByComparingTo(liquidToday);
        assertThat(liquid(YESTERDAY)).as("и в день факта тоже").isEqualByComparingTo(liquidYesterday);
    }

    @Test
    @DisplayName("факт к плану копилки на счёте — отказ FUND_ON_ACCOUNT, факта нет")
    void linkedFact_toAccountBackedFund_isRefused() throws Exception {
        String fundId = createFundOnAccount("Цель на карте", firstTrackedAccountId());
        String planId = createTransferPlan(fundId, "1000", TODAY);

        linkedFact(planId, "1000", TODAY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("FUND_ON_ACCOUNT"));

        assertThat(transferFactsOf(planId)).as("отказ откатывает и сам факт").isZero();
    }

    // ── ANO-201: правка и удаление перевода ─────────────────────────────────

    @Test
    @DisplayName("правка суммы перевода двигает копилку на разницу")
    void editTransferFact_movesFundByDelta() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, "100", true, null).andExpect(status().isOk());
        String factId = transferFactOf(fundId);
        BigDecimal liquidBefore = liquid(TODAY);

        patchFact(factId, "150").andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("150");
        assertThat(liquid(TODAY)).as("капитал не сдвинулся").isEqualByComparingTo(liquidBefore);
    }

    @Test
    @DisplayName("удаление перевода забирает деньги из копилки")
    void deleteTransferFact_takesMoneyBackFromFund() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, "100", true, null).andExpect(status().isOk());
        String factId = transferFactOf(fundId);
        BigDecimal liquidBefore = liquid(TODAY);

        deleteEvent(factId).andExpect(status().isNoContent());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("0");
        assertThat(liquid(TODAY)).as("деньги не появились из воздуха").isEqualByComparingTo(liquidBefore);
    }

    @Test
    @DisplayName("удалить перевод, когда из копилки уже взяли часть, нельзя — FUND_HOLDS_LESS")
    void deleteTransferFact_whenFundHoldsLess_isRefused() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, "100", true, null).andExpect(status().isOk());
        String depositId = transferFactOf(fundId);
        transfer(fundId, "-60", true, null).andExpect(status().isOk());

        deleteEvent(depositId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("FUND_HOLDS_LESS"));

        assertThat(fundBalance(fundId)).as("отказ ничего не списал").isEqualByComparingTo("40");
        assertThat(isDeleted(depositId)).as("и факт на месте").isFalse();
    }

    @Test
    @DisplayName("перевод удалённой копилки не правится и не удаляется — FUND_CLOSED")
    void transferFactOfDeletedFund_isClosed() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, "100", true, null).andExpect(status().isOk());
        String factId = transferFactOf(fundId);
        mockMvc.perform(delete("/api/v1/funds/{id}", fundId).param("money", "RETURN"))
                .andExpect(status().isNoContent());
        BigDecimal liquidBefore = liquid(TODAY);

        patchFact(factId, "150")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("FUND_CLOSED"));
        deleteEvent(factId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("FUND_CLOSED"));

        assertThat(isDeleted(factId)).isFalse();
        assertThat(liquid(TODAY)).isEqualByComparingTo(liquidBefore);
    }

    @Test
    @DisplayName("старый путь PATCH на плане перевода: и первое заполнение, и смена суммы двигают копилку")
    void legacyPatchOnTransferPlan_keepsFundInStep() throws Exception {
        String fundId = createFund("Отпуск");
        String planId = createTransferPlan(fundId, "100", TODAY);
        BigDecimal liquidBefore = liquid(TODAY);

        patchFact(planId, "100").andExpect(status().isOk());
        assertThat(fundBalance(fundId)).as("первое заполнение переводило и раньше").isEqualByComparingTo("100");

        patchFact(planId, "150").andExpect(status().isOk());
        assertThat(fundBalance(fundId)).as("смена суммы — тоже").isEqualByComparingTo("150");
        assertThat(liquid(TODAY)).isEqualByComparingTo(liquidBefore);
    }

    /**
     * Ревью #96. Старый путь пишет факт в строку плана, и дата факта — дата плана. Будущей датой
     * копилка выросла бы уже сегодня, а счёт — только в день плана: ещё не ушедшие деньги можно
     * было бы снять из копилки. Факт к плану и ручка перевода будущую дату не пускают (ANO-155).
     */
    @Test
    @DisplayName("старый путь PATCH на плане перевода будущей датой — 400, копилка не тронута")
    void legacyPatchOnFutureTransferPlan_isRejected() throws Exception {
        String fundId = createFund("Отпуск");
        String planId = createTransferPlan(fundId, "100", TODAY.plusDays(3));

        patchFact(planId, "100").andExpect(status().isBadRequest());

        assertThat(fundBalance(fundId)).as("копилка не выросла раньше перевода").isEqualByComparingTo("0");
        assertThat(movementsOf(fundId)).as("движения нет").isZero();
        assertThat(factAmountOf(planId)).as("и факта на плане нет").isNull();
    }

    /**
     * Запрет — только на запись суммы. Такие записи уже есть: до правки старый путь двигал
     * копилку и у будущего плана. Снять факт — единственный способ их исправить.
     */
    @Test
    @DisplayName("снять факт с плана перевода будущей датой можно — копилка отдаёт деньги")
    void legacyPatchRemovingFactFromFutureTransferPlan_returnsMoney() throws Exception {
        String fundId = createFund("Отпуск");
        String planId = createTransferPlan(fundId, "100", TODAY);
        patchFact(planId, "100").andExpect(status().isOk());
        // Как лежат записи, сделанные до правки: факт на плане, а дата плана — в будущем.
        jdbc.update("UPDATE financial_events SET date = CURRENT_DATE + 3 WHERE id = ?::uuid", planId);

        patchFact(planId, "null").andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("0");
        assertThat(factAmountOf(planId)).isNull();
    }

    // ── факт без копилки и перевод с датой ──────────────────────────────────

    @Test
    @DisplayName("POST /events/facts с типом FUND_TRANSFER — 400: у такого факта нет копилки")
    void standaloneTransferFact_isRejected() throws Exception {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE type = 'EXPENSE' AND is_deleted = false LIMIT 1",
                String.class);
        String body = """
                {"date": "%s", "categoryId": "%s", "type": "FUND_TRANSFER", "factAmount": 100}
                """.formatted(TODAY, categoryId);

        mockMvc.perform(post("/api/v1/events/facts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        Integer facts = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE type = 'FUND_TRANSFER'", Integer.class);
        assertThat(facts).isZero();
    }

    @Test
    @DisplayName("перевод с датой: факт и движение — этой датой; дата в будущем — 400")
    void transferWithDate_recordsOnThatDate() throws Exception {
        String fundId = createFund("Отпуск");
        BigDecimal liquidYesterday = liquid(YESTERDAY);

        transfer(fundId, "500", true, YESTERDAY).andExpect(status().isOk());
        String factId = transferFactOf(fundId);

        assertThat(jdbc.queryForObject("SELECT date FROM financial_events WHERE id = ?::uuid",
                LocalDate.class, factId)).isEqualTo(YESTERDAY);
        assertThat(movementDate(factId)).isEqualTo(YESTERDAY);
        assertThat(liquid(YESTERDAY)).as("вчерашний ликвид сходится").isEqualByComparingTo(liquidYesterday);

        transfer(fundId, "500", true, TODAY.plusDays(1)).andExpect(status().isBadRequest());
        assertThat(fundBalance(fundId)).isEqualByComparingTo("500");
    }

    // ── оснастка ────────────────────────────────────────────────────────────

    private String createFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }

    private String createFundOnAccount(String name, String accountId) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at, account_id)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now(), ?::uuid)
                """, id, name, accountId);
        return id;
    }

    private String firstTrackedAccountId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_deleted = false"
                        + " AND track_balance = true ORDER BY is_default DESC, created_at LIMIT 1",
                String.class);
    }

    /** План перевода — как его создаёт быстрый ввод: без категории, характер «Ожидание». */
    private String createTransferPlan(String fundId, String amount, LocalDate date) throws Exception {
        String body = """
                {"date": "%s", "type": "FUND_TRANSFER", "plannedAmount": %s,
                 "priority": "MEDIUM", "targetFundId": "%s"}
                """.formatted(date, amount, fundId);
        return idOf(mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()));
    }

    private ResultActions linkedFact(String planId, String amount, LocalDate date) throws Exception {
        return mockMvc.perform(post("/api/v1/events/{planId}/facts", planId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\": \"%s\", \"factAmount\": %s}".formatted(date, amount)));
    }

    private ResultActions patchFact(String eventId, String amount) throws Exception {
        return mockMvc.perform(patch("/api/v1/events/{id}/fact", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"factAmount\": %s}".formatted(amount)));
    }

    private ResultActions deleteEvent(String eventId) throws Exception {
        return mockMvc.perform(delete("/api/v1/events/{id}", eventId));
    }

    /** @param date {@code null} — без даты, как кнопка «Пополнить» */
    private ResultActions transfer(String fundId, String amount, boolean confirm, LocalDate date)
            throws Exception {
        String body = date == null
                ? "{\"amount\": %s, \"confirm\": %s}".formatted(amount, confirm)
                : "{\"amount\": %s, \"confirm\": %s, \"date\": \"%s\"}".formatted(amount, confirm, date);
        return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id").asText();
    }

    private BigDecimal liquid(LocalDate date) {
        return capitalService.cashLiquidAt(date);
    }

    private BigDecimal fundBalance(String fundId) {
        return jdbc.queryForObject("SELECT current_balance FROM target_funds WHERE id = ?::uuid",
                BigDecimal.class, fundId);
    }

    /** Самый ранний живой факт перевода в эту копилку. */
    private String transferFactOf(String fundId) {
        return jdbc.queryForObject("""
                SELECT id::text FROM financial_events
                WHERE type = 'FUND_TRANSFER' AND event_kind = 'FACT' AND is_deleted = false
                  AND target_fund_id = ?::uuid
                ORDER BY created_at LIMIT 1
                """, String.class, fundId);
    }

    private int transferFactsOf(String planId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE parent_event_id = ?::uuid AND is_deleted = false",
                Integer.class, planId);
        return n == null ? 0 : n;
    }

    private int movementsOf(String fundId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM fund_transactions WHERE fund_id = ?::uuid AND is_deleted = false",
                Integer.class, fundId);
        return n == null ? 0 : n;
    }

    private BigDecimal factAmountOf(String eventId) {
        return jdbc.queryForObject("SELECT fact_amount FROM financial_events WHERE id = ?::uuid",
                BigDecimal.class, eventId);
    }

    private String eventFund(String eventId) {
        return jdbc.queryForObject(
                "SELECT target_fund_id::text FROM financial_events WHERE id = ?::uuid",
                String.class, eventId);
    }

    /** Дата живого движения копилки, связанного с событием общим ключом. */
    private LocalDate movementDate(String eventId) {
        return jdbc.queryForObject("""
                SELECT t.transaction_date FROM fund_transactions t
                JOIN financial_events e ON e.idempotency_key = t.idempotency_key
                WHERE e.id = ?::uuid AND t.is_deleted = false
                """, LocalDate.class, eventId);
    }

    private boolean isDeleted(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_deleted FROM financial_events WHERE id = ?::uuid", Boolean.class, eventId));
    }
}
