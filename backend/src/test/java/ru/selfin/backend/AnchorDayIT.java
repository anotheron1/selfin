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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-82 и ANO-125: день якоря. Что считается остатком, когда сверка и ввод пришлись на
 * один день.
 *
 * <p>Проверяется ЗАКОН СОХРАНЕНИЯ и граница правила, а не отдельные методы. Утверждение о
 * системе ловит дефект независимо от того, в скольких местах записано правило — до этой
 * починки оно было записано трижды, и все три копии были обязаны меняться синхронно.
 *
 * <p>Третий тест — про защиту ANO-28, и он здесь важнее первых двух: сдвинуть границу так,
 * чтобы перестали пропадать записанные факты, легко; трудно не задвоить при этом те, что
 * банк уже посчитал.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-13-anchor-day-design.md}.
 * План: {@code docs/superpowers/plans/2026-09-13-anchor-day.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AnchorDayIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    /**
     * Возвращает денежное состояние к нулю перед каждым тестом: контейнер один на класс, и без
     * уборки тесты протекают друг в друга (на этом уже спотыкались в {@link FundMoneyFlowIT}).
     * Сиды — счета и категории — не трогаем.
     */
    @BeforeEach
    void resetMoneyState() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
    }

    // ── ANO-125: перевод в копилку в день ре-якоря ───────────────────────────

    @Test
    @DisplayName("ANO-125: перевод в копилку в день ре-якоря не создаёт денег")
    void transferOnReanchorDay_conservesCapital() throws Exception {
        anchorDefaultAccountToday("60000");
        String fundId = createFund("Отпуск");
        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());

        transfer(fundId, new BigDecimal("15000")).andExpect(status().isOk());

        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("перемещение между своими деньгами не создаёт их — даже в день ре-якоря")
                .isEqualByComparingTo(liquidBefore);
    }

    // ── ANO-82: ввод после сверки ────────────────────────────────────────────

    @Test
    @DisplayName("ANO-82: факт, записанный после ре-якоря, двигает остаток")
    void factRecordedAfterReanchor_movesBalance() throws Exception {
        anchorDefaultAccountToday("60000");
        BigDecimal before = pocketBalance();

        createFactToday(new BigDecimal("5000"));

        assertThat(before.subtract(pocketBalance()))
                .as("весь день ввода не имеет права уходить в никуда")
                .isEqualByComparingTo("5000");
    }

    // ── ANO-28: защита от задвоения ──────────────────────────────────────────

    @Test
    @DisplayName("ANO-28: факт, записанный ДО ре-якоря, остаток не двигает")
    void factRecordedBeforeReanchor_leavesBalanceAlone() throws Exception {
        createFactToday(new BigDecimal("5000"));   // сначала факт
        anchorDefaultAccountToday("60000");        // потом сверка

        assertThat(factsToday())
                .as("тест бесполезен, если факт вообще не создался — проверяем, что он в базе")
                .isEqualTo(1);
        assertThat(pocketBalance())
                .as("число из банка уже содержало эту трату — защита от задвоения")
                .isEqualByComparingTo("60000");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /**
     * Ре-якорь СЕГОДНЯШНИМ днём — в отличие от {@link FundMoneyFlowIT}, где якорь ставится
     * вчерашним, чтобы не наткнуться на этот дефект. Здесь дефект и есть предмет теста,
     * поэтому день ввода и день сверки обязаны совпасть.
     *
     * <p>Идёт через HTTP, а не через jdbc: время ввода якоря должно проставить тот же код,
     * что и в бою. Тест на закон сохранения, поставивший created_at руками, проверял бы
     * собственную оснастку.
     */
    private void anchorDefaultAccountToday(String amount) throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"" + LocalDate.now() + "\", \"amount\": " + amount + "}"))
                .andExpect(status().isCreated());
    }

    /** Копилка без счёта — у неё собственный баланс, и именно он задваивал капитал в ANO-125. */
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

    private ResultActions transfer(String fundId, BigDecimal amount) throws Exception {
        return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount.toPlainString() + "}"));
    }

    /** Трата сегодняшней датой через тот же эндпоинт, которым её вводит человек. */
    private void createFactToday(BigDecimal amount) throws Exception {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE type = 'EXPENSE' AND is_deleted = false LIMIT 1",
                String.class);
        mockMvc.perform(post("/api/v1/events/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s", "categoryId": "%s", "type": "EXPENSE",
                                 "factAmount": %s, "description": "трата дня якоря"}
                                """.formatted(LocalDate.now(), categoryId, amount.toPlainString())))
                .andExpect(status().isCreated());
    }

    private int factsToday() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE is_deleted = false"
                        + " AND fact_amount IS NOT NULL AND date = CURRENT_DATE", Integer.class);
        return n == null ? 0 : n;
    }

    /** Остаток, который продукт показывает в кармашке — через весь путь: сборщик и движок. */
    private BigDecimal pocketBalance() throws Exception {
        String body = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(objectMapper.readTree(body).get("currentBalance").asText());
    }
}
