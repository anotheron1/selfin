package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * IT кармашка: контракт GET /pocket, настройки буфера,
 * сценарий ANO-6 (ввод немедленно меняет ответ), «факт вытесняет план».
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PocketControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FinancialEventRepository eventRepo;
    @Autowired RecurringRuleRepository ruleRepo;
    @Autowired TargetFundRepository fundRepo;
    @Autowired PlatformTransactionManager txManager;

    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");

    private BigDecimal pocket() throws Exception {
        String body = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(objectMapper.readTree(body).get("pocket").asText());
    }

    // ── хелперы для SECOND_INCOME / recurring сценариев (ANO-14) ────────────

    private String createCategory(String name, String type) throws Exception {
        String body = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","type":"%s"}
                                """.formatted(name, type)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createEvent(String json) throws Exception {
        String body = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createIncome(String categoryId, LocalDate date, long amount, String desc) throws Exception {
        return createEvent("""
                {"date":"%s","categoryId":"%s","type":"INCOME",
                 "plannedAmount":%d,"priority":"MEDIUM","description":"%s"}
                """.formatted(date, categoryId, amount, desc));
    }

    private void deleteEvent(String id) throws Exception {
        mockMvc.perform(delete("/api/v1/events/" + id)).andExpect(status().isNoContent());
    }

    private JsonNode getPocket(String scope) throws Exception {
        String body = mockMvc.perform(get("/api/v1/pocket" + (scope != null ? "?scope=" + scope : "")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> work.get());
    }

    private UUID ruleIdOf(String eventId) {
        return inTx(() -> eventRepo.findById(UUID.fromString(eventId))
                .orElseThrow().getRecurringRule().getId());
    }

    /** Симуляция нематериализованного хвоста + вывод правила из активных при уборке. */
    private void trimRuleEvents(UUID ruleId, LocalDate fromDate) {
        inTx(() -> eventRepo.softDeletePlanEventsByRuleFromDate(ruleId, fromDate));
    }

    private void retireRule(UUID ruleId, LocalDate startDate) {
        inTx(() -> {
            eventRepo.softDeletePlanEventsByRuleFromDate(ruleId, startDate);
            ruleRepo.findById(ruleId).ifPresent(r -> {
                // endDate = startDate (не start−1): chk_end_after_start требует end >= start.
                // deleted=true сам по себе выводит правило из findIndefiniteActiveIds.
                r.setDeleted(true);
                r.setEndDate(startDate);
                ruleRepo.save(r);
            });
            return null;
        });
    }

    // ── SECOND_INCOME e2e (ANO-14 §4) ────────────────────────────────────────

    @Test
    void secondIncome_twoIncomes_horizonAtSecondInclusive() throws Exception {
        String cat = createCategory("IT-sec-inc-a", "INCOME");
        LocalDate d1 = LocalDate.now().plusDays(10);
        LocalDate d2 = LocalDate.now().plusDays(40);
        String e1 = createIncome(cat, d1, 1_000, "IT income 1");
        String e2 = createIncome(cat, d2, 2_000, "IT income 2");
        try {
            JsonNode r = getPocket("SECOND_INCOME");
            assertThat(r.get("horizon").get("type").asText()).isEqualTo("SECOND_INCOME");
            assertThat(r.get("horizon").get("endDate").asText()).isEqualTo(d2.toString());
            assertThat(r.get("horizon").get("fallback").asBoolean()).isFalse();
            assertThat(r.get("horizon").get("label").asText())
                    .isEqualTo("до 2-го дохода " + DD_MM.format(d2));
            JsonNode traj = r.get("trajectory");
            assertThat(traj.get(traj.size() - 1).get("date").asText())
                    .isEqualTo(d2.toString()); // день 2-го дохода включён в траекторию
        } finally {
            deleteEvent(e1);
            deleteEvent(e2);
        }
    }

    @Test
    void secondIncome_sameDayIncomes_countAsOneDate() throws Exception {
        String cat = createCategory("IT-sec-inc-b", "INCOME");
        LocalDate d1 = LocalDate.now().plusDays(12);
        LocalDate d2 = LocalDate.now().plusDays(45);
        String e1 = createIncome(cat, d1, 1_000, "IT аванс");
        String e2 = createIncome(cat, d1, 500, "IT кэшбек в тот же день");
        String e3 = createIncome(cat, d2, 2_000, "IT зп");
        try {
            JsonNode r = getPocket("SECOND_INCOME");
            // Две суммы в один день = ОДНА дата; вторая различная дата — d2
            assertThat(r.get("horizon").get("endDate").asText()).isEqualTo(d2.toString());
            assertThat(r.get("horizon").get("fallback").asBoolean()).isFalse();
        } finally {
            deleteEvent(e1);
            deleteEvent(e2);
            deleteEvent(e3);
        }
    }

    @Test
    void secondIncome_onlyOneIncome_truthfulFallbackCoveringIt() throws Exception {
        String cat = createCategory("IT-sec-inc-c", "INCOME");
        LocalDate d1 = LocalDate.now().plusDays(50); // дальше 30 дней
        String e1 = createIncome(cat, d1, 1_000, "IT единственный доход");
        try {
            JsonNode r = getPocket("SECOND_INCOME");
            assertThat(r.get("horizon").get("fallback").asBoolean()).isTrue();
            assertThat(r.get("horizon").get("endDate").asText()).isEqualTo(d1.toString());
            assertThat(r.get("horizon").get("label").asText())
                    .isEqualTo("до " + DD_MM.format(d1) + " (второй доход не найден)");
        } finally {
            deleteEvent(e1);
        }
    }

    @Test
    void shortHorizon_calendarTailExtendsToSevenDays() throws Exception {
        // ANO-24 §3.9: доход завтра → горизонт = завтра, но траектория тянется до asOf+7
        String cat = createCategory("IT-tail-inc", "INCOME");
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        String e1 = createIncome(cat, tomorrow, 50_000, "IT доход завтра");
        try {
            JsonNode r = getPocket(null);
            assertThat(r.get("horizon").get("endDate").asText()).isEqualTo(tomorrow.toString());
            JsonNode traj = r.get("trajectory");
            assertThat(traj.size()).isGreaterThanOrEqualTo(8);
            assertThat(traj.get(traj.size() - 1).get("date").asText())
                    .isEqualTo(LocalDate.now().plusDays(7).toString());
            // Минимум — внутри горизонта, не в хвосте
            assertThat(r.get("minPoint").get("date").asText())
                    .isLessThanOrEqualTo(tomorrow.toString());
        } finally {
            deleteEvent(e1);
        }
    }

    // ── recurring: продление до/внутри горизонта кармашка (ANO-14 §6) ───────

    @Test
    void recurringExpense_regeneratedIntoStretchedScope() throws Exception {
        String cat = createCategory("IT-recur-exp-cat", "EXPENSE");
        LocalDate first = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        String eventId = createEvent("""
                {"date":"%s","categoryId":"%s","type":"EXPENSE",
                 "plannedAmount":777,"priority":"MEDIUM","description":"IT-recur-exp",
                 "recurring":{"frequency":"MONTHLY","dayOfMonth":15,"startDate":"%s"}}
                """.formatted(first, cat, first));
        UUID ruleId = ruleIdOf(eventId);
        try {
            // Симулируем нематериализованный хвост: активные строки дальше first+5д стёрты
            trimRuleEvents(ruleId, first.plusDays(5));
            LocalDate second = first.plusMonths(1);

            JsonNode r = getPocket("MONTHS:3");
            boolean secondOccurrenceInTrajectory = false;
            for (JsonNode day : r.get("trajectory")) {
                if (day.get("date").asText().equals(second.toString())
                        && day.get("expense").decimalValue().compareTo(new BigDecimal("777")) >= 0) {
                    secondOccurrenceInTrajectory = true;
                }
            }
            assertThat(secondOccurrenceInTrajectory)
                    .as("повторение %s должно быть регенерировано продлением в /pocket", second)
                    .isTrue();
        } finally {
            retireRule(ruleId, first);
        }
    }

    @Test
    void recurringIncome_materializationAnchorsHorizon_notFallback() throws Exception {
        String cat = createCategory("IT-recur-inc-cat", "INCOME");
        LocalDate first = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        String eventId = createEvent("""
                {"date":"%s","categoryId":"%s","type":"INCOME",
                 "plannedAmount":55555,"priority":"MEDIUM","description":"IT-recur-inc",
                 "recurring":{"frequency":"MONTHLY","dayOfMonth":15,"startDate":"%s"}}
                """.formatted(first, cat, first));
        UUID ruleId = ruleIdOf(eventId);
        try {
            // Все активные строки правила стёрты — без продления NEXT_INCOME упал бы в фолбэк
            trimRuleEvents(ruleId, first);

            JsonNode r = getPocket(null);
            assertThat(r.get("horizon").get("fallback").asBoolean())
                    .as("материализация в /pocket должна заякорить горизонт")
                    .isFalse();
            assertThat(r.get("horizon").get("endDate").asText()).isEqualTo(first.toString());
        } finally {
            retireRule(ruleId, first);
        }
    }

    @Test
    void contract_defaultScope() throws Exception {
        mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pocket").exists())
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.buffer").exists())
                .andExpect(jsonPath("$.checkpointDate").hasJsonPath()) // null в этой базе — якоря нет
                .andExpect(jsonPath("$.horizon.type").exists())
                .andExpect(jsonPath("$.horizon.endDate").exists())
                .andExpect(jsonPath("$.horizon.label").exists())
                .andExpect(jsonPath("$.minPoint.date").exists())
                .andExpect(jsonPath("$.breakdown").isArray())
                .andExpect(jsonPath("$.trajectory").isArray())
                .andExpect(jsonPath("$.wishlistCandidates").isArray());
    }

    @Test
    void scopes_monthsOkGarbage400() throws Exception {
        mockMvc.perform(get("/api/v1/pocket?scope=MONTHS:3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizon.type").value("MONTHS"));
        mockMvc.perform(get("/api/v1/pocket?scope=GARBAGE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/pocket?scope=MONTHS:99"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settings_putGetValidate() throws Exception {
        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": 5000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferAmount").value(5000));

        mockMvc.perform(get("/api/v1/settings/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferAmount").value(5000));

        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": -1}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // вернуть 0, чтобы не влиять на другие тесты
        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": 0}
                                """))
                .andExpect(status().isOk());
    }

    // ── резервирование FIXED-копилок (ANO-16 §6) ────────────────────────────

    private UUID createFixedSavingsFund(String name, long target, long balance, LocalDate targetDate) {
        return inTx(() -> fundRepo.save(TargetFund.builder()
                .name(name)
                .targetAmount(BigDecimal.valueOf(target))
                .currentBalance(BigDecimal.valueOf(balance))
                .targetDate(targetDate)
                .purchaseType(FundPurchaseType.SAVINGS)
                .status(FundStatus.FUNDING)
                .wishlistStatus(WishlistStatus.FIXED)
                .build()).getId());
    }

    private void hardDeleteFund(UUID id) {
        inTx(() -> { fundRepo.deleteById(id); return null; });
    }

    private JsonNode breakdownLine(JsonNode pocket, String type) {
        for (JsonNode line : pocket.get("breakdown")) {
            if (type.equals(line.get("type").asText())) return line;
        }
        return null;
    }

    @Test
    void fixedSavingsFund_reservedInPocket() throws Exception {
        BigDecimal before = new BigDecimal(getPocket("MONTHS:3").get("pocket").asText());
        // Остаток 60 000, цель через 2 месяца → 2 взноса по 30 000, оба внутри MONTHS:3
        UUID fundId = createFixedSavingsFund("IT-Египет", 80_000, 20_000,
                LocalDate.now().plusMonths(2));
        try {
            JsonNode r = getPocket("MONTHS:3");
            JsonNode line = breakdownLine(r, "SAVINGS_CONTRIBUTIONS");
            assertThat(line).as("строка взносов в breakdown").isNotNull();
            assertThat(line.get("details").get(0).asText()).isEqualTo("IT-Египет");
            assertThat(new BigDecimal(line.get("amount").asText()))
                    .isEqualByComparingTo(new BigDecimal("-60000.00"));
            BigDecimal after = new BigDecimal(r.get("pocket").asText());
            assertThat(before.subtract(after)).isEqualByComparingTo(new BigDecimal("60000.00"));
        } finally {
            hardDeleteFund(fundId);
        }
    }

    @Test
    void fixedSavingsFund_edges_notReservedAndNoError() throws Exception {
        // Уже накоплено и протухшая цель — не резервируются, GET /pocket не падает (§6 тотален)
        UUID saved = createFixedSavingsFund("IT-накоплено", 50_000, 50_000,
                LocalDate.now().plusMonths(2));
        UUID stale = createFixedSavingsFund("IT-протухла", 50_000, 0,
                LocalDate.now().minusDays(10));
        try {
            JsonNode r = getPocket("MONTHS:3");
            assertThat(breakdownLine(r, "SAVINGS_CONTRIBUTIONS")).isNull();
        } finally {
            hardDeleteFund(saved);
            hardDeleteFund(stale);
        }
    }

    /**
     * Сценарий ANO-6: ввод плана и факта немедленно меняет ответ кармашка.
     * План HIGH-расхода на СЕГОДНЯ −5000 → кармашек падает на 5000 (день 0 траектории);
     * факт 4000 по этому плану → факт вытесняет план: кармашек = старт − 4000.
     *
     * Дата = сегодня НАМЕРЕННО: updateFact пишет factAmount в ту же PLAN-запись,
     * а факт с БУДУЩЕЙ датой не попадает ни в баланс (date > asOf), ни в траекторию
     * (factAmount != null) — известная дыра v1, залогирована в ANO-12.
     */
    @Test
    void ano6_inputImmediatelyChangesPocket() throws Exception {
        // Категория для события (оба POST в этом API возвращают 200, не 201)
        String catBody = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"IT-pocket-test","type":"EXPENSE"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String categoryId = objectMapper.readTree(catBody).get("id").asText();

        BigDecimal before = pocket();

        // План: HIGH-расход на сегодня. POST /events требует Idempotency-Key (обязательный header).
        LocalDate planDate = LocalDate.now();
        String eventBody = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"%s","categoryId":"%s","type":"EXPENSE",
                                 "plannedAmount":5000,"priority":"HIGH","description":"IT plan"}
                                """.formatted(planDate, categoryId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String planId = objectMapper.readTree(eventBody).get("id").asText();

        BigDecimal afterPlan = pocket();
        assertThat(before.subtract(afterPlan)).isEqualByComparingTo(new BigDecimal("5000"));

        // Факт 4000 по плану: PATCH /events/{id}/fact (FinancialEventUpdateFactDto)
        mockMvc.perform(patch("/api/v1/events/" + planId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"factAmount": 4000}
                                """))
                .andExpect(status().isOk());

        BigDecimal afterFact = pocket();
        // План вытеснен фактом: итоговая дельта от старта = −4000, не −9000 и не −5000
        assertThat(before.subtract(afterFact)).isEqualByComparingTo(new BigDecimal("4000"));
    }
}
