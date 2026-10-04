package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.capital.CapitalTrajectoryDto;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.SandboxRef;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.dto.strategy.BreakdownDto;
import ru.selfin.backend.dto.strategy.BreakdownItemDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Остаток по месяцам для «Стратегии» и «Что с капиталом» (Р1, ANO-23).
 *
 * <p>Текущий и будущие месяцы — из ядра: точка траектории {@link PocketEngine} на последний день
 * месяца, вход — тот же {@link PocketInputAssembler}, что у «Свободно». Доход, расход и разбивка
 * месяца — строки, которые траектория держит ({@link PocketEngine#held}), поэтому подсказка
 * объясняет ровно то число, что стоит на линии. Своей выборки планов здесь больше нет: до Р1 она
 * расходилась с ядром в пяти местах (карта C1, Р1, пункты (а)–(д)).
 *
 * <p>Прошлые месяцы — остаток счетов на последний день, как «на счёте» у ядра
 * ({@link AccountBalanceService#accountsBalanceAt}); доход, расход и разбивка — записи-факты.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BaselineTimelineBuilder {

    static final int MIN_CATEGORIES_FOR_FAN = 3;
    /** Строки разбивки без категории — теми же словами, что в расшифровке «Свободно». */
    static final String OVERDUE_LINE = "Брони с прошедшей датой";
    static final String CONTRIBUTIONS_LINE = "Взносы в копилки";
    static final String NO_CATEGORY = "Без категории";

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final CategoryRepository categoryRepository;
    private final PredictionService predictionService;
    private final CapitalService capitalService;
    private final AccountBalanceService accountBalanceService;
    private final PocketInputAssembler assembler;
    /** ANO-39: «сегодня» приходит извне — иначе календарную логику не проверить детерминированно. */
    private final Clock clock;

    /**
     * Полный timeline: прошлые, текущий и будущие месяцы, обогащённые капиталом и (по запросу)
     * разбивкой по категориям.
     */
    public TimelineSnapshot build(int horizonMonths, boolean withBreakdown) {
        LocalDate today = LocalDate.now(clock);
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth horizonEnd = currentMonth.plusMonths(horizonMonths);
        YearMonth firstMonth = firstActivityMonth();

        PocketInputAssembler.Assembled core = assembler.build(
                new PocketScope(PocketScope.Type.DATE, null, horizonEnd.atEndOfMonth()), today);
        List<PocketEngine.Held> held = PocketEngine.held(core.input());
        Map<YearMonth, CoreMonth> months = coreMonths(PocketEngine.calculate(core.input()), held);

        Fan fan = Fan.of(computeStatsMap());
        // Записи-факты с первого месяца по сегодня — одним запросом: прошлым месяцам и текущему.
        Map<YearMonth, List<FinancialEvent>> facts = eventRepository
                .findFactsByDateRange(firstMonth.atDay(1), today).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        List<StrategyTimelinePointDto> all = new ArrayList<>(buildPastPoints(firstMonth, currentMonth, facts));
        for (int k = 0; k <= horizonMonths; k++) {
            YearMonth ym = currentMonth.plusMonths(k);
            List<FinancialEvent> monthFacts = k == 0 ? facts.getOrDefault(ym, List.of()) : List.of();
            all.add(corePoint(ym, k, months.get(ym), monthFacts, fan));
        }

        all = enrichWithCapital(all);
        if (withBreakdown) {
            all = enrichWithBreakdown(all, facts, months, core.forecastByCategory());
        }
        return new TimelineSnapshot(firstMonth, currentMonth, horizonEnd,
                PredictionService.HISTORY_WINDOW_MONTHS, fan.enabled(), all, heldByRef(core, held));
    }

    // ── месяцы ядра ─────────────────────────────────────────────────────────

    /**
     * Месяц ядра: точка траектории на последний день, прогноз обычных трат за месяц и строки,
     * которые траектория держит в этом месяце.
     */
    private record CoreMonth(PocketResultDto.TrajectoryPoint end, BigDecimal forecast,
                             List<PocketEngine.Held> held) {
        BigDecimal sum(boolean income) {
            return held.stream()
                    .filter(h -> (h.event().type() == EventType.INCOME) == income)
                    .map(PocketEngine.Held::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static Map<YearMonth, CoreMonth> coreMonths(PocketResultDto result, List<PocketEngine.Held> held) {
        Map<YearMonth, PocketResultDto.TrajectoryPoint> ends = new LinkedHashMap<>();
        for (PocketResultDto.TrajectoryPoint t : result.trajectory()) ends.put(YearMonth.from(t.date()), t);
        Map<YearMonth, List<PocketEngine.Held>> byMonth = held.stream()
                .collect(Collectors.groupingBy(h -> YearMonth.from(h.day())));

        Map<YearMonth, CoreMonth> months = new LinkedHashMap<>();
        BigDecimal forecastBefore = BigDecimal.ZERO;
        for (Map.Entry<YearMonth, PocketResultDto.TrajectoryPoint> e : ends.entrySet()) {
            // Прогноз за месяц — насколько две линии разошлись с прошлого конца месяца.
            BigDecimal forecastSoFar = e.getValue().balance().subtract(mainLine(e.getValue()));
            months.put(e.getKey(), new CoreMonth(e.getValue(), forecastSoFar.subtract(forecastBefore),
                    byMonth.getOrDefault(e.getKey(), List.of())));
            forecastBefore = forecastSoFar;
        }
        return months;
    }

    /** Главная линия — «с обычными тратами»; где прогноз ещё не накоплен, она совпадает с линией по планам. */
    private static BigDecimal mainLine(PocketResultDto.TrajectoryPoint t) {
        return t.balanceWithForecast() != null ? t.balanceWithForecast() : t.balance();
    }

    /**
     * Точка текущего или будущего месяца. У текущего к строкам ядра добавлены записи-факты с
     * начала месяца: остаток на конец месяца — это и уже случившееся, и ещё ожидаемое.
     */
    private static StrategyTimelinePointDto corePoint(YearMonth ym, int k, CoreMonth month,
                                                      List<FinancialEvent> facts, Fan fan) {
        BigDecimal main = mainLine(month.end());
        BigDecimal income = month.sum(true).add(sumFacts(facts, EventType.INCOME));
        BigDecimal expense = month.sum(false).add(sumFacts(facts, EventType.EXPENSE)).add(month.forecast());
        BigDecimal[] band = fan.around(main, k);
        return new StrategyTimelinePointDto(
                ym, k == 0 ? StrategyPointPhase.CURRENT : StrategyPointPhase.FUTURE,
                main, income, expense, income.subtract(expense),
                month.end().balance(), band[0], band[1],
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,   // капитал — в enrichWithCapital
                null);                                               // разбивка — в enrichWithBreakdown
    }

    /**
     * Сколько ядро держит по каждой ссылке примерки, по месяцам. «Что с капиталом» строит из
     * этого основу без зафиксированного и дельты зафиксированного по оси счёта.
     */
    private static Map<SandboxRef, Map<YearMonth, BigDecimal>> heldByRef(PocketInputAssembler.Assembled core,
                                                                         List<PocketEngine.Held> held) {
        Map<EventSnapshot, SandboxRef> refOf = new IdentityHashMap<>();
        Map<SandboxRef, Map<YearMonth, BigDecimal>> byRef = new LinkedHashMap<>();
        core.baselineRefs().forEach((ref, snapshots) -> {
            byRef.put(ref, new TreeMap<>());
            snapshots.forEach(s -> refOf.put(s, ref));
        });
        // Копилка в плане ядра, по которой держать нечего — накоплена до цели или срок в этом
        // месяце, — тоже здесь, с пустой картой: иначе «Что с капиталом» взял бы для неё формулу
        // и вычел взносы на всю цель, которых ядро не держит (ревью Codex на #129).
        core.plannedFunds().forEach(id -> byRef.putIfAbsent(SandboxRef.fund(id), new TreeMap<>()));
        for (PocketEngine.Held h : held) {
            SandboxRef ref = refOf.get(h.event());
            if (ref != null) byRef.get(ref).merge(YearMonth.from(h.day()), h.amount(), BigDecimal::add);
        }
        return byRef;
    }

    /** Веер «Диапазон»: полуразмах трат по истории категорий, растёт как √k — k месяцев от текущего. */
    private record Fan(boolean enabled, double sumHalfIqr) {
        static Fan of(Map<Category, CategoryMonthStats> statsMap) {
            List<CategoryMonthStats> eligible = statsMap.values().stream()
                    .filter(s -> s.monthsOfHistory() >= PredictionService.MIN_HISTORY_MONTHS)
                    .toList();
            double sumHalfIqr = Math.sqrt(eligible.stream()
                    .mapToDouble(s -> {
                        double halfIqr = s.p75().subtract(s.p25()).doubleValue() / 2.0;
                        return halfIqr * halfIqr;
                    })
                    .sum());
            return new Fan(eligible.size() >= MIN_CATEGORIES_FOR_FAN, sumHalfIqr);
        }

        /** {нижняя, верхняя} граница вокруг главной линии; без веера — сама линия. */
        BigDecimal[] around(BigDecimal main, int k) {
            if (!enabled) return new BigDecimal[]{main, main};
            double half = Math.min(sumHalfIqr * Math.sqrt(k), 2.0 * Math.abs(main.doubleValue()));
            BigDecimal halfBd = BigDecimal.valueOf(half).setScale(2, RoundingMode.HALF_UP);
            return new BigDecimal[]{main.subtract(halfBd), main.add(halfBd)};
        }
    }

    /**
     * Загружает все forecast-enabled категории и вычисляет статистику для каждой ОДИН РАЗ —
     * для веера.
     */
    private Map<Category, CategoryMonthStats> computeStatsMap() {
        List<Category> forecastCats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        Map<Category, CategoryMonthStats> result = new LinkedHashMap<>();
        for (Category cat : forecastCats) {
            result.put(cat, predictionService.getStatsForCategory(cat, PredictionService.HISTORY_WINDOW_MONTHS));
        }
        return result;
    }

    // ── прошлые месяцы ──────────────────────────────────────────────────────

    List<StrategyTimelinePointDto> buildPastPoints(YearMonth from, YearMonth currentMonth,
                                                   Map<YearMonth, List<FinancialEvent>> facts) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        for (YearMonth ym = from; ym.isBefore(currentMonth); ym = ym.plusMonths(1)) {
            List<FinancialEvent> monthFacts = facts.getOrDefault(ym, List.of());
            BigDecimal income = sumFacts(monthFacts, EventType.INCOME);
            BigDecimal expense = sumFacts(monthFacts, EventType.EXPENSE);
            points.add(new StrategyTimelinePointDto(
                    ym,
                    StrategyPointPhase.PAST,
                    accountBalanceService.accountsBalanceAt(ym.atEndOfMonth()),
                    income,
                    expense,
                    income.subtract(expense),
                    null, null, null,                       // balanceConfirmed/Low/High не для PAST
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,  // капитал — в enrichWithCapital
                    null                                    // разбивка — в enrichWithBreakdown
            ));
        }
        return points;
    }

    private static BigDecimal sumFacts(List<FinancialEvent> facts, EventType type) {
        return facts.stream()
                .filter(e -> e.getType() == type)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── капитал ─────────────────────────────────────────────────────────────

    /**
     * Обогащает точки timeline данными капитала (capital, assets, liabilities).
     *
     * <p><b>Контракт:</b> {@code points} ДОЛЖЕН быть полным списком (past + current + future)
     * — диапазон вызова `trajectory(first, last)` определяется крайними точками. При передаче
     * частичного списка trajectory будет вычислена на меньшем интервале, и future-точки могут
     * получить некорректные значения капитала.
     */
    List<StrategyTimelinePointDto> enrichWithCapital(List<StrategyTimelinePointDto> points) {
        if (points.isEmpty()) return points;

        YearMonth first = points.get(0).yearMonth();
        YearMonth last = points.get(points.size() - 1).yearMonth();

        CapitalTrajectoryDto trajectory = capitalService.trajectory(first.atDay(1), last.atEndOfMonth());

        // Маппим точки траектории по YearMonth
        Map<YearMonth, CapitalTrajectoryDto.Point> byMonth = trajectory.points().stream()
                .collect(Collectors.toMap(
                        p -> YearMonth.from(p.date()),
                        p -> p,
                        (a, b) -> b   // если коллизия — берём более поздний
                ));

        // Last known — для пропусков и для будущих точек после последней revaluation
        BigDecimal lastCapital = BigDecimal.ZERO;
        BigDecimal lastAssets = BigDecimal.ZERO;
        BigDecimal lastLiabilities = BigDecimal.ZERO;

        List<StrategyTimelinePointDto> enriched = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            CapitalTrajectoryDto.Point cap = byMonth.get(p.yearMonth());
            if (cap != null) {
                lastCapital = cap.capital();
                lastAssets = cap.assets();
                lastLiabilities = cap.liabilities();
            }
            enriched.add(new StrategyTimelinePointDto(
                    p.yearMonth(), p.phase(),
                    p.balance(), p.income(), p.expense(), p.nettoFlow(),
                    p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                    lastCapital, lastAssets, lastLiabilities,
                    p.breakdown()
            ));
        }
        return enriched;
    }

    // ── разбивка ────────────────────────────────────────────────────────────

    /**
     * Разбивка месяца: прошлый — записи-факты по категориям; текущий — факты и строки ядра одной
     * суммой на категорию; будущий — строки ядра. Брони с прошедшей датой и взносы в копилки —
     * своими строками, прогноз обычных трат — по категориям, если ядро держит его в этом месяце.
     */
    List<StrategyTimelinePointDto> enrichWithBreakdown(List<StrategyTimelinePointDto> points,
                                                       Map<YearMonth, List<FinancialEvent>> facts,
                                                       Map<YearMonth, CoreMonth> months,
                                                       Map<YearMonth, Map<String, BigDecimal>> forecastByCategory) {
        // Категория и «повторяется» строк ядра — по id: движок работает на плоских снимках без JPA.
        List<UUID> ids = months.values().stream()
                .flatMap(m -> m.held().stream())
                .map(h -> h.event().id())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, FinancialEvent> planned = new HashMap<>();
        if (!ids.isEmpty()) {
            for (FinancialEvent e : eventRepository.findAllById(ids)) planned.put(e.getId(), e);
        }

        List<StrategyTimelinePointDto> enriched = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            Lines income = new Lines();
            Lines expense = new Lines();
            if (p.phase() != StrategyPointPhase.FUTURE) {
                for (FinancialEvent f : facts.getOrDefault(p.yearMonth(), List.of())) {
                    if (f.getCategory() == null) continue;
                    Lines lines = f.getType() == EventType.INCOME ? income
                            : f.getType() == EventType.EXPENSE ? expense : null;
                    if (lines != null) {
                        lines.add(f.getCategory().getName(), f.getFactAmount(), f.getRecurringRule() != null);
                    }
                }
            }
            CoreMonth month = p.phase() == StrategyPointPhase.PAST ? null : months.get(p.yearMonth());
            if (month != null) {
                for (PocketEngine.Held h : month.held()) {
                    FinancialEvent e = h.event().id() != null ? planned.get(h.event().id()) : null;
                    (h.event().type() == EventType.INCOME ? income : expense)
                            .add(nameOf(h, e), h.amount(), e != null && e.getRecurringRule() != null);
                }
                if (month.forecast().signum() != 0) {
                    forecastByCategory.getOrDefault(p.yearMonth(), Map.of()).forEach(expense::predicted);
                }
            }
            enriched.add(withBreakdown(p, new BreakdownDto(income.items(), expense.items())));
        }
        return enriched;
    }

    /** Имя строки ядра в разбивке: бронь с прошедшей датой и взнос — по смыслу, план — категорией. */
    private static String nameOf(PocketEngine.Held h, FinancialEvent planned) {
        if (h.event().syntheticKind() == SyntheticKind.SAVINGS_CONTRIBUTION) return CONTRIBUTIONS_LINE;
        if (h.event().date() != null && h.event().date().isBefore(h.day())) return OVERDUE_LINE;
        if (planned != null && planned.getCategory() != null) return planned.getCategory().getName();
        String description = h.event().description();
        return description != null && !description.isBlank() ? description : NO_CATEGORY;
    }

    /** Строки разбивки по имени: суммы складываются; повторяется — если повторяется хоть одна. */
    private static final class Lines {
        private final Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        private final Set<String> recurring = new HashSet<>();
        private final Map<String, BigDecimal> predicted = new LinkedHashMap<>();

        void add(String name, BigDecimal amount, boolean isRecurring) {
            amounts.merge(name, amount != null ? amount : BigDecimal.ZERO, BigDecimal::add);
            if (isRecurring) recurring.add(name);
        }

        void predicted(String name, BigDecimal amount) {
            predicted.merge(name, amount, BigDecimal::add);
        }

        /** Сначала факты и планы по убыванию суммы, затем прогноз. */
        List<BreakdownItemDto> items() {
            Comparator<BreakdownItemDto> byAmount = Comparator.comparing(BreakdownItemDto::amount).reversed();
            Stream<BreakdownItemDto> known = amounts.entrySet().stream()
                    .map(e -> new BreakdownItemDto(e.getKey(), e.getValue(), recurring.contains(e.getKey()), false))
                    .sorted(byAmount);
            Stream<BreakdownItemDto> forecast = predicted.entrySet().stream()
                    .map(e -> new BreakdownItemDto(e.getKey(), e.getValue(), false, true))
                    .sorted(byAmount);
            return Stream.concat(known, forecast).collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private StrategyTimelinePointDto withBreakdown(StrategyTimelinePointDto p, BreakdownDto br) {
        return new StrategyTimelinePointDto(
                p.yearMonth(), p.phase(),
                p.balance(), p.income(), p.expense(), p.nettoFlow(),
                p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                p.capital(), p.assets(), p.liabilities(),
                br
        );
    }

    /**
     * Самый ранний месяц активности пользователя — минимум из:
     * <ul>
     *   <li>первого FACT-события</li>
     *   <li>первого чекпоинта</li>
     *   <li>первой переоценки капитала</li>
     * </ul>
     * Если данных нет — возвращает предыдущий месяц (условный «старт»).
     *
     * <p>Используется для определения левой границы шкалы и {@code predictionWindowMonths}.
     */
    YearMonth firstActivityMonth() {
        Optional<LocalDate> earliestFact = eventRepository.findEarliestFactDate();
        Optional<LocalDate> earliestCheckpoint = checkpointRepository.findEarliestCheckpointDate();
        Optional<LocalDate> earliestRevaluation = capitalService.findEarliestRevaluationDate();

        Optional<LocalDate> earliest = Stream.of(earliestFact, earliestCheckpoint, earliestRevaluation)
                .flatMap(Optional::stream)
                .min(LocalDate::compareTo);

        return earliest
                .map(YearMonth::from)
                .orElseGet(() -> YearMonth.now(clock).minusMonths(1));
    }
}
