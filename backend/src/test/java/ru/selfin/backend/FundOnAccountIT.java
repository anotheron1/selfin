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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.AccountBalanceService;
import ru.selfin.backend.service.CapitalService;
import ru.selfin.backend.service.OwnerService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Срез 4, п. 6 (ANO-262): копилка на своём счёте принимает перевод. Деньги уходят с основной на счёт копилки, капитал
 * не меняется; снятие — зеркально. В этой связке уже было две ошибки с деньгами — ANO-158 (привязка переписывала
 * прошлое) и ANO-163 (отвязанная копилка держала деньги, которых на карте нет), оба случая здесь.
 *
 * <p>«Свободно» — {@code GET /pocket}, «по записям» — лист «Обновить остатки», капитал — {@code /capital/summary},
 * как на экране. Пользователь MockMvc — синтетический владелец с «Основной картой» (V20).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FundOnAccountIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capital;
    @Autowired AccountBalanceService balances;

    final LocalDate today = LocalDate.now();
    final UUID me = OwnerService.SYNTHETIC_OWNER;
    String deposit;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM fund_account_links");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("DELETE FROM accounts WHERE user_id = ? AND name NOT IN ('Основная карта', 'Наличные')", me);
        // Обновление основной за три дня до сегодня: переводы вчера и сегодня лежат после него и двигают её остаток.
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 3, 500000, ?::uuid, now())""", mainCard());
        deposit = id(mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Вклад","purpose":"SAVINGS","balance":100000}"""))
                .andExpect(status().isCreated()));
    }

    @Test
    @DisplayName("перевод: основная меньше, счёт копилки больше, капитал прежний; «Уже в копилках» её не вычитает; "
            + "снятие — зеркально")
    void transfer_movesMoneyOffCard_withdrawMirrors() throws Exception {
        String fund = fund("Квартира", deposit);
        BigDecimal capital0 = capitalTotal(), free0 = pocket(), card0 = byRecords(mainCard()), dep0 = byRecords(deposit);

        transfer(fund, "8000", null).andExpect(status().isOk());

        assertThat(byRecords(mainCard())).as("деньги ушли с карты").isEqualByComparingTo(card0.subtract(n("8000")));
        assertThat(byRecords(deposit)).as("и легли на счёт копилки").isEqualByComparingTo(dep0.add(n("8000")));
        assertThat(capitalTotal()).as("переехали, а не пропали").isEqualByComparingTo(capital0);
        assertThat(pocket()).as("вклад в «Свободно» не входит; копилка не вычтена второй раз")
                .isEqualByComparingTo(free0.subtract(n("8000")));
        assertThat(fundBalance(fund)).as("накоплено — остаток счёта").isEqualByComparingTo(dep0.add(n("8000")));
        assertThat(card(deposit).get("transfersSinceUpdate").decimalValue()).isEqualByComparingTo("8000");

        transfer(fund, "-3000", null).andExpect(status().isOk());

        assertThat(byRecords(mainCard())).isEqualByComparingTo(card0.subtract(n("5000")));
        assertThat(byRecords(deposit)).isEqualByComparingTo(dep0.add(n("5000")));
        assertThat(capitalTotal()).isEqualByComparingTo(capital0);
    }

    @Test
    @DisplayName("снять больше остатка счёта копилки — 409, ничего не записано")
    void withdraw_overAccountBalance_refused() throws Exception {
        String fund = fund("Квартира", deposit);

        transfer(fund, "-100001", null).andExpect(status().isConflict());

        assertThat(movements()).isZero();
        assertThat(byRecords(deposit)).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("новое обновление счёта включает прежние переводы: «по записям» — оно, рост сверх переводов — разница")
    void newUpdate_absorbsEarlierTransfers() throws Exception {
        String fund = fund("Квартира", deposit);
        transfer(fund, "8000", null).andExpect(status().isOk());
        BigDecimal capital0 = capitalTotal(), card0 = byRecords(mainCard());

        JsonNode result = json(mvc.perform(post("/api/v1/balance-checkpoints/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"accountId\":\"%s\",\"amount\":108300}]}".formatted(deposit)))
                .andExpect(status().isOk()));

        assertThat(result.get("cards").get(0).get("difference").decimalValue())
                .as("переводы уже в «по записям» — в разнице только проценты").isEqualByComparingTo("300");
        assertThat(byRecords(deposit)).as("перевод не прибавлен второй раз").isEqualByComparingTo("108300");
        assertThat(card(deposit).get("transfersSinceUpdate").decimalValue()).isEqualByComparingTo("0");
        assertThat(byRecords(mainCard())).isEqualByComparingTo(card0);
        assertThat(capitalTotal()).isEqualByComparingTo(capital0.add(n("300")));
    }

    @Test
    @DisplayName("«Оплачено» у взноса в копилку на счёте: основная меньше, счёт больше, капитал прежний")
    void linkedFact_toFundOnAccount_movesMoney() throws Exception {
        String fund = fund("Квартира", deposit);
        String plan = id(mvc.perform(post("/api/v1/events").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"date":"%s","type":"FUND_TRANSFER","plannedAmount":1000,"priority":"MEDIUM",
                                 "targetFundId":"%s"}""".formatted(today, fund)))
                .andExpect(status().isOk()));
        BigDecimal capital0 = capitalTotal(), card0 = byRecords(mainCard()), dep0 = byRecords(deposit);

        mvc.perform(post("/api/v1/events/{id}/facts", plan).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + today + "\",\"factAmount\":1000}"))
                .andExpect(status().isOk());

        assertThat(byRecords(mainCard())).isEqualByComparingTo(card0.subtract(n("1000")));
        assertThat(byRecords(deposit)).isEqualByComparingTo(dep0.add(n("1000")));
        assertThat(capitalTotal()).isEqualByComparingTo(capital0);
        assertThat(movements()).isEqualTo(1);
    }

    @Test
    @DisplayName("ANO-158: перевод до привязки копилки к счёту основную не трогает, капитал прошлых дней не переписан")
    void transferBeforeLink_staysOnCard() throws Exception {
        String fund = fund("Отпуск", null);
        LocalDate past = today.minusDays(2);
        // Вклад обновлён раньше перевода: перевод в окне его обновления, и решает только день привязки.
        jdbc.update("UPDATE balance_checkpoints SET date = CURRENT_DATE - 3 WHERE account_id = ?::uuid", deposit);
        transfer(fund, "20000", past).andExpect(status().isOk());
        BigDecimal card0 = byRecords(mainCard()), dep0 = byRecords(deposit);
        BigDecimal liquidPast = capital.liquidAt(past), envelopesPast = balances.envelopesAt(past);

        link(fund, deposit);

        assertThat(byRecords(mainCard())).as("в день перевода копилка была конвертом").isEqualByComparingTo(card0);
        assertThat(byRecords(deposit)).isEqualByComparingTo(dep0);
        assertThat(capital.liquidAt(past)).isEqualByComparingTo(liquidPast);
        assertThat(balances.envelopesAt(past)).isEqualByComparingTo(envelopesPast).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("ANO-163: отвязанная копилка не держит денег, ушедших на счёт: поле, «Уже в копилках» и «Свободно»")
    void unlink_keepsTransfersOnAccount() throws Exception {
        String fund = fund("Квартира", deposit);
        // Копилка на вкладе с трёх дней назад, вклад обновлён тогда же, перевод — вчера: день отвязки (сегодня) — уже
        // день конверта (ANO-163), а вчера копилка жила на счёте.
        jdbc.update("UPDATE fund_account_links SET linked_from = CURRENT_DATE - 3 WHERE fund_id = ?::uuid", fund);
        jdbc.update("UPDATE balance_checkpoints SET date = CURRENT_DATE - 3 WHERE account_id = ?::uuid", deposit);
        transfer(fund, "8000", today.minusDays(1)).andExpect(status().isOk());
        BigDecimal free0 = pocket(), capital0 = capitalTotal(), dep0 = byRecords(deposit);

        link(fund, null);

        assertThat(fundBalance(fund)).as("деньги лежат на вкладе, а не в копилке на карте").isEqualByComparingTo("0");
        assertThat(balances.envelopesAt(today)).isEqualByComparingTo("0");
        assertThat(pocket()).isEqualByComparingTo(free0);
        assertThat(byRecords(deposit)).as("перевод остался на счёте").isEqualByComparingTo(dep0);
        assertThat(capitalTotal()).isEqualByComparingTo(capital0);
    }

    @Test
    @DisplayName("счёт копилки без обновления — перевод 400, ничего не записано")
    void accountWithoutUpdate_refused() throws Exception {
        String oldDeposit = id(mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Старый вклад","kind":"DEPOSIT","trackBalance":true}"""))
                .andExpect(status().isCreated()));
        String fund = fund("Квартира", oldDeposit);
        BigDecimal card0 = byRecords(mainCard());

        transfer(fund, "8000", null).andExpect(status().isBadRequest());

        assertThat(movements()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_events WHERE type = 'FUND_TRANSFER'", Long.class))
                .isZero();
        assertThat(byRecords(mainCard())).isEqualByComparingTo(card0);
    }

    @Test
    @DisplayName("ревью Codex на #240: дата перевода перенесена с дня на счёте на день конверта — конверт и поле сходятся")
    void dateOnlyEdit_acrossUnlink_settlesEnvelope() throws Exception {
        String fund = fund("Квартира", deposit);
        jdbc.update("UPDATE fund_account_links SET linked_from = CURRENT_DATE - 3 WHERE fund_id = ?::uuid", fund);
        jdbc.update("UPDATE balance_checkpoints SET date = CURRENT_DATE - 3 WHERE account_id = ?::uuid", deposit);
        transfer(fund, "8000", today.minusDays(1)).andExpect(status().isOk());
        link(fund, null);
        BigDecimal capital0 = capitalTotal();

        mvc.perform(patch("/api/v1/events/{id}/fact", transferEvent("8000")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factAmount\":8000,\"date\":\"" + today + "\"}"))
                .andExpect(status().isOk());

        assertThat(balances.envelopesAt(today)).as("перевод сегодня — конвертом").isEqualByComparingTo("8000");
        assertThat(fundBalance(fund)).as("поле копилки — те же деньги").isEqualByComparingTo("8000");
        assertThat(capitalTotal()).isEqualByComparingTo(capital0);
    }

    @Test
    @DisplayName("ревью Codex на #240: удалить перевод, после которого со счёта копилки сняли всё, — 409, счёт не в минус")
    void deleteTransfer_wouldMakeAccountNegative_refused() throws Exception {
        String fund = fund("Квартира", deposit);
        transfer(fund, "8000", null).andExpect(status().isOk());
        transfer(fund, "-108000", null).andExpect(status().isOk());
        String event = transferEvent("8000");

        mvc.perform(delete("/api/v1/events/{id}", event)).andExpect(status().isConflict());

        assertThat(byRecords(deposit)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM financial_events WHERE id = ?::uuid", Boolean.class, event))
                .isFalse();
    }

    @Test
    @DisplayName("ревью Codex на #240: снятие и отвязка в один день — 409, копилка осталась на счёте, «Свободно» прежнее")
    void unlinkSameDayAfterWithdraw_refused() throws Exception {
        String fund = fund("Квартира", deposit);
        transfer(fund, "-3000", null).andExpect(status().isOk());
        BigDecimal free0 = pocket();

        mvc.perform(put("/api/v1/funds/{id}", fund).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Квартира\",\"targetAmount\":1000000}"))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT account_id::text FROM target_funds WHERE id = ?::uuid", String.class, fund))
                .isEqualTo(deposit);
        assertThat(balances.envelopesAt(today)).isEqualByComparingTo("0");
        assertThat(pocket()).isEqualByComparingTo(free0);
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    private String transferEvent(String amount) {
        return jdbc.queryForObject("SELECT id::text FROM financial_events WHERE type = 'FUND_TRANSFER' AND fact_amount = ?",
                String.class, new BigDecimal(amount));
    }

    private String fund(String name, String accountId) throws Exception {
        return id(mvc.perform(post("/api/v1/funds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"targetAmount\":1000000%s}".formatted(name,
                                accountId != null ? ",\"accountId\":\"" + accountId + "\"" : "")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isBetween(200, 201)));
    }

    private void link(String fund, String accountId) throws Exception {
        mvc.perform(put("/api/v1/funds/{id}", fund).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Фонд\",\"targetAmount\":1000000%s}".formatted(
                                accountId != null ? ",\"accountId\":\"" + accountId + "\"" : "")))
                .andExpect(status().isOk());
    }

    private ResultActions transfer(String fund, String amount, LocalDate date) throws Exception {
        return mvc.perform(post("/api/v1/funds/{id}/transfer", fund).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":%s,\"confirm\":true%s}".formatted(amount,
                        date != null ? ",\"date\":\"" + date + "\"" : "")));
    }

    private BigDecimal fundBalance(String fund) throws Exception {
        for (JsonNode f : json(mvc.perform(get("/api/v1/funds")).andExpect(status().isOk())).get("funds")) {
            if (f.get("id").asText().equals(fund)) return f.get("currentBalance").decimalValue();
        }
        throw new AssertionError("нет копилки " + fund);
    }

    private JsonNode card(String account) throws Exception {
        for (JsonNode c : json(mvc.perform(get("/api/v1/balance-checkpoints/sheet")).andExpect(status().isOk()))
                .get("cards")) {
            if (c.get("accountId").asText().equals(account)) return c;
        }
        throw new AssertionError("в листе нет карты " + account);
    }

    private BigDecimal byRecords(String account) throws Exception {
        return card(account).get("byRecords").decimalValue();
    }

    private BigDecimal capitalTotal() throws Exception {
        return json(mvc.perform(get("/api/v1/capital/summary")).andExpect(status().isOk())).get("total").decimalValue();
    }

    private BigDecimal pocket() throws Exception {
        return new BigDecimal(json(mvc.perform(get("/api/v1/pocket")).andExpect(status().isOk())).get("pocket").asText());
    }

    private long movements() {
        return jdbc.queryForObject("SELECT count(*) FROM fund_transactions WHERE NOT is_deleted", Long.class);
    }

    private String mainCard() {
        return jdbc.queryForObject("SELECT id::text FROM accounts WHERE user_id = ? AND is_default AND NOT is_deleted",
                String.class, me);
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private String id(ResultActions r) throws Exception {
        return json(r).get("id").asText();
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
