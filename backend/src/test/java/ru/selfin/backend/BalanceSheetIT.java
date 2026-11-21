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
import ru.selfin.backend.service.Consent;
import ru.selfin.backend.service.OwnerService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Срез 4, пп. 3, 4, 12: лист «Обновить остатки», пакет обновлений с предпросмотром, карта с остатком при заведении,
 * «Сделать основной» без сдвига «Свободно». «Свободно» — {@code GET /pocket}, как на экране.
 *
 * <p>Пользователь MockMvc по умолчанию — синтетический владелец с сидом «Основная карта» (V20) и «Наличными» (V38).
 * Второй человек заводится согласием, как после входа. {@code jdbc} — роль владельца таблиц, мимо RLS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BalanceSheetIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired OwnerService owners;

    final LocalDate today = LocalDate.now();
    final UUID me = OwnerService.SYNTHETIC_OWNER;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("DELETE FROM accounts WHERE user_id = ? AND name NOT IN ('Основная карта', 'Наличные')", me);
        jdbc.update("UPDATE accounts SET is_default = TRUE WHERE user_id = ? AND name = 'Основная карта'", me);
    }

    // ── лист ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("лист: карты с остатком по порядку — основная, вторая «на всё», накопительная, кредитка; "
            + "«по записям» у основной с записями, у прочих — обновление; карт для трат нет")
    void sheet_cardsWithBalanceInOrder() throws Exception {
        anchor(null, today.minusDays(1), "60000");
        fact(today, "1500");
        create("""
                {"name":"Кредитка","purpose":"CREDIT","creditLimit":100000,"balance":85000}""");
        create("""
                {"name":"Вклад","purpose":"SAVINGS","balance":100000}""");
        create("""
                {"name":"Бензин","purpose":"SPEND","purposeCategoryId":"%s"}""".formatted(category()));
        String wallet = create("""
                {"name":"Кошелёк","kind":"CASH","trackBalance":true}""");
        anchor(wallet, today.minusDays(2), "5000");

        JsonNode cards = json(mvc.perform(get("/api/v1/balance-checkpoints/sheet")).andExpect(status().isOk()))
                .get("cards");

        assertThat(cards.findValuesAsText("name")).containsExactly("Основная карта", "Кошелёк", "Вклад", "Кредитка");
        assertThat(cards.findValues("byRecords").stream().map(JsonNode::decimalValue).toList())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("58500"), new BigDecimal("5000"), new BigDecimal("100000"),
                        new BigDecimal("85000"));
        assertThat(cards.findValuesAsText("updatedOn")).containsExactly(
                today.minusDays(1).toString(), today.minusDays(2).toString(), today.toString(), today.toString());
        assertThat(cards.get(0).get("isDefault").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("заведение с остатком: первое обновление сегодня; ошибка обновления не оставляет карты")
    void create_writesFirstUpdate_andFailureLeavesNoCard() throws Exception {
        String deposit = create("""
                {"name":"Вклад","purpose":"SAVINGS","balance":60000}""");
        assertThat(jdbc.queryForObject("SELECT amount FROM balance_checkpoints WHERE account_id = ?::uuid AND date = ?",
                BigDecimal.class, deposit, today)).isEqualByComparingTo("60000");

        // Сумма не влезает в NUMERIC(19, 2): карта уже записана, обновление падает — откатывается всё.
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Вклад без места","purpose":"SAVINGS","balance":1e20}"""))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE name = 'Вклад без места'", Long.class))
                .isZero();
    }

    // ── пакет и предпросмотр ─────────────────────────────────────────────────

    @Test
    @DisplayName("пакет: пустое поле пропущено; «Свободно» до и после — как /pocket; разница у карты; "
            + "старые обновления на месте")
    void batch_skipsEmpty_reportsFreeAndDifference() throws Exception {
        String main = mainCard();
        anchor(null, today.minusDays(1), "60000");
        fact(today, "1500");
        String deposit = create("""
                {"name":"Вклад","purpose":"SAVINGS","balance":100000}""");
        BigDecimal before = pocket();

        JsonNode result = json(batch("batch", main, "59700", deposit, null).andExpect(status().isOk()));

        assertThat(new BigDecimal(result.get("freeBefore").asText())).isEqualByComparingTo(before);
        assertThat(new BigDecimal(result.get("freeAfter").asText())).isEqualByComparingTo(pocket())
                .isEqualByComparingTo(before.add(new BigDecimal("1200")));
        assertThat(result.get("cards")).hasSize(1);
        assertThat(result.get("cards").get(0).get("accountId").asText()).isEqualTo(main);
        assertThat(new BigDecimal(result.get("cards").get(0).get("difference").asText())).isEqualByComparingTo("1200");
        assertThat(checkpoints(deposit)).as("пустое поле — карта не обновилась").containsExactly("100000.00");
        assertThat(checkpoints(main)).as("прежнее обновление осталось").containsExactlyInAnyOrder("60000.00", "59700.00");
    }

    @Test
    @DisplayName("пакет: ошибка в одном числе — не записано ни одного; карта дважды — 400")
    void batch_oneBadEntry_writesNothing() throws Exception {
        String main = mainCard();
        anchor(null, today.minusDays(1), "60000");
        String petrol = create("""
                {"name":"Бензин","purpose":"SPEND","purposeCategoryId":"%s"}""".formatted(category()));

        batch("batch", main, "59700", petrol, "100").andExpect(status().isBadRequest());
        batch("batch", main, "59700", main, "59800").andExpect(status().isBadRequest());

        assertThat(checkpoints(main)).containsExactly("60000.00");
    }

    @Test
    @DisplayName("предпросмотр ничего не пишет и равен итогу записи")
    void preview_equalsBatch_writesNothing() throws Exception {
        String main = mainCard();
        anchor(null, today.minusDays(1), "60000");
        fact(today, "1500");
        String deposit = create("""
                {"name":"Вклад","purpose":"SAVINGS","balance":100000}""");
        BigDecimal before = pocket();

        JsonNode preview = json(batch("preview", main, "59700", deposit, "100300").andExpect(status().isOk()));
        assertThat(checkpoints(main)).as("предпросмотр не пишет").containsExactly("60000.00");
        assertThat(checkpoints(deposit)).containsExactly("100000.00");
        assertThat(pocket()).isEqualByComparingTo(before);

        JsonNode written = json(batch("batch", main, "59700", deposit, "100300").andExpect(status().isOk()));
        assertThat(preview).isEqualTo(written);
        assertThat(new BigDecimal(preview.get("freeAfter").asText())).isEqualByComparingTo(pocket());
        assertThat(new BigDecimal(written.get("cards").get(1).get("difference").asText()))
                .as("рост вклада сверх обновления").isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("чужие карты: в листе их нет, обновить нельзя — 404, ничего не записано")
    void foreignCards_notVisible() throws Exception {
        String deposit = create("""
                {"name":"Вклад","purpose":"SAVINGS","balance":100000}""");
        owners.resolve("sheet-other", Consent.current(Clock.systemUTC()));

        JsonNode cards = json(mvc.perform(get("/api/v1/balance-checkpoints/sheet").with(other()))
                .andExpect(status().isOk())).get("cards");
        assertThat(cards.findValuesAsText("accountId")).doesNotContain(deposit, mainCard());

        mvc.perform(post("/api/v1/balance-checkpoints/batch").with(other()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"accountId\":\"%s\",\"amount\":1}]}".formatted(deposit)))
                .andExpect(status().isNotFound());
        assertThat(checkpoints(deposit)).containsExactly("100000.00");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    private ResultActions batch(String path, String first, String firstAmount, String second, String secondAmount)
            throws Exception {
        return mvc.perform(post("/api/v1/balance-checkpoints/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"entries":[{"accountId":"%s","amount":%s},{"accountId":"%s","amount":%s}]}"""
                        .formatted(first, firstAmount, second, secondAmount)));
    }

    private String create(String body) throws Exception {
        return json(mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private void anchor(String account, LocalDate date, String amount) throws Exception {
        mvc.perform(post("/api/v1/balance-checkpoints").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"%s\",\"amount\":%s%s}".formatted(date, amount,
                                account != null ? ",\"accountId\":\"" + account + "\"" : "")))
                .andExpect(status().isCreated());
    }

    private void fact(LocalDate date, String amount) throws Exception {
        mvc.perform(post("/api/v1/events/facts").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"date":"%s","categoryId":"%s","type":"EXPENSE","factAmount":%s,
                                 "priority":"MEDIUM","description":"трата"}""".formatted(date, category(), amount)))
                .andExpect(status().is2xxSuccessful());
    }

    private String category() {
        return jdbc.queryForObject("SELECT id::text FROM categories WHERE user_id = ? AND name = 'Наличные'",
                String.class, me);
    }

    private String mainCard() {
        return jdbc.queryForObject("SELECT id::text FROM accounts WHERE user_id = ? AND is_default AND NOT is_deleted",
                String.class, me);
    }

    private List<String> checkpoints(String account) {
        return jdbc.queryForList("SELECT amount::text FROM balance_checkpoints WHERE account_id = ?::uuid",
                String.class, account);
    }

    private BigDecimal pocket() throws Exception {
        return new BigDecimal(json(mvc.perform(get("/api/v1/pocket")).andExpect(status().isOk())).get("pocket").asText());
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor other() {
        return oidcLogin().idToken(t -> t.subject("sheet-other"));
    }
}
