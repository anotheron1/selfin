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
import ru.selfin.backend.service.OwnerService;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Срез 4, п. 7 (ANO-263): НЗ — копилка. «Свободно» — {@code GET /pocket} и примерка {@code POST /pocket/sandbox}, как на
 * экране; НЗ правится той же ручкой, что любая цель, — {@code PUT /funds/{id}}.
 *
 * <p>Пользователь MockMvc — синтетический владелец: НЗ ему завела V40.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReserveFundIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    final UUID me = OwnerService.SYNTHETIC_OWNER;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM fund_account_links");
        jdbc.update("DELETE FROM target_funds WHERE NOT is_reserve");
        jdbc.update("UPDATE target_funds SET target_amount = 0, account_id = NULL WHERE is_reserve");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("DELETE FROM accounts WHERE user_id = ? AND name NOT IN ('Основная карта', 'Наличные')", me);
        // Вчерашним днём: перевод сегодня в остаток дня обновления не входит (ANO-15 §5).
        mvc.perform(post("/api/v1/balance-checkpoints").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"%s\",\"amount\":100000}".formatted(java.time.LocalDate.now().minusDays(1))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("НЗ — первой целью, с признаком; новая цель — после него")
    void reserve_firstInGoals() throws Exception {
        mvc.perform(post("/api/v1/funds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Отпуск\",\"targetAmount\":50000}")).andExpect(status().isOk());

        JsonNode funds = json(mvc.perform(get("/api/v1/funds"))).get("funds");
        assertThat(funds.get(0).get("name").asText()).isEqualTo("НЗ");
        assertThat(funds.get(0).get("reserve").asBoolean()).isTrue();
        assertThat(funds.get(1).get("name").asText()).isEqualTo("Отпуск");
        assertThat(funds.get(1).get("reserve").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("НЗ на основной: «Свободно» меньше на «сколько держать» — и в примерке то же число")
    void reserveOnCard_lowersFree_sandboxToo() throws Exception {
        BigDecimal free = pocket().get("pocket").decimalValue();

        edit("30000", null).andExpect(status().isOk());

        JsonNode after = pocket();
        assertThat(after.get("pocket").decimalValue()).isEqualByComparingTo(free.subtract(new BigDecimal("30000")));
        assertThat(after.get("buffer").decimalValue()).isEqualByComparingTo("30000");
        JsonNode sandbox = json(mvc.perform(post("/api/v1/pocket/sandbox").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tryOn\":[],\"exclude\":[]}")));
        assertThat(sandbox.get("baseline").get("buffer").decimalValue()).isEqualByComparingTo("30000");
        assertThat(sandbox.get("fitted").get("buffer").decimalValue()).isEqualByComparingTo("30000");
        assertThat(sandbox.get("fitted").get("pocket").decimalValue()).isEqualByComparingTo(after.get("pocket").decimalValue());
    }

    @Test
    @DisplayName("НЗ на вкладе: с карт не вычитается — «Свободно» прежнее; НЗ — остаток вклада")
    void reserveOnDeposit_isTheDepositBalance() throws Exception {
        BigDecimal free = pocket().get("pocket").decimalValue();
        edit("30000", null).andExpect(status().isOk());
        String deposit = deposit("60000");

        edit("30000", deposit).andExpect(status().isOk());

        assertThat(pocket().get("pocket").decimalValue()).as("Свободно больше на 30 000 — как до НЗ").isEqualByComparingTo(free);
        assertThat(pocket().get("buffer").decimalValue()).isEqualByComparingTo("0");
        assertThat(reserve().get("currentBalance").decimalValue()).isEqualByComparingTo("60000");
    }

    @Test
    @DisplayName("перевод в НЗ на основной — 400, движения нет; НЗ на вкладе принимает перевод, как любая копилка")
    void transfer_refusedOnCard_takenOnDeposit() throws Exception {
        String id = reserve().get("id").asText();
        transfer(id, "1000").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fund_transactions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_events WHERE type = 'FUND_TRANSFER'", Integer.class))
                .isZero();

        edit("0", deposit("60000")).andExpect(status().isOk());
        transfer(id, "1000").andExpect(status().isOk());
        assertThat(reserve().get("currentBalance").decimalValue()).isEqualByComparingTo("61000");
    }

    @Test
    @DisplayName("НЗ не удаляется — 400 с ответом про деньги и без него; к основной не привязывается — 400")
    void reserve_notDeleted_notOnMainAccount() throws Exception {
        String id = reserve().get("id").asText();
        mvc.perform(delete("/api/v1/funds/{id}", id)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/funds/{id}?money=RETURN", id)).andExpect(status().isBadRequest());
        assertThat(reserve().get("id").asText()).isEqualTo(id);

        String main = jdbc.queryForObject("SELECT id::text FROM accounts WHERE user_id = ? AND is_default", String.class, me);
        edit("30000", main).andExpect(status().isBadRequest());
        assertThat(reserve().get("accountId").isNull()).isTrue();
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode pocket() throws Exception {
        return json(mvc.perform(get("/api/v1/pocket")));
    }

    private JsonNode reserve() throws Exception {
        for (JsonNode f : json(mvc.perform(get("/api/v1/funds"))).get("funds")) {
            if (f.get("reserve").asBoolean()) return f;
        }
        throw new AssertionError("нет НЗ");
    }

    /** Лист НЗ: «Сколько держать» и «Где лежит»; {@code accountId == null} — на основной карте. */
    private ResultActions edit(String keep, String accountId) throws Exception {
        return mvc.perform(put("/api/v1/funds/{id}", reserve().get("id").asText()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"НЗ\",\"targetAmount\":%s%s}".formatted(keep,
                        accountId != null ? ",\"accountId\":\"" + accountId + "\"" : "")));
    }

    private String deposit(String balance) throws Exception {
        return om.readTree(mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Вклад\",\"purpose\":\"SAVINGS\",\"balance\":%s}".formatted(balance)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private ResultActions transfer(String fundId, String amount) throws Exception {
        return mvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":%s,\"confirm\":true}".formatted(amount)));
    }
}
