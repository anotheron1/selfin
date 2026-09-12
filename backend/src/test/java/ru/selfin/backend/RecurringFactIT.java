package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-91. Факт на событие, порождённое повторяющимся правилом, обязан записываться.
 *
 * <p><b>Почему именно интеграционный.</b> Дефект жил в уникальном индексе
 * {@code uq_events_rule_date_active} (V16), то есть в базе, а не в коде. Существующий мок-тест
 * {@code FinancialEventServiceTest.createLinkedFact_*} был ЗЕЛЁНЫМ при полностью сломанном
 * поведении: на моках базы нет, индекса нет, разбиваться не обо что. Он не просто пропустил
 * дефект — он зафиксировал наследование правила как требование. Здесь база настоящая.
 *
 * <p>План правки: {@code docs/superpowers/plans/2026-09-12-recurring-fact-rule.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class RecurringFactIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("факт на порождённое правилом событие записывается — было 500")
    void factOnRecurringPlan_succeeds() throws Exception {
        Recurring r = createMonthlyRule("Ипотека");

        // Ровно тот запрос, что падал: дата факта совпадает с датой планового платежа.
        mockMvc.perform(post("/api/v1/events/{planId}/facts", r.planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factBody(r.planDate, 23598)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("у записанного факта нет ссылки на рецепт, но есть ссылка на родителя")
    void factOnRecurringPlan_carriesNoRuleButKeepsParent() throws Exception {
        Recurring r = createMonthlyRule("Подписка");

        String resp = mockMvc.perform(post("/api/v1/events/{planId}/facts", r.planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factBody(r.planDate, 500)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String factId = objectMapper.readTree(resp).get("id").asText();

        assertThat(ruleIdOf(factId))
                .as("recurring_rule_id — поле плана, факту оно не принадлежит")
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT parent_event_id::text FROM financial_events WHERE id = ?::uuid",
                String.class, factId))
                .as("связь с рецептом остаётся через родителя")
                .isEqualTo(r.planId);
    }

    @Test
    @DisplayName("два частичных платежа на один повторяющийся план проходят оба")
    void twoFactsOnOneRecurringPlan_bothSucceed() throws Exception {
        Recurring r = createMonthlyRule("Коммуналка");

        // Мультифактовость заложена дизайном: в репозитории есть COUNT+SUM фактов
        // GROUP BY parentEventId. Широкий индекс её ломал — второй факт садился на тот же
        // ключ (rule_id, date), что и первый.
        mockMvc.perform(post("/api/v1/events/{planId}/facts", r.planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factBody(r.planDate, 3000)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/events/{planId}/facts", r.planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factBody(r.planDate, 2000)))
                .andExpect(status().isOk());

        Integer facts = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE parent_event_id = ?::uuid"
                        + " AND is_deleted = false", Integer.class, r.planId);
        assertThat(facts).isEqualTo(2);
    }

    @Test
    @DisplayName("V23 обнуляет правило у существующих фактов и не трогает планы")
    void v23_clearsRuleOnFactsOnly() throws Exception {
        Recurring r = createMonthlyRule("Аренда");

        // Воссоздаём строку, которая могла записаться ДО починки: факт со ссылкой на рецепт.
        // Такое проходило вставку, когда дата факта не совпадала с датой планового платежа,
        // и потом тихо участвовало в состоянии правила.
        String legacyFactId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, type, event_kind, status, fact_amount, category_id, description,
                     priority, is_deleted, recurring_rule_id, idempotency_key, created_at, updated_at)
                SELECT ?::uuid, date + 3, type, 'FACT', 'EXECUTED', 1000, category_id,
                       'legacy fact with rule', priority, false, recurring_rule_id,
                       gen_random_uuid(), now(), now()
                  FROM financial_events WHERE id = ?::uuid
                """, legacyFactId, r.planId);
        assertThat(ruleIdOf(legacyFactId)).as("предусловие: старая строка несёт рецепт").isNotNull();

        applyMigrationV23();

        assertThat(ruleIdOf(legacyFactId)).as("V23 обязана вычистить рецепт у факта").isNull();
        assertThat(ruleIdOf(r.planId)).as("и обязана НЕ трогать планы").isNotNull();
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    private record Recurring(String ruleId, String planId, String planDate) {}

    /** Заводит ежемесячное правило и возвращает первое порождённое им плановое событие. */
    private Recurring createMonthlyRule(String description) throws Exception {
        String catId = firstCategoryId();
        LocalDate start = LocalDate.now().plusDays(1);   // I3: правило заводится в будущее
        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 23598,
              "priority": "HIGH",
              "description": "%s",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(start, catId, description, start.getDayOfMonth(), start,
                          start.plusMonths(6));

        String resp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recurringRuleId").exists())
                .andReturn().getResponse().getContentAsString();

        var node = objectMapper.readTree(resp);
        return new Recurring(node.get("recurringRuleId").asText(),
                             node.get("id").asText(),
                             node.get("date").asText());
    }

    private static String factBody(String date, long amount) {
        return """
            {"date": "%s", "factAmount": %d, "description": "оплачено"}
            """.formatted(date, amount);
    }

    private String ruleIdOf(String eventId) {
        return jdbc.queryForObject(
                "SELECT recurring_rule_id::text FROM financial_events WHERE id = ?::uuid",
                String.class, eventId);
    }

    /** Исполняет настоящий файл V23 с classpath — правка миграции меняет исход теста. */
    private void applyMigrationV23() {
        String sql;
        try (var in = new org.springframework.core.io.ClassPathResource(
                "db/migration/V23__clear_rule_on_facts.sql").getInputStream()) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V23 не читается с classpath", e);
        }
        String stripped = String.join("\n", sql.lines()
                .filter(l -> !l.stripLeading().startsWith("--")).toList());
        for (String stmt : stripped.split(";")) {
            if (!stmt.isBlank()) jdbc.execute(stmt.trim());
        }
    }

    @SuppressWarnings("unchecked")
    private String firstCategoryId() throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var list = objectMapper.readValue(body, List.class);
        return (String) ((Map<String, Object>) list.get(0)).get("id");
    }
}
