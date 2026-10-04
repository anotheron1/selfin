package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.service.CreditPaymentService;
import ru.selfin.backend.service.RecurringRuleService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Р2-Б (ANO-40, ANO-190; спека {@code 2026-10-05-credit-fix-payments-design.md}): фиксация кредита
 * ставит график платежей бронью на ту же копилку; отказ от кредита его снимает; ядро держит платежи
 * графика по ссылке копилки-кредита — «Что с капиталом» и примерка считают платёж один раз.
 *
 * <p>Сценарий ANO-190: 120 000 под 12% на 12 месяцев, покупка — 10-е следующего месяца; платёж
 * 10 661,85, первый — через месяц после покупки.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CreditPaymentsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final BigDecimal PMT = new BigDecimal("10661.85");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;
    @Autowired FinancialEventRepository eventRepository;
    @Autowired TargetFundRepository fundRepository;
    @Autowired RecurringRuleRepository ruleRepository;
    @Autowired RecurringRuleService recurringRuleService;
    @Autowired CreditPaymentService creditPayments;
    @Autowired ru.selfin.backend.repository.CategoryRepository categoryRepository;

    private final LocalDate today = LocalDate.now();
    private final LocalDate purchase = today.plusMonths(1).withDayOfMonth(10);

    @AfterEach
    void cleanDb() {
        eventRepository.deleteAll(eventRepository.findAll().stream()
                .filter(e -> e.getEventKind() == EventKind.FACT).toList());
        eventRepository.deleteAll();
        ruleRepository.deleteAll();
        fundRepository.deleteAll();
    }

    private TargetFund credit(WishlistStatus status, LocalDate purchaseDate) {
        return fundRepository.save(TargetFund.builder()
                .name("Проверка кредита").purchaseType(FundPurchaseType.CREDIT).status(FundStatus.FUNDING)
                .wishlistStatus(status).targetAmount(new BigDecimal("120000")).targetDate(purchaseDate)
                .creditRate(new BigDecimal("12")).creditTermMonths(12).build());
    }

    private void fixInSandbox(UUID id) throws Exception {
        mockMvc.perform(post("/api/v1/wishlist/items/" + id + "/fix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"CREDIT","amount":120000,"date":"%s","stretchMonths":0,
                                 "creditRate":12,"creditTermMonths":12}""".formatted(purchase)))
                .andExpect(status().isOk());
    }

    private void setStatus(UUID id, String body) throws Exception {
        mockMvc.perform(patch("/api/v1/funds/" + id + "/wishlist-status")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful());
    }

    /** Живые плановые события со ссылкой на копилку — платежи графика. */
    private List<FinancialEvent> livePayments(UUID fundId) {
        return eventRepository.findAll().stream()
                .filter(e -> !e.isDeleted() && e.getEventKind() == EventKind.PLAN
                        && fundId.equals(e.getTargetFundId()))
                .sorted(Comparator.comparing(FinancialEvent::getDate))
                .toList();
    }

    private List<FinancialEvent> livePlannedOfRule(UUID ruleId) {
        return eventRepository.findAll().stream()
                .filter(e -> !e.isDeleted() && e.getStatus() == EventStatus.PLANNED
                        && e.getRecurringRule() != null && ruleId.equals(e.getRecurringRule().getId()))
                .toList();
    }

    @Test
    @DisplayName("Р2-Б (ANO-40): «Зафиксировать» в примерке ставит график бронью на ту же копилку — копий нет")
    void sandboxFix_schedulesPaymentsOnSameFund() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);

        fixInSandbox(c.getId());

        assertThat(fundRepository.count()).as("новая копилка не заводится").isEqualTo(1);
        TargetFund after = fundRepository.findById(c.getId()).orElseThrow();
        assertThat(after.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(after.getConvertedToFundId()).isNull();
        List<FinancialEvent> payments = livePayments(c.getId());
        assertThat(payments).hasSize(12);
        assertThat(payments).allSatisfy(e -> {
            assertThat(e.getPriority()).as("платёж по кредиту — бронь").isEqualTo(Priority.HIGH);
            assertThat(e.getPlannedAmount()).isEqualByComparingTo(PMT);
        });
        assertThat(payments.get(0).getDate()).isEqualTo(purchase.plusMonths(1));
    }

    @Test
    @DisplayName("Р2-Б: диалог «Кредит» с графиком — то же, на месте: ссылки конверсии нет, копий нет")
    void dialogCredit_schedulesInPlace() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);

        mockMvc.perform(post("/api/v1/wishlist/items/" + c.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"CREDIT","target":"FUND_WITH_CREDIT","createRecurringPayments":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("FIXED"))
                .andExpect(jsonPath("$.recurringRuleId").isNotEmpty());

        assertThat(fundRepository.count()).isEqualTo(1);
        TargetFund after = fundRepository.findById(c.getId()).orElseThrow();
        assertThat(after.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(after.getConvertedToFundId()).isNull();
        assertThat(livePayments(c.getId())).hasSize(12);
    }

    @Test
    @DisplayName("Р2-Б: диалог «Кредит» без галочки «Создать график платежей» — фиксация без графика")
    void dialogCredit_withoutPayments_noSchedule() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);

        mockMvc.perform(post("/api/v1/wishlist/items/" + c.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"CREDIT","target":"FUND_WITH_CREDIT","createRecurringPayments":false}"""))
                .andExpect(status().isOk());

        assertThat(fundRepository.count()).isEqualTo(1);
        assertThat(fundRepository.findById(c.getId()).orElseThrow().getWishlistStatus())
                .isEqualTo(WishlistStatus.FIXED);
        assertThat(livePayments(c.getId())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OPEN", "DISMISSED"})
    @DisplayName("Р2-Б: «Вернуть в обсуждение» и «Отложить» снимают будущие платежи, прошедший остаётся")
    void leavingFixed_unschedulesFuturePayments_keepsPast(String newStatus) throws Exception {
        // Покупка сегодня: серия начинается не раньше сегодня (I3), а старая формула платежа давала
        // здесь ноль — месяц покупки «до горизонта» (computeCreditDelta, индекс −1).
        TargetFund c = credit(WishlistStatus.FIXED, today);
        creditPayments.schedule(c);
        List<FinancialEvent> before = livePayments(c.getId());
        assertThat(before).hasSize(12).allSatisfy(e -> assertThat(e.getPlannedAmount())
                .as("платёж не зависит от месяца покупки").isEqualByComparingTo(PMT));
        // Время прошло: правило началось месяц назад, первый платёж — вчера, без факта. Прошедшая бронь —
        // история, снятие её не трогает. Старт правила — до этого платежа, как в жизни: иначе платёж
        // лежал бы вне серии, и «эту и следующие» не тронула бы его при любой дате отсечки.
        FinancialEvent first = before.get(0);
        RecurringRule rule = ruleRepository.findById(first.getRecurringRule().getId()).orElseThrow();
        rule.setStartDate(today.minusMonths(1));
        ruleRepository.save(rule);
        first.setDate(today.minusDays(1));
        eventRepository.save(first);

        setStatus(c.getId(), "{\"status\":\"" + newStatus + "\"}");

        List<FinancialEvent> after = livePayments(c.getId());
        assertThat(after).as("будущих платежей нет, прошедший остался").hasSize(1);
        assertThat(after.get(0).getDate()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("Р2-Б: удаление копилки-кредита снимает её будущие платежи")
    void deleteCredit_unschedulesFuturePayments() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);
        fixInSandbox(c.getId());
        assertThat(livePayments(c.getId())).hasSize(12);

        mockMvc.perform(delete("/api/v1/funds/" + c.getId()).param("money", "RETURN"))
                .andExpect(status().is2xxSuccessful());

        assertThat(livePayments(c.getId())).isEmpty();
    }

    @Test
    @DisplayName("Р2-Б: график опознаётся и после правки правила — перегенерированные платежи ссылки не несут")
    void editedRule_stillRecognized() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);
        fixInSandbox(c.getId());
        List<FinancialEvent> payments = livePayments(c.getId());
        RecurringRule rule = payments.get(0).getRecurringRule();
        // С первого платежа: ссылку на копилку помнят только удалённые события.
        recurringRuleService.regenerate(rule, payments.get(0).getDate());
        assertThat(livePlannedOfRule(rule.getId()))
                .as("предусловие: живые платежи — все без ссылки на копилку")
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getTargetFundId()).isNull());

        setStatus(c.getId(), "{\"status\":\"OPEN\"}");

        assertThat(livePlannedOfRule(rule.getId())).isEmpty();
    }

    @Test
    @DisplayName("Р2-Б: повторная фиксация не задваивает график")
    void refix_keepsOneSchedule() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);
        fixInSandbox(c.getId());
        setStatus(c.getId(), "{\"status\":\"OPEN\"}");
        fixInSandbox(c.getId());
        assertThat(livePayments(c.getId())).hasSize(12);

        creditPayments.schedule(fundRepository.findById(c.getId()).orElseThrow());

        assertThat(livePayments(c.getId())).as("стоявший график снят перед новым").hasSize(12);
    }

    @Test
    @DisplayName("Р2-Б: «удалить созданное» у старой копии из конверсии снимает и её платежи")
    void oldCopy_deleteCreated_unschedulesItsPayments() throws Exception {
        TargetFund copy = credit(WishlistStatus.FIXED, purchase);
        creditPayments.schedule(copy);
        TargetFund source = credit(WishlistStatus.FIXED, purchase);
        source.setConvertedToFundId(copy.getId());
        fundRepository.save(source);

        setStatus(source.getId(), "{\"status\":\"OPEN\",\"deleteArtifact\":true}");

        assertThat(fundRepository.findById(copy.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(livePayments(copy.getId())).isEmpty();
    }

    @Test
    @DisplayName("Ревью Codex на #138: серия со ссылкой на копилку-накопление — не график кредита, её не снимают")
    void savingsFundLinkedRule_isNotACreditSchedule() throws Exception {
        // Через экран так не завести — копилку журнал ставит только переводу, а переводов правилом не
        // бывает (V16). Через API — можно: журнал передаёт targetFundId в правило при любом типе.
        TargetFund savings = fundRepository.save(TargetFund.builder()
                .name("Отпуск").purchaseType(FundPurchaseType.SAVINGS).status(FundStatus.FUNDING)
                .wishlistStatus(WishlistStatus.FIXED).targetAmount(new BigDecimal("100000"))
                .targetDate(purchase.plusMonths(6)).build());
        var cfg = new ru.selfin.backend.dto.RecurringConfigDto(ru.selfin.backend.model.enums.RecurringFrequency.MONTHLY,
                10, null, purchase, purchase.plusMonths(5));
        var sport = categoryRepository.save(ru.selfin.backend.model.Category.builder()
                .name("Спорт " + UUID.randomUUID()).type(ru.selfin.backend.model.enums.CategoryType.EXPENSE).build());
        UUID ruleId = recurringRuleService.createFromDto(sport, ru.selfin.backend.model.enums.EventType.EXPENSE,
                new BigDecimal("5000"), Priority.MEDIUM, "Абонемент", savings.getId(), null, cfg).rule().getId();
        int before = livePlannedOfRule(ruleId).size();

        setStatus(savings.getId(), "{\"status\":\"OPEN\"}");
        mockMvc.perform(delete("/api/v1/funds/" + savings.getId()).param("money", "RETURN"))
                .andExpect(status().is2xxSuccessful());

        assertThat(livePlannedOfRule(ruleId)).as("чужая серия цела").hasSize(before);
    }

    @Test
    @DisplayName("ANO-190: «Что с капиталом» при открытии равен «Стратегии» — платёж кредита один раз, без «+сумма на счёт»")
    void capitalBlock_equalsStrategy_forScheduledCredit() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);
        fixInSandbox(c.getId());

        JsonNode sim = om.readTree(mockMvc.perform(get("/api/v1/wishlist/simulation").param("horizonMonths", "12"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode strategy = om.readTree(mockMvc.perform(get("/api/v1/strategy/timeline").param("horizonMonths", "12"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        Map<String, Double> core = new HashMap<>();
        strategy.get("points").forEach(p -> core.put(p.get("yearMonth").asText(), p.get("balance").asDouble()));
        // Как composeTimeline на фронте: дельты включённых (зафиксированных) — на точки FUTURE, индекс 0 — следующий месяц.
        List<JsonNode> future = new java.util.ArrayList<>();
        sim.get("baseline").get("points").forEach(p -> { if ("FUTURE".equals(p.get("phase").asText())) future.add(p); });
        double[] cum = new double[future.size()];
        JsonNode item = null;
        for (JsonNode it : sim.get("items")) {
            if (!"FIXED".equals(it.get("status").asText())) continue;
            if (it.get("id").asText().equals(c.getId().toString())) item = it;
            for (JsonNode d : it.get("delta")) {
                for (int i = d.get("monthIndex").asInt(); i < cum.length; i++) cum[i] += d.get("accountDelta").asDouble();
            }
        }
        assertThat(item).as("кредит — в блоке").isNotNull();
        int checked = 0;
        for (int i = 0; i < future.size(); i++) {
            String ym = future.get(i).get("yearMonth").asText();
            if (!core.containsKey(ym)) continue;
            assertThat(future.get(i).get("balance").asDouble() + cum[i]).as(ym).isCloseTo(core.get(ym), within(0.01));
            checked++;
        }
        assertThat(checked).as("сверены месяцы покупки и платежей").isGreaterThanOrEqualTo(3);
        assertThat(item.get("delta")).as("по оси счёта — только платежи, без «+сумма на счёт»")
                .allSatisfy(d -> assertThat(d.get("accountDelta").asDouble()).isLessThanOrEqualTo(0.0));
        assertThat(item.get("monthlyContribution").isNull()).as("у кредита взноса нет — удержанное это платёж").isTrue();
    }

    @Test
    @DisplayName("ANO-190: примерка — кредит с графиком «в основе», исключить его можно")
    void sandbox_scheduledCreditInBaseline() throws Exception {
        TargetFund c = credit(WishlistStatus.OPEN, purchase);
        fixInSandbox(c.getId());

        JsonNode resp = om.readTree(mockMvc.perform(post("/api/v1/pocket/sandbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"MONTHS:6","tryOn":[],"exclude":[{"type":"FUND","id":"%s"}]}"""
                                .formatted(c.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode item = null;
        for (JsonNode it : resp.get("items")) {
            if (Objects.equals(it.get("ref").get("id").asText(), c.getId().toString())) item = it;
        }
        assertThat(item).isNotNull();
        assertThat(item.get("inBaseline").asBoolean()).isTrue();
    }
}
