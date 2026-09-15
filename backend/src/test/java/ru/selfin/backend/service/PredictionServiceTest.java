package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;           // NOTE: EventKind is in model package, NOT model.enums
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Вклад прогноза текущего месяца в кармашек.
 *
 * <p>ANO-80 заменил механизм целиком. Было: дневной темп {@code факт / номер дня},
 * достроенный на остаток месяца, и особый случай «в категории есть план → вклад ноль».
 * Стало: норма по истории минус то, что уже стоит в пути денег.
 *
 * <p>Здесь проверяется ФОРМУЛА вклада. Медиана подменяется через spy — у неё свой класс
 * тестов ({@link PredictionServiceStatsTest}), и подсовывать сюда пять месяцев фактов
 * значило бы перепроверять её во второй раз.
 */
class PredictionServiceTest {

    /** «Сегодня» по умолчанию — 14 сентября 2026; отдельные тесты передают свою дату. */
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 9, 14).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    private FinancialEventRepository eventRepo;
    private CategoryRepository categoryRepo;
    private PredictionService service;
    private Category food;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        // spy — чтобы подменять statsForCategories в medianOf(). Внутренние вызовы
        // forecastFromEvents идут через прокси и потому перехватываются.
        service = spy(new PredictionService(eventRepo, categoryRepo, FIXED));
        food = makeCategory("Еда / Продукты");
    }

    // ── ANO-80: формула вклада ─────────────────────────────────────────────────

    @Test
    @DisplayName("ANO-80: планов нет — вклад равен норме минус потраченное")
    void forecastFromEvents_noPlans_contributesMedianMinusFact() {
        enabled(food);
        medianOf(food, "50000", 5);

        MonthlyForecastDto result = forecast(
                List.of(fact(food, LocalDate.of(2026, 9, 5), "23444")),
                LocalDate.of(2026, 9, 5));

        assertThat(result.netPredictionDelta())
                .as("дневной темп дал бы 117 220: 23 444 / 5 дней × 25 оставшихся")
                .isEqualByComparingTo("26556");
    }

    @Test
    @DisplayName("ANO-80: план не отключает категорию — отдаётся разница между нормой и планом")
    void forecastFromEvents_withPlan_contributesMedianMinusPlan() {
        enabled(food);
        medianOf(food, "50000", 5);

        MonthlyForecastDto result = forecast(
                List.of(plan(food, LocalDate.of(2026, 9, 20), "40000")),
                LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("до ANO-80 категория с планом давала ноль, сколько бы сверх него ни тратилось")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-80: план со статусом EXECUTED не вычитается — его закрыл факт-ребёнок")
    void forecastFromEvents_planClosedByChildFact_notSubtractedTwice() {
        enabled(food);
        medianOf(food, "50000", 5);

        // Форма из боя: факт заведён отдельным событием, у плана меняется только статус.
        MonthlyForecastDto result = forecast(
                List.of(closedPlanNoFactAmount(food, LocalDate.of(2026, 9, 3), "4000"),
                        fact(food, LocalDate.of(2026, 9, 3), "5000")),
                LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("вычесть и план, и заменивший его факт значит посчитать трату дважды")
                .isEqualByComparingTo("45000");
    }

    @Test
    @DisplayName("ANO-155: частично погашенный план вычитается остатком, а не полной суммой")
    void forecastFromEvents_partiallySettledPlan_subtractsOnlyRemainder() {
        enabled(food);
        medianOf(food, "50000", 5);

        // Продукты 20 000 на 20-е, чек 300 третьего. Норма обязана вычесть 19 700 —
        // ровно то, что удерживает траектория. Вычтет 20 000 — прогноз оптимистичнее
        // правды; вычтет ноль — трата посчитана дважды.
        UUID planId = UUID.randomUUID();
        MonthlyForecastDto result = forecast(
                List.of(planWithId(planId, food, LocalDate.of(2026, 9, 20), "20000"),
                        factFor(planId, food, LocalDate.of(2026, 9, 3), "300")),
                LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("50 000 − 300 потрачено − 19 700 удержано")
                .isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("ANO-80: факт, внесённый в строку плана, считается потраченным")
    void forecastFromEvents_factPatchedOntoPlanRow_countsAsSpent() {
        enabled(food);
        medianOf(food, "50000", 5);

        // Вторая форма из боя: PATCH /events/{id}/fact ставит factAmount на ту же строку
        // и переводит статус. Трата живёт на событии вида PLAN — фильтр по виду её терял,
        // и норма не вычитала уже ушедшие деньги.
        MonthlyForecastDto result = forecast(
                List.of(planPatchedWithFact(food, LocalDate.of(2026, 9, 3), "4000", "5000")),
                LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("деньги ушли: норма обязана вычесть их, а не ждать события вида FACT")
                .isEqualByComparingTo("45000");
    }

    @Test
    @DisplayName("ANO-80: просроченный план вычитается, если его удержала бронь")
    void forecastFromEvents_overduePlan_reserved_isSubtracted() {
        enabled(food);
        medianOf(food, "50000", 5);
        FinancialEvent overdue = plan(food, LocalDate.of(2026, 9, 2), "40000");

        MonthlyForecastDto result = service.forecastFromEvents(
                List.of(overdue), List.of(overdue), LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("бронь удержала эти деньги — норма обязана их учесть")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-80: просроченный план БЕЗ брони не вычитается — он не стоит в пути денег")
    void forecastFromEvents_overduePlan_notReserved_isNotSubtracted() {
        enabled(food);
        medianOf(food, "50000", 5);

        // Найдено ревью PR #43. План MEDIUM, датированный раньше сегодня и не исполненный,
        // не лежит НИГДЕ: в будущие дни движка не попадает по дате, в расход сегодняшнего
        // дня — тоже, а бронь берёт только HIGH и только после якоря. Вычесть его значило
        // бы сделать второе число оптимистичнее правды и проглотить предупреждение.
        MonthlyForecastDto result = service.forecastFromEvents(
                List.of(plan(food, LocalDate.of(2026, 9, 2), "40000")),
                List.of(), LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("деньги не удержаны нигде — норма остаётся полной")
                .isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("ANO-80: хотелка OPEN не вычитается — траектория её не держит")
    void forecastFromEvents_openWishlistPlan_isNotSubtracted() {
        enabled(food);
        medianOf(food, "50000", 5);

        MonthlyForecastDto result = forecast(
                List.of(wishlistPlan(food, LocalDate.of(2026, 9, 20), "40000")),
                LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("allowedInTrajectory отсеивает неFIXED-хотелки; норма обязана так же")
                .isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("ANO-80: окно медианы считается от переданной даты, а не от часов")
    void forecastFromEvents_statsAnchoredToRequestedDate() {
        enabled(food);
        medianOf(food, "50000", 5);

        forecast(List.of(), LocalDate.of(2026, 7, 15));

        // Найдено ревью PR #43: /analytics/forecast?date=... просит июль, а медиана
        // считалась по августу включительно — данные из будущего относительно запроса.
        org.mockito.Mockito.verify(service)
                .statsForCategories(anyList(), anyInt(), org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 7, 15)));
    }

    @Test
    @DisplayName("ANO-80: потрачено больше нормы — вклад ноль, а не отрицательное число")
    void forecastFromEvents_spentAboveMedian_contributesZero() {
        enabled(food);
        medianOf(food, "50000", 5);

        MonthlyForecastDto result = forecast(
                List.of(fact(food, LocalDate.of(2026, 9, 5), "70000")),
                LocalDate.of(2026, 9, 5));

        assertThat(result.netPredictionDelta()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-80: наблюдений меньше трёх — прогноза нет вовсе")
    void forecastFromEvents_belowThreshold_noForecast() {
        enabled(food);
        medianOf(food, "50000", 2);

        MonthlyForecastDto result = forecast(
                List.of(fact(food, LocalDate.of(2026, 9, 5), "23444")),
                LocalDate.of(2026, 9, 5));

        assertThat(result.netPredictionDelta())
                .as("это и есть симптом ANO-80: у нового пользователя оснований нет")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-80: категория без событий месяца всё равно даёт норму")
    void forecastFromEvents_categoryWithoutMonthEvents_stillContributes() {
        enabled(food);
        medianOf(food, "15851", 5);

        MonthlyForecastDto result = forecast(List.of(), LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("обход идёт по включённым категориям, а не по событиям месяца: "
                        + "дневной темп молчал, пока человек ничего не внёс")
                .isEqualByComparingTo("15851");
    }

    @Test
    @DisplayName("ANO-80: выключенная галочка — категории в расчёте нет")
    void forecastFromEvents_forecastDisabled_contributesNothing() {
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of());

        MonthlyForecastDto result = forecast(
                List.of(fact(food, LocalDate.of(2026, 9, 5), "23444")),
                LocalDate.of(2026, 9, 5));

        assertThat(result.netPredictionDelta()).isEqualByComparingTo("0");
        assertThat(result.categories()).isEmpty();
    }

    @Test
    @DisplayName("ANO-80: вклад лежит в ответе — категория с планом остаётся виновником")
    void forecastFromEvents_beyondPlan_isCarriedInTheResponse() {
        enabled(food);
        medianOf(food, "50000", 5);

        var category = forecast(
                        List.of(plan(food, LocalDate.of(2026, 9, 20), "40000")),
                        LocalDate.of(2026, 9, 14))
                .categories().get(0);

        assertThat(category.beyondPlan())
                .as("разбивка отбирала виновников по «плана нет»; теперь вклад есть и при плане")
                .isEqualByComparingTo("10000");
        assertThat(category.plannedLimit())
                .as("предпосылка теста: план у категории действительно есть")
                .isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("ANO-80: линия спарклайна — планка нормы, а не кривая от номера дня")
    void forecastFromEvents_history_isFlatUntilFactCrossesMedian() {
        enabled(food);
        medianOf(food, "50000", 5);

        List<DailyForecastPointDto> history = forecast(
                List.of(fact(food, LocalDate.of(2026, 9, 3), "60000")),
                LocalDate.of(2026, 9, 5))
                .categories().get(0).history();

        // usingComparatorForType обязателен: BigDecimal.equals различает 50000 и 50000.00,
        // а медиана приходит из percentile со своей шкалой. Сравнивать нужно значения.
        assertThat(history).extracting(DailyForecastPointDto::projectedTotal)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .as("до траты — планка нормы; после — факт, который её перерос")
                .containsExactly(new BigDecimal("50000"), new BigDecimal("50000"),
                        new BigDecimal("60000"), new BigDecimal("60000"), new BigDecimal("60000"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Расчёт без брони просрочки — случай дашборда и эндпоинта /forecast. */
    private MonthlyForecastDto forecast(List<FinancialEvent> monthEvents, LocalDate today) {
        return service.forecastFromEvents(monthEvents, List.of(), today);
    }

    private void enabled(Category... cats) {
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of(cats));
    }

    /** Хотелка со статусом OPEN: в траекторию движок её не берёт. */
    private FinancialEvent wishlistPlan(Category category, LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).category(category).type(EventType.EXPENSE)
                .date(date).plannedAmount(new BigDecimal(amount))
                .eventKind(EventKind.PLAN).status(EventStatus.PLANNED)
                .wishlistStatus(ru.selfin.backend.model.enums.WishlistStatus.OPEN)
                .priority(Priority.LOW).deleted(false)
                .build();
    }

    /**
     * Подменяет статистику категории: медиана и число месяцев наблюдения.
     *
     * <p>Подмена вешается на батч-метод, потому что расчёт ходит именно через него —
     * один поход в базу на все категории вместо двух запросов на каждую.
     */
    private void medianOf(Category c, String median, int months) {
        doReturn(Map.of(c.getId(), new CategoryMonthStats(c.getId(), months,
                new BigDecimal(median), new BigDecimal(median), new BigDecimal(median))))
                .when(service).statsForCategories(anyList(), anyInt(), org.mockito.ArgumentMatchers.any());
    }

    private FinancialEvent fact(Category category, LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).category(category).type(EventType.EXPENSE)
                .date(date).factAmount(new BigDecimal(amount))
                .eventKind(EventKind.FACT).status(EventStatus.EXECUTED).deleted(false)
                .build();
    }

    /** План с известным id — чтобы к нему можно было привязать факт (ANO-155). */
    private FinancialEvent planWithId(UUID id, Category category, LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(id).category(category).type(EventType.EXPENSE)
                .date(date).plannedAmount(new BigDecimal(amount))
                .eventKind(EventKind.PLAN).status(EventStatus.PLANNED).deleted(false)
                .build();
    }

    /** Факт, привязанный к плану: гасит его на свою сумму (ANO-155). */
    private FinancialEvent factFor(UUID planId, Category category, LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).category(category).type(EventType.EXPENSE)
                .date(date).factAmount(new BigDecimal(amount)).parentEventId(planId)
                .eventKind(EventKind.FACT).status(EventStatus.EXECUTED).deleted(false)
                .build();
    }

    private FinancialEvent plan(Category category, LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).category(category).type(EventType.EXPENSE)
                .date(date).plannedAmount(new BigDecimal(amount))
                .eventKind(EventKind.PLAN).status(EventStatus.PLANNED).deleted(false)
                .build();
    }

    /**
     * План, закрытый отдельным фактом-ребёнком: меняется только статус.
     * Так работает {@code POST /events/{planId}/facts} — factAmount у плана остаётся null.
     */
    private FinancialEvent closedPlanNoFactAmount(Category category, LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).category(category).type(EventType.EXPENSE)
                .date(date).plannedAmount(new BigDecimal(amount))
                .eventKind(EventKind.PLAN).status(EventStatus.EXECUTED).deleted(false)
                .build();
    }

    /**
     * План, в строку которого внесли факт: {@code PATCH /events/{id}/fact} ставит factAmount
     * и переводит статус в EXECUTED. Вид события остаётся PLAN.
     */
    private FinancialEvent planPatchedWithFact(Category category, LocalDate date,
                                               String planned, String factual) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).category(category).type(EventType.EXPENSE)
                .date(date).plannedAmount(new BigDecimal(planned)).factAmount(new BigDecimal(factual))
                .eventKind(EventKind.PLAN).status(EventStatus.EXECUTED).deleted(false)
                .build();
    }

    private Category makeCategory(String name) {
        return Category.builder()
                .id(UUID.randomUUID()).name(name).type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM).forecastEnabled(true).deleted(false)
                .build();
    }
}
