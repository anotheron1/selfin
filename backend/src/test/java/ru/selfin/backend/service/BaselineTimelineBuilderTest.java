package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.capital.CapitalTrajectoryDto;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.FallbackKind;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.SandboxRef;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.dto.strategy.BreakdownDto;
import ru.selfin.backend.dto.strategy.BreakdownItemDto;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сборка остатка по месяцам (Р1). Поведение «точки = ядро» на базе — {@code StrategyFromCoreIT};
 * здесь то, чего IT не видит: прогноз обычных трат (ему нужна история) и строки разбивки.
 * Движок настоящий, подменён только сборщик его входа.
 */
class BaselineTimelineBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final YearMonth MAY = YearMonth.of(2026, 5);

    private FinancialEventRepository eventRepo;
    private BalanceCheckpointRepository checkpointRepo;
    private CategoryRepository categoryRepo;
    private CapitalService capitalService;
    private AccountBalanceService accountBalanceService;
    private PocketInputAssembler assembler;
    private BaselineTimelineBuilder builder;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        checkpointRepo = mock(BalanceCheckpointRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        capitalService = mock(CapitalService.class);
        accountBalanceService = mock(AccountBalanceService.class);
        assembler = mock(PocketInputAssembler.class);
        PredictionService predictionService = mock(PredictionService.class);

        builder = new BaselineTimelineBuilder(eventRepo, checkpointRepo, categoryRepo, predictionService,
                capitalService, accountBalanceService, assembler,
                Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC));
    }

    // ── первый месяц активности ─────────────────────────────────────────────

    @Test
    void firstActivityMonth_returns_earliest_of_all_three_sources() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.of(2024, 6, 15)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2024, 3, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.of(LocalDate.of(2024, 5, 1)));

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 3));
    }

    @Test
    void firstActivityMonth_uses_fact_when_earliest() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.of(2023, 1, 10)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2023, 4, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2023, 1));
    }

    @Test
    void firstActivityMonth_uses_revaluation_when_earliest() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.of(LocalDate.of(2022, 11, 1)));

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2022, 11));
    }

    @Test
    void firstActivityMonth_returns_previous_month_when_no_data() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(MARCH.minusMonths(1));
    }

    @Test
    void firstActivityMonth_truncates_to_month_ignoring_day() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.of(2024, 8, 15)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 8));
    }

    @Test
    void firstActivityMonth_with_only_checkpoint() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2024, 2, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 2));
    }

    // ── прошлые месяцы ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Р1: прошлый месяц — остаток счетов на последний день, как «на счёте»; доход и расход — факты")
    void buildPastPoints_useAccountsBalance_andFacts() {
        when(accountBalanceService.accountsBalanceAt(LocalDate.of(2026, 1, 31))).thenReturn(new BigDecimal("100000"));
        when(accountBalanceService.accountsBalanceAt(LocalDate.of(2026, 2, 28))).thenReturn(new BigDecimal("150000"));
        Category salary = category("Зарплата");
        Category food = category("Продукты");

        List<StrategyTimelinePointDto> past = builder.buildPastPoints(YearMonth.of(2026, 1), MARCH, Map.of(
                YearMonth.of(2026, 2), List.of(
                        fact(salary, EventType.INCOME, LocalDate.of(2026, 2, 5), 200_000),
                        fact(food, EventType.EXPENSE, LocalDate.of(2026, 2, 10), 40_000))));

        assertThat(past).extracting(StrategyTimelinePointDto::yearMonth)
                .containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        assertThat(past).allMatch(p -> p.phase() == StrategyPointPhase.PAST && p.balanceConfirmed() == null);
        assertThat(past.get(0).balance()).isEqualByComparingTo("100000");
        assertThat(past.get(1).balance()).isEqualByComparingTo("150000");
        assertThat(past.get(1).income()).isEqualByComparingTo("200000");
        assertThat(past.get(1).expense()).isEqualByComparingTo("40000");
        assertThat(past.get(1).nettoFlow()).isEqualByComparingTo("160000");
    }

    // ── капитал ─────────────────────────────────────────────────────────────

    @Test
    void enrichWithCapital_fills_capital_assets_liabilities_for_past_and_future() {
        YearMonth jan = YearMonth.of(2026, 1);
        YearMonth feb = YearMonth.of(2026, 2);
        YearMonth jun = YearMonth.of(2026, 6);
        List<StrategyTimelinePointDto> points = new ArrayList<>(List.of(
                pointWith(jan, StrategyPointPhase.PAST),
                pointWith(feb, StrategyPointPhase.PAST),
                pointWith(jun, StrategyPointPhase.FUTURE)
        ));
        when(capitalService.trajectory(any(), any())).thenReturn(new CapitalTrajectoryDto(List.of(
                new CapitalTrajectoryDto.Point(LocalDate.of(2026, 1, 31),
                        new BigDecimal("3500000"), new BigDecimal("4000000"),
                        new BigDecimal("4500000"), new BigDecimal("500000")),
                new CapitalTrajectoryDto.Point(LocalDate.of(2026, 2, 28),
                        new BigDecimal("3600000"), new BigDecimal("4100000"),
                        new BigDecimal("4600000"), new BigDecimal("500000"))
        )));

        List<StrategyTimelinePointDto> result = builder.enrichWithCapital(points);

        assertThat(result.get(0).capital()).isEqualByComparingTo("3500000");
        assertThat(result.get(1).capital()).isEqualByComparingTo("3600000");
        // Будущий месяц получает последний известный капитал
        assertThat(result.get(2).capital()).isEqualByComparingTo("3600000");
        assertThat(result.get(2).assets()).isEqualByComparingTo("4600000");
        assertThat(result.get(2).liabilities()).isEqualByComparingTo("500000");
    }

    // ── текущий и будущие месяцы из ядра ────────────────────────────────────

    /**
     * Вход ядра: сверка 100 000 на 01.03, факт «Кафе» 2 000 08.03, бронь 7 000 с прошедшей датой,
     * доход 50 000 и план «Ипотека» 20 000 в апреле, взнос в копилку 10 000 в апреле; прогноз обычных
     * трат — 3 000 до конца марта и 4 000 в апреле.
     */
    private final class CoreCase {
        final Category cafe = category("Кафе");
        final UUID mortgageId = UUID.randomUUID();
        final UUID salaryId = UUID.randomUUID();
        final EventSnapshot factSnap = new EventSnapshot(UUID.randomUUID(), LocalDate.of(2026, 3, 8),
                EventType.EXPENSE, EventKind.FACT, EventStatus.EXECUTED, Priority.MEDIUM,
                null, BigDecimal.valueOf(2_000), null, false, "Кафе");
        final EventSnapshot overdue = plan(UUID.randomUUID(), EventType.EXPENSE, LocalDate.of(2026, 3, 5), 7_000, "Связь");
        final EventSnapshot salary = plan(salaryId, EventType.INCOME, LocalDate.of(2026, 4, 5), 50_000, null);
        final EventSnapshot mortgage = plan(mortgageId, EventType.EXPENSE, LocalDate.of(2026, 4, 15), 20_000, null);
        final EventSnapshot contrib = new EventSnapshot(null, LocalDate.of(2026, 4, 5), EventType.EXPENSE,
                EventKind.PLAN, EventStatus.PLANNED, Priority.MEDIUM, BigDecimal.valueOf(10_000), null, null,
                false, "Отпуск", SyntheticKind.SAVINGS_CONTRIBUTION);
        final SandboxRef fundRef = SandboxRef.fund(UUID.randomUUID());

        void stub(LocalDate asOf, BigDecimal currentMonthForecast) {
            PocketScope scope = new PocketScope(PocketScope.Type.DATE, null, LocalDate.of(2026, 6, 30));
            PocketInput input = new PocketInput(asOf, BigDecimal.valueOf(100_000), LocalDate.of(2026, 3, 1), null,
                    List.of(factSnap, salary, mortgage, contrib), List.of(), List.of(overdue), List.of(),
                    scope, LocalDate.of(2026, 6, 30), FallbackKind.NONE, BigDecimal.ZERO,
                    currentMonthForecast, List.of("Продукты"), Map.of(APRIL, BigDecimal.valueOf(4_000)),
                    null, null, null);
            Map<YearMonth, Map<String, BigDecimal>> forecastByCategory = new LinkedHashMap<>();
            forecastByCategory.put(MARCH, Map.of("Продукты", currentMonthForecast));
            forecastByCategory.put(APRIL, Map.of("Продукты", BigDecimal.valueOf(4_000)));
            when(assembler.build(eq(scope), eq(asOf))).thenReturn(new PocketInputAssembler.Assembled(
                    input, Map.of(fundRef, List.of(contrib)), List.of(), forecastByCategory));

            RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
            when(eventRepo.findAllById(any())).thenReturn(List.of(
                    FinancialEvent.builder().id(mortgageId).category(category("Ипотека")).recurringRule(rule).build(),
                    FinancialEvent.builder().id(salaryId).category(category("Зарплата")).build()));
            when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(
                    fact(cafe, EventType.EXPENSE, LocalDate.of(2026, 3, 8), 2_000)));
            when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.of(2026, 3, 8)));
            when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2026, 3, 1)));
            when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());
            when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of());
            when(capitalService.trajectory(any(), any())).thenReturn(new CapitalTrajectoryDto(List.of()));
        }
    }

    @Test
    @DisplayName("Р1: текущий и будущие месяцы — точки ядра на конец месяца; главная линия с прогнозом, вторая по планам")
    void build_currentAndFutureMonths_fromCoreTrajectory() {
        CoreCase c = new CoreCase();
        c.stub(TODAY, BigDecimal.valueOf(3_000));

        TimelineSnapshot snap = builder.build(3, true);

        assertThat(snap.points()).extracting(StrategyTimelinePointDto::yearMonth)
                .containsExactly(MARCH, APRIL, MAY, YearMonth.of(2026, 6));
        StrategyTimelinePointDto march = snap.points().get(0);
        assertThat(march.phase()).isEqualTo(StrategyPointPhase.CURRENT);
        assertThat(march.balanceConfirmed()).as("на счёте 98 000 минус бронь").isEqualByComparingTo("91000");
        assertThat(march.balance()).as("и обычные траты до конца месяца").isEqualByComparingTo("88000");
        assertThat(march.expense()).as("факт + бронь + обычные траты").isEqualByComparingTo("12000");
        assertThat(march.income()).isEqualByComparingTo("0");

        StrategyTimelinePointDto april = snap.points().get(1);
        assertThat(april.phase()).isEqualTo(StrategyPointPhase.FUTURE);
        assertThat(april.balanceConfirmed()).isEqualByComparingTo("111000");
        assertThat(april.balance()).isEqualByComparingTo("104000");
        assertThat(april.income()).isEqualByComparingTo("50000");
        assertThat(april.expense()).as("ипотека + взнос + обычные траты").isEqualByComparingTo("34000");
        assertThat(april.nettoFlow()).isEqualByComparingTo("16000");

        StrategyTimelinePointDto may = snap.points().get(2);
        assertThat(may.balance()).isEqualByComparingTo("104000");
        assertThat(may.expense()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Р1: разбивка — факты и строки ядра; брони и взносы своими строками; прогноз по категориям")
    void build_breakdown_fromFactsAndCoreLines() {
        CoreCase c = new CoreCase();
        c.stub(TODAY, BigDecimal.valueOf(3_000));

        TimelineSnapshot snap = builder.build(3, true);

        BreakdownDto march = snap.points().get(0).breakdown();
        assertThat(march.expenseItems())
                .extracting(BreakdownItemDto::category, i -> i.amount().longValue(), BreakdownItemDto::isPredicted)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BaselineTimelineBuilder.OVERDUE_LINE, 7_000L, false),
                        org.assertj.core.groups.Tuple.tuple("Кафе", 2_000L, false),
                        org.assertj.core.groups.Tuple.tuple("Продукты", 3_000L, true));

        BreakdownDto april = snap.points().get(1).breakdown();
        assertThat(april.expenseItems())
                .extracting(BreakdownItemDto::category, i -> i.amount().longValue(), BreakdownItemDto::isPredicted)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Ипотека", 20_000L, false),
                        org.assertj.core.groups.Tuple.tuple(BaselineTimelineBuilder.CONTRIBUTIONS_LINE, 10_000L, false),
                        org.assertj.core.groups.Tuple.tuple("Продукты", 4_000L, true));
        assertThat(april.expenseItems().get(0).isRecurring()).isTrue();
        assertThat(april.incomeItems()).extracting(BreakdownItemDto::category).containsExactly("Зарплата");
    }

    @Test
    @DisplayName("Р1: последний день месяца — ядро обычные траты месяца уже не держит, и разбивка их не показывает")
    void build_predictedLines_onlyWhenCoreHoldsForecast() {
        LocalDate lastDay = LocalDate.of(2026, 3, 31);
        builder = new BaselineTimelineBuilder(eventRepo, checkpointRepo, categoryRepo, mock(PredictionService.class),
                capitalService, accountBalanceService, assembler,
                Clock.fixed(Instant.parse("2026-03-31T10:00:00Z"), ZoneOffset.UTC));
        CoreCase c = new CoreCase();
        c.stub(lastDay, BigDecimal.valueOf(3_000));

        StrategyTimelinePointDto march = builder.build(3, true).points().get(0);

        assertThat(march.balance()).isEqualByComparingTo(march.balanceConfirmed());
        assertThat(march.breakdown().expenseItems()).noneMatch(BreakdownItemDto::isPredicted);
    }

    @Test
    @DisplayName("Р1: сколько ядро держит по ссылке примерки — по месяцам; ссылка без строк и копилка плана без строк — с пустой картой")
    void build_heldByRef_perMonth() {
        CoreCase c = new CoreCase();
        c.stub(TODAY, BigDecimal.ZERO);
        SandboxRef emptyRef = SandboxRef.event(UUID.randomUUID());
        PocketInputAssembler.Assembled stubbed = assembler.build(
                new PocketScope(PocketScope.Type.DATE, null, LocalDate.of(2026, 6, 30)), TODAY);
        Map<SandboxRef, List<EventSnapshot>> refs = new LinkedHashMap<>(stubbed.baselineRefs());
        refs.put(emptyRef, List.of(plan(UUID.randomUUID(), EventType.EXPENSE, LocalDate.of(2027, 1, 1), 1_000, null)));
        UUID savedFund = UUID.randomUUID();   // накоплена до цели: в плане ядра, держать нечего
        when(assembler.build(any(), eq(TODAY))).thenReturn(new PocketInputAssembler.Assembled(
                stubbed.input(), refs, List.of(), stubbed.forecastByCategory(), java.util.Set.of(savedFund)));

        TimelineSnapshot snap = builder.build(3, false);

        assertThat(snap.heldByRef().get(c.fundRef)).containsExactlyEntriesOf(Map.of(APRIL, new BigDecimal("10000")));
        assertThat(snap.heldByRef()).containsKey(emptyRef);
        assertThat(snap.heldByRef().get(emptyRef)).isEmpty();
        assertThat(snap.heldByRef().get(SandboxRef.fund(savedFund))).as("ревью Codex на #129").isEmpty();
    }

    // ── хелперы ─────────────────────────────────────────────────────────────

    private static Category category(String name) {
        return Category.builder().id(UUID.randomUUID()).name(name).build();
    }

    private static FinancialEvent fact(Category category, EventType type, LocalDate date, long amount) {
        return FinancialEvent.builder().id(UUID.randomUUID()).date(date).category(category).type(type)
                .factAmount(BigDecimal.valueOf(amount)).eventKind(EventKind.FACT).deleted(false).build();
    }

    private static EventSnapshot plan(UUID id, EventType type, LocalDate date, long amount, String description) {
        return new EventSnapshot(id, date, type, EventKind.PLAN, EventStatus.PLANNED, Priority.HIGH,
                BigDecimal.valueOf(amount), null, null, false, description);
    }

    private static StrategyTimelinePointDto pointWith(YearMonth ym, StrategyPointPhase phase) {
        return new StrategyTimelinePointDto(ym, phase,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null);
    }
}
