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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-86 и ANO-87: деньги в копилке ходят в обе стороны, удаление их не уничтожает.
 *
 * <p>Проверяется ЗАКОН СОХРАНЕНИЯ, а не отдельные методы: спека капитала
 * ({@code 2026-05-10-capital-net-worth-design.md:66}) обещает, что FUND_TRANSFER и
 * FundTransaction взаимно компенсируются. Утверждение о системе ловит дефект независимо
 * от того, в скольких местах записано правило — а оно записано не в одном.
 *
 * <p>План: {@code docs/superpowers/plans/2026-09-12-fund-money-flow.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FundMoneyFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    /**
     * Возвращает денежное состояние к нулю перед каждым тестом.
     *
     * <p>Контейнер один на класс, и без этой уборки тесты протекают друг в друга: перевод на
     * 99 999 999 из проверки подтверждения уводил счёт в минус, и последующие тесты упирались
     * в проверку достаточности. Тесты становились зависимыми от порядка запуска — то есть
     * измеряли не то, что заявляют. Сиды (счета, категории) не трогаем, чистим только деньги.
     */
    @BeforeEach
    void resetMoneyState() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events WHERE type = 'FUND_TRANSFER'");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
    }

    /**
     * Даёт дефолтному счёту якорь, чтобы у продукта было основание для мнения о свободных
     * деньгах. Без якоря {@code freeMoneyAt} равен нулю, и ЛЮБОЙ перевод считался бы
     * превышением остатка — тест проверял бы не то, что заявляет.
     *
     * <p><b>Якорь ставится ВЧЕРАШНИМ днём, и это принципиально.</b> Перевод создаёт факт
     * сегодняшней датой, а по семантике дня якоря (ANO-15 §5) операции дня чекпоинта в
     * остаток не добавляются: при якоре «сегодня» счёт не уменьшился бы, копилка выросла, и
     * ликвид совпал бы с исходным ПО СОВПАДЕНИЮ — это дефект ANO-125, и он замаскировал бы
     * ровно то, что мы здесь проверяем. Тесты этого класса написаны так, чтобы измерять
     * поведение копилки, а не наткнуться на соседний дефект.
     */
    private void anchorDefaultAccount(String amount) {
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 1, ?::numeric, ?::uuid, now())
                """, amount, accountId);
    }

    @Test
    @DisplayName("ANO-87: из копилки можно забрать деньги обратно")
    void withdraw_returnsMoneyToAccount() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-5000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("ANO-87: снять больше накопленного нельзя — в копилке столько нет")
    void withdraw_moreThanBalance_isRefused() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-20001"), null).andExpect(status().isConflict());

        assertThat(fundBalance(fundId))
                .as("отказ не должен списать ничего").isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("ANO-87: перевод больше остатка требует подтверждения")
    void transfer_overBalance_needsConfirmation() throws Exception {
        // ANO-157: якорь здесь ОБЯЗАТЕЛЕН, и это не оснастка, а предмет. Без него тест
        // проходил, опираясь на дефект: свободных денег ноль, потому что продукт ничего не
        // знает, — и предупреждение приходило из незнания. Проверялось не правило ANO-87,
        // а тот самый ноль-незнание, который ANO-157 перестал считать запретом.
        anchorDefaultAccount("1000");
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("99999999"), null).andExpect(status().isConflict());
        assertThat(fundBalance(fundId))
                .as("без подтверждения не должно пройти ничего").isEqualByComparingTo("0");

        transfer(fundId, new BigDecimal("99999999"), true).andExpect(status().isOk());
        assertThat(fundBalance(fundId)).isEqualByComparingTo("99999999");
    }

    // ANO-156: здесь стоял deletedFund_dropsOutOfLiquid — он помечал копилку удалённой В
    // ОБХОД сервиса и проверял, что её деньги уйдут из ликвида. Проверялся фильтр
    // t.fund.deleted в запросе суммы копилок, и другого смысла у обхода не было.
    //
    // Фильтр снят намеренно: он применял флаг «удалена сейчас» ко всем прошлым датам и
    // переписывал историю. Гарантия ANO-86 переехала на компенсирующее движение, а обход
    // сервиса — это ровно обход компенсации, то есть больше не эквивалент удаления.
    //
    // Само утверждение не потеряно: delete_spent_dropsCapital проверяет то же самое —
    // капитал падает ровно на сумму — но через настоящий путь. Строки, удалённые в базе
    // руками до этой правки, лечит миграция V24 (DisposedFundMigrationIT).

    @Test
    @DisplayName("ANO-86: удаление копилки с деньгами без ответа — 409 с суммой")
    void delete_withMoney_withoutChoice_isRefused() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}", fundId))
                .andExpect(status().isConflict());

        assertThat(isDeleted(fundId)).as("отказ не должен удалять").isFalse();
    }

    @Test
    @DisplayName("ANO-86: «вернуть» — деньги возвращаются, капитал не меняется")
    void delete_return_givesMoneyBack() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        BigDecimal liquidStart = capitalService.cashLiquidAt(LocalDate.now());
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=RETURN", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("возврат своих же денег не меняет чистую стоимость")
                .isEqualByComparingTo(liquidStart);
        assertThat(isDeleted(fundId)).isTrue();
    }

    @Test
    @DisplayName("ANO-86: «потрачено» — капитал падает ровно на сумму")
    void delete_spent_dropsCapital() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());
        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        assertThat(liquidBefore.subtract(capitalService.cashLiquidAt(LocalDate.now())))
                .as("вещь куплена — этих денег в чистой стоимости больше нет")
                .isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("ANO-156: «потрачено» не переписывает прошлое")
    void delete_spent_doesNotRewriteHistory() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        LocalDate past = LocalDate.now().minusMonths(1);
        contributeOn(fundId, new BigDecimal("20000"), past);
        BigDecimal liquidPastBefore = capitalService.cashLiquidAt(past);

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(past))
                .as("месяц назад деньги лежали в копилке — удаление сегодня не меняет прошлое")
                .isEqualByComparingTo(liquidPastBefore);
    }

    @Test
    @DisplayName("ANO-156: «вернуть» тоже не переписывает прошлое")
    void delete_return_doesNotRewriteHistory() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        LocalDate past = LocalDate.now().minusMonths(1);
        contributeOn(fundId, new BigDecimal("20000"), past);
        BigDecimal liquidPastBefore = capitalService.cashLiquidAt(past);

        mockMvc.perform(delete("/api/v1/funds/{id}?money=RETURN", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(past))
                .as("возврат датируется сегодняшним днём и прошлого не касается")
                .isEqualByComparingTo(liquidPastBefore);
    }

    @Test
    @DisplayName("ANO-156: «потрачено» списывает деньги движением, а не флагом")
    void delete_spent_writesCompensatingMovement() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        assertThat(movementSum(fundId))
                .as("деньги ушли из копилки — это обязано быть записано движением")
                .isEqualByComparingTo("0");
        assertThat(fundBalance(fundId))
                .as("поле и сумма движений не имеют права разъезжаться")
                .isEqualByComparingTo("0");
        assertThat(fundTransferEvents(fundId))
                .as("события возврата быть не должно: деньги потрачены, а не возвращены")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ANO-86: пустая копилка удаляется без выбора")
    void delete_emptyFund_needsNoChoice() throws Exception {
        String fundId = createFund("Пустая");

        mockMvc.perform(delete("/api/v1/funds/{id}", fundId))
                .andExpect(status().isNoContent());

        assertThat(isDeleted(fundId)).isTrue();
    }

    @Test
    @DisplayName("ANO-86: при «потрачено» журнал перестаёт звать трату переводом")
    void delete_spent_renamesJournalEntry() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        String description = jdbc.queryForObject(
                "SELECT description FROM financial_events"
                        + " WHERE target_fund_id = ?::uuid AND is_deleted = false LIMIT 1",
                String.class, fundId);

        assertThat(description)
                .as("место, куда переводили, больше не существует — это была трата")
                .isEqualTo("Отпуск");
    }

    @Test
    @DisplayName("ANO-86: при «вернуть» журнал не переписывается — перевод и был переводом")
    void delete_return_keepsJournalWording() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=RETURN", fundId))
                .andExpect(status().isNoContent());

        String description = jdbc.queryForObject(
                "SELECT description FROM financial_events"
                        + " WHERE target_fund_id = ?::uuid AND is_deleted = false"
                        + " ORDER BY created_at LIMIT 1",
                String.class, fundId);

        assertThat(description)
                .as("переписывать прошлое без нужды нельзя: деньги вернулись, перевод состоялся")
                .isEqualTo("В копилку: Отпуск");
    }

    // ── закон сохранения ─────────────────────────────────────────────────────

    @Test
    @DisplayName("закон сохранения: перевод туда и обратно не меняет ликвид")
    void conservation_transferRoundTrip_keepsLiquid() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        BigDecimal start = capitalService.cashLiquidAt(LocalDate.now());

        transfer(fundId, new BigDecimal("15000"), null).andExpect(status().isOk());
        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("перемещение между своими деньгами не создаёт и не уничтожает их")
                .isEqualByComparingTo(start);

        transfer(fundId, new BigDecimal("-15000"), null).andExpect(status().isOk());
        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("и обратное перемещение тоже").isEqualByComparingTo(start);
    }

    @Test
    @DisplayName("копилка со счётом удаляется без выбора, остаток счёта не тронут")
    void accountBackedFund_deletesWithoutChoice() throws Exception {
        anchorDefaultAccount("500000");
        String accountId = firstTrackedAccountId();
        String fundId = createFundOnAccount("Цель на карте", accountId);
        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());

        mockMvc.perform(delete("/api/v1/funds/{id}", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("деньги лежат на счёте и никуда не делись — удалена только цель поверх них")
                .isEqualByComparingTo(liquidBefore);
        assertThat(isDeleted(fundId)).isTrue();
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /** Копилка без счёта — базовый случай: у неё собственный баланс. */
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

    /** Копилка поверх счёта — не базовый случай, своих денег не имеет (спека §4.6). */
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
                        + " AND track_balance = true LIMIT 1", String.class);
    }

    /** @param confirm {@code null} — без подтверждения */
    private ResultActions transfer(String fundId, BigDecimal amount, Boolean confirm)
            throws Exception {
        String body = confirm == null
                ? "{\"amount\": " + amount.toPlainString() + "}"
                : "{\"amount\": " + amount.toPlainString() + ", \"confirm\": " + confirm + "}";
        return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private BigDecimal fundBalance(String fundId) {
        return jdbc.queryForObject(
                "SELECT current_balance FROM target_funds WHERE id = ?::uuid",
                BigDecimal.class, fundId);
    }

    /**
     * Взнос в копилку ПРОШЛОЙ датой: движение, событие FUND_TRANSFER и баланс копилки —
     * ровно то, что создал бы перевод, но задним числом.
     *
     * <p>Через API так нельзя: {@code doTransfer} всегда ставит сегодняшнюю дату. А без
     * прошлой даты дефект ANO-156 не воспроизводится вовсе — он весь про то, что удаление
     * сегодня меняет числа за прошлые месяцы.
     */
    private void contributeOn(String fundId, BigDecimal amount, LocalDate date) {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE type = 'EXPENSE' AND is_deleted = false LIMIT 1",
                String.class);
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?::numeric, ?::date, false, now())
                """, fundId, amount.toPlainString(), date.toString());
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, fact_amount, status, priority,
                     is_deleted, event_kind, created_at, target_fund_id, description)
                VALUES (gen_random_uuid(), ?::date, ?::uuid, 'FUND_TRANSFER', ?::numeric,
                        'EXECUTED', 'MEDIUM', false, 'FACT', now(), ?::uuid, 'В копилку')
                """, date.toString(), categoryId, amount.toPlainString(), fundId);
        jdbc.update("UPDATE target_funds SET current_balance = ?::numeric WHERE id = ?::uuid",
                amount.toPlainString(), fundId);
    }

    /** Сумма живых движений копилки — то самое, что складывает запрос суммы копилок. */
    private BigDecimal movementSum(String fundId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM fund_transactions"
                        + " WHERE fund_id = ?::uuid AND is_deleted = false",
                BigDecimal.class, fundId);
    }

    /** Живых событий FUND_TRANSFER у копилки: «вернуть» добавляет второе, «потрачено» — нет. */
    private int fundTransferEvents(String fundId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE target_fund_id = ?::uuid"
                        + " AND is_deleted = false AND type = 'FUND_TRANSFER'",
                Integer.class, fundId);
        return n == null ? 0 : n;
    }

    private boolean isDeleted(String fundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_deleted FROM target_funds WHERE id = ?::uuid", Boolean.class, fundId));
    }

}
