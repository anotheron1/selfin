package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.ForecastReadinessDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PredictionService {

    private final FinancialEventRepository eventRepository;
    /** ANO-80: обход прогноза идёт по включённым категориям, а не по событиям месяца. */
    private final CategoryRepository categoryRepository;
    /** ANO-39: «сегодня» приходит извне — иначе календарную логику не проверить детерминированно. */
    private final Clock clock;

    /**
     * Сколько полных месяцев наблюдения нужно, чтобы верить медиане категории.
     *
     * <p>ANO-80: один порог на трёх потребителей — прогноз текущего месяца, прогноз будущих
     * месяцев ({@code PocketInputAssembler}) и конус fan chart ({@code BaselineTimelineBuilder}).
     * Вторую копию запрещает {@code ForecastThresholdSingleSourceTest}: разъехавшись, копии
     * дадут экран, где прогноз в кармашке есть, а конуса рядом нет.
     */
    public static final int MIN_HISTORY_MONTHS = 3;

    /** Окно истории для медианы — те же шесть месяцев у всех троих. */
    public static final int HISTORY_WINDOW_MONTHS = 6;

    /**
     * Прогноз по всем категориям с включённой галочкой.
     *
     * <p>ANO-80: обход идёт по КАТЕГОРИЯМ, а не по событиям месяца. До этой задачи категория
     * без единого факта в текущем месяце не попадала в расчёт вовсе — прогноз появлялся
     * только после первой траты и тут же раздувал её дневным темпом. Норма известна заранее
     * и не ждёт, пока человек что-нибудь купит.
     *
     * <p>Формула на категорию:
     * <pre>сверх плана = max(0, медиана − потрачено в месяце − непогашенные планы месяца)</pre>
     *
     * Медиана — это ВСЯ обычная трата месяца, а факт и план — её части, уже стоящие в пути
     * денег: факт ушёл со счёта, план удержан траекторией (просроченный — строкой брони).
     * Тот же принцип уже действовал для будущих месяцев (ANO-36), но до текущего не доехал.
     */
    public MonthlyForecastDto forecastFromEvents(List<FinancialEvent> monthEvents,
                                                 List<FinancialEvent> reservedOverdue,
                                                 LocalDate today) {
        List<CategoryForecastDto> forecasts = new ArrayList<>();
        BigDecimal netDelta = BigDecimal.ZERO;

        Set<UUID> reservedIds = reservedOverdue.stream()
                .map(FinancialEvent::getId).collect(Collectors.toSet());

        List<Category> enabled = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        Map<UUID, CategoryMonthStats> stats = statsForCategories(enabled, HISTORY_WINDOW_MONTHS, today);

        for (Category cat : enabled) {
            List<FinancialEvent> catEvents = monthEvents.stream()
                    .filter(e -> !e.isDeleted())
                    .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                    .toList();

            BigDecimal fact = sumFacts(catEvents);
            BigDecimal pendingPlans = sumHeldPlans(catEvents, today, reservedIds);
            BigDecimal median = medianIfTrusted(stats.get(cat.getId()));

            BigDecimal beyondPlan = median.subtract(fact).subtract(pendingPlans).max(BigDecimal.ZERO);
            BigDecimal projection = median.max(fact.add(pendingPlans));

            forecasts.add(new CategoryForecastDto(cat.getName(), fact, sumAllPlans(catEvents),
                    projection, beyondPlan, buildHistory(catEvents, median, pendingPlans, today)));
            netDelta = netDelta.add(beyondPlan);
        }

        return new MonthlyForecastDto(forecasts, netDelta);
    }

    /**
     * Готовность прогноза: сколько месяцев наблюдения набралось и когда он появится.
     *
     * <p>ANO-80. Без этого случай «галочка стоит, а прогноза нет» читается как поломка:
     * человек включил, ничего не изменилось, и единственный доступный ему вывод —
     * «не работает». Экран категорий дописывает месяц готовности рядом с пометкой.
     */
    public ForecastReadinessDto readiness() {
        LocalDate today = LocalDate.now(clock);
        LocalDate firstSpending = eventRepository.findFirstSpendingDate();
        if (firstSpending == null) {
            // Считать не от чего: обещать месяц было бы выдумкой.
            return new ForecastReadinessDto(0, MIN_HISTORY_MONTHS, null);
        }

        YearMonth lastFull = YearMonth.from(today).minusMonths(1);
        YearMonth firstObserved = YearMonth.from(firstSpending).plusMonths(1);
        int observed = (int) Math.max(0, ChronoUnit.MONTHS.between(firstObserved, lastFull) + 1);
        if (observed >= MIN_HISTORY_MONTHS) {
            return new ForecastReadinessDto(observed, MIN_HISTORY_MONTHS, null);
        }

        // Каждый прошедший месяц добавляет один месяц наблюдения.
        YearMonth readyFrom = YearMonth.from(today).plusMonths(MIN_HISTORY_MONTHS - observed);
        return new ForecastReadinessDto(observed, MIN_HISTORY_MONTHS, readyFrom.toString());
    }

    /** Медиана категории либо ноль, если месяцев наблюдения меньше порога. */
    private BigDecimal medianIfTrusted(CategoryMonthStats stats) {
        if (stats == null) return BigDecimal.ZERO;
        return stats.monthsOfHistory() >= MIN_HISTORY_MONTHS ? stats.median() : BigDecimal.ZERO;
    }

    /**
     * Compute forecasts fetching events from DB. Use for standalone /forecast endpoint.
     */
    public MonthlyForecastDto forecastMonth(YearMonth month, LocalDate today) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<FinancialEvent> events = eventRepository.findAllByDeletedFalseAndDateBetween(start, end);
        // Брони просрочки у этого пути нет: эндпоинт отвечает про месяц, а не про кармашек.
        // Планы раньше сегодня из нормы не вычитаются — и это верно, они нигде не удержаны.
        return forecastFromEvents(events, List.of(), today);
    }

    /**
     * Стата трат категории по МЕСЯЦАМ НАБЛЮДЕНИЯ, не больше {@code historyWindowMonths}.
     *
     * <p>ANO-80. Месяц наблюдения — полный месяц от первого факта пользователя до конца
     * прошлого месяца; месяц первого факта отбрасывается как неполный. Месяц, в котором учёт
     * вёлся, но в этой категории не тратилось, даёт в ряд полноценный НОЛЬ. До ANO-80 такие
     * месяцы в ряд не попадали вовсе, и редкая категория выглядела регулярной.
     *
     * <p>Отсюда и смысл {@code monthsOfHistory}: это «сколько месяцев мы наблюдаем за
     * человеком», а не «в скольких месяцах он тратил в этой категории». Редкая категория
     * порог проходит и гасит себя низкой медианой, а не отсекается порогом.
     *
     * <p>Трата — это событие с ненулевым {@code factAmount}, любого вида. Фильтр по
     * {@code eventKind == FACT} терял траты, записанные правкой строки плана: тот же путь,
     * что чинится в {@link #sumFacts}. Обе половины одной формулы обязаны считать одинаково.
     *
     * <p>Если {@code monthsOfHistory < MIN_HISTORY_MONTHS}, caller не должен учитывать
     * категорию — но median всё равно вычисляется.
     *
     * <p>Percentile-вычисление — линейная интерполяция между соседними точками отсортированного массива.
     */
    public CategoryMonthStats getStatsForCategory(Category cat, int historyWindowMonths) {
        return statsForCategories(List.of(cat), historyWindowMonths, LocalDate.now(clock))
                .getOrDefault(cat.getId(), noStats(cat.getId()));
    }

    /**
     * То же, что {@link #getStatsForCategory}, но сразу по списку категорий и за ОДИН поход
     * в базу на всех.
     *
     * <p>ANO-80: прогноз текущего месяца считается по каждой включённой категории, и
     * поштучный вызов давал бы два запроса на категорию при каждом расчёте кармашка. На
     * боевых данных владельца это 21 категория × 2 запроса × 2 скоупа на одну загрузку
     * дашборда. Окно и первая трата от категории не зависят — значит, и спрашивать их
     * по разу на категорию незачем.
     */
    public Map<UUID, CategoryMonthStats> statsForCategories(List<Category> categories,
                                                            int historyWindowMonths,
                                                            LocalDate today) {
        Map<UUID, CategoryMonthStats> result = new LinkedHashMap<>();
        if (categories.isEmpty()) return result;

        YearMonth lastFull = YearMonth.from(today).minusMonths(1);
        LocalDate firstSpending = eventRepository.findFirstSpendingDate();
        if (firstSpending == null) {
            categories.forEach(c -> result.put(c.getId(), noStats(c.getId())));
            return result;
        }

        // ANO-80. Месяц первой траты отбрасывается ВСЕГДА, даже если она пришлась на первое
        // число: запись первого числа так же может быть занесена задним числом, как и любая
        // другая. Правило не пытается угадать, вёлся ли учёт с начала месяца, — и потому
        // проверяется тестом без оговорок. Замерено: неполный первый месяц занижал медианы
        // на 13–27 % (эталонный стенд, «Авто» 44 379 → 38 598, «Медицина» 16 615 → 12 076).
        YearMonth firstObserved = YearMonth.from(firstSpending).plusMonths(1);
        YearMonth windowStart = lastFull.minusMonths(historyWindowMonths - 1L);
        if (windowStart.isBefore(firstObserved)) windowStart = firstObserved;
        if (windowStart.isAfter(lastFull)) {
            categories.forEach(c -> result.put(c.getId(), noStats(c.getId())));
            return result;
        }

        List<YearMonth> observedMonths = new ArrayList<>();
        for (YearMonth m = windowStart; !m.isAfter(lastFull); m = m.plusMonths(1)) {
            observedMonths.add(m);
        }

        Map<UUID, Map<YearMonth, BigDecimal>> spendingByCategory = eventRepository
                .findSpendingByDateRange(windowStart.atDay(1), lastFull.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getCategory() != null && e.getDate() != null && e.getFactAmount() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getCategory().getId(),
                        Collectors.groupingBy(
                                e -> YearMonth.from(e.getDate()),
                                Collectors.reducing(BigDecimal.ZERO,
                                        FinancialEvent::getFactAmount, BigDecimal::add))
                ));

        for (Category cat : categories) {
            Map<YearMonth, BigDecimal> monthlyTotals =
                    spendingByCategory.getOrDefault(cat.getId(), Map.of());

            // Месяц наблюдения без траты в категории — полноценный ноль в ряду, а не пропуск.
            // Без этого «Одежда», покупаемая раз в квартал, закладывалась бы каждый месяц
            // целиком: на стенде такая категория давала медиану 5 283 вместо честного нуля.
            List<BigDecimal> sorted = observedMonths.stream()
                    .map(m -> monthlyTotals.getOrDefault(m, BigDecimal.ZERO))
                    .sorted()
                    .toList();

            result.put(cat.getId(), new CategoryMonthStats(
                    cat.getId(),
                    observedMonths.size(),
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.25),
                    percentile(sorted, 0.75)));
        }
        return result;
    }

    private static CategoryMonthStats noStats(UUID categoryId) {
        return new CategoryMonthStats(categoryId, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Линейная интерполяция percentile из отсортированного списка.
     */
    private BigDecimal percentile(List<BigDecimal> sorted, double q) {
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        if (sorted.size() == 1) return sorted.get(0);

        double position = q * (sorted.size() - 1);
        int lowerIdx = (int) Math.floor(position);
        int upperIdx = (int) Math.ceil(position);
        if (lowerIdx == upperIdx) return sorted.get(lowerIdx);

        double frac = position - lowerIdx;
        BigDecimal lower = sorted.get(lowerIdx);
        BigDecimal upper = sorted.get(upperIdx);
        BigDecimal diff = upper.subtract(lower);
        return lower.add(diff.multiply(BigDecimal.valueOf(frac)));
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Линия спарклайна: факт по дням и планка обычного месяца.
     *
     * <p>ANO-80: раньше проекция пересчитывалась от номера дня и потому извивалась — одна и
     * та же трата рисовала разную кривую в зависимости от даты записи. Теперь это
     * горизонтальная планка, к которой ползёт факт; пересечение планки означает «в этом
     * месяце выходит дороже обычного» и видно без чисел.
     */
    private List<DailyForecastPointDto> buildHistory(List<FinancialEvent> catEvents,
                                                     BigDecimal median,
                                                     BigDecimal pendingPlans,
                                                     LocalDate today) {
        List<DailyForecastPointDto> points = new ArrayList<>();
        LocalDate monthStart = today.withDayOfMonth(1);

        for (int d = 1; d <= today.getDayOfMonth(); d++) {
            LocalDate dayDate = monthStart.withDayOfMonth(d);

            // Вид события не фильтруем по той же причине, что в sumFacts: факт может лежать
            // на строке плана.
            BigDecimal factOnDay = catEvents.stream()
                    .filter(e -> e.getDate() != null && !e.getDate().isAfter(dayDate))
                    .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            points.add(new DailyForecastPointDto(d, factOnDay,
                    median.max(factOnDay.add(pendingPlans))));
        }

        return points;
    }

    /**
     * Непогашенные планы месяца — то, что траектория кармашка уже удерживает.
     *
     * <p>Предикат намеренно повторяет {@code PocketEngine.isPendingPlan}: вычитать из нормы
     * надо ровно то, что уже стоит в пути денег, — не больше и не меньше. Разойдись эти два
     * места, и трата посчиталась бы дважды либо пропала.
     *
     * <p>Отсюда и проверка {@code factAmount == null}: план, закрытый фактом, движок пендингом
     * не считает, и вычитать его нельзя — его уже заменил факт. На эталонном стенде в сентябре
     * ровно один такой план, и первый замер без этой проверки дал по «Авто» 16 000 вместо
     * 12 000.
     */
    private BigDecimal sumHeldPlans(List<FinancialEvent> events, LocalDate today,
                                    Set<UUID> reservedOverdueIds) {
        Map<UUID, BigDecimal> settled = settledByPlan(events);
        return events.stream()
                .filter(e -> isPendingPlan(e, settled))
                .filter(e -> heldByMoneyPath(e, today, reservedOverdueIds))
                .map(e -> PlanRemainder.of(e.getPlannedAmount(), settled.get(e.getId())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Сколько по каждому плану погашено фактами-детьми (ANO-155). */
    private static Map<UUID, BigDecimal> settledByPlan(List<FinancialEvent> events) {
        Map<UUID, BigDecimal> settled = new HashMap<>();
        for (FinancialEvent e : events) {
            if (e.getEventKind() == EventKind.FACT && e.getParentEventId() != null
                    && e.getFactAmount() != null) {
                settled.merge(e.getParentEventId(), e.getFactAmount(), BigDecimal::add);
            }
        }
        return settled;
    }

    /**
     * Ещё не погашен: предикат {@code PocketEngine.isPendingPlan}, условие в условие.
     *
     * <p>ANO-155: план с непогашенным остатком остаётся в пути денег, поэтому вычитать из
     * нормы надо остаток, а не полную сумму. Арифметика остатка — в {@link PlanRemainder},
     * здесь только отбор.
     */
    private static boolean isPendingPlan(FinancialEvent e, Map<UUID, BigDecimal> settled) {
        return e.getFactAmount() == null
                && e.getEventKind() == EventKind.PLAN
                && e.getStatus() == EventStatus.PLANNED
                && PlanRemainder.of(e.getPlannedAmount(), settled.get(e.getId())).signum() > 0;
    }

    /**
     * Стоит ли план в пути денег — то есть удержан ли он траекторией или бронью просрочки.
     *
     * <p>ANO-80, найдено ревью PR #43. Вычитать из нормы можно ровно то, что уже удержано:
     * вычтешь лишнее — второе число выйдет оптимистичнее правды и может проглотить
     * предупреждение о разрыве. План со статусом {@code PLANNED}, датированный раньше
     * сегодня и НЕ мандаторный, не удерживается нигде: в будущие дни движка он не попадает
     * по дате, в расход сегодняшнего дня — тоже, а бронь просрочки берёт только
     * {@code priority = HIGH} и только после якоря.
     *
     * <p>Три ветки зеркалят три шага движка и ничего не добавляют от себя:
     *
     * <ol>
     *   <li>день позже сегодня — движок держит план в {@code futureByDay}, с тем же
     *       фильтром хотелок {@code allowedInTrajectory};</li>
     *   <li>ровно сегодня — держит в {@code todayExpenses}, и там фильтр строже:
     *       хотелки исключены целиком;</li>
     *   <li>раньше сегодня — держит только бронь, и её состав приходит списком от
     *       вызывающего. Правило брони (HIGH, после якоря, без факта-ребёнка) здесь
     *       НЕ повторяется: копия предиката из девяти условий — ровно та болезнь,
     *       которую ANO-23 называет по имени.</li>
     * </ol>
     */
    private static boolean heldByMoneyPath(FinancialEvent e, LocalDate today,
                                           Set<UUID> reservedOverdueIds) {
        if (e.getDate() == null) return false;
        if (e.getDate().isAfter(today)) return allowedInTrajectory(e);
        if (e.getDate().isEqual(today)) return e.getWishlistStatus() == null;
        return reservedOverdueIds.contains(e.getId());
    }

    /** Фильтр хотелок для траектории: копия {@code PocketEngine.allowedInTrajectory}. */
    private static boolean allowedInTrajectory(FinancialEvent e) {
        if (e.getWishlistStatus() == null) return true;
        return e.getWishlistStatus() == WishlistStatus.FIXED
                && e.getConvertedToEventId() == null && e.getConvertedToFundId() == null;
    }

    /**
     * Потрачено в категории — сумма {@code factAmount} по ВСЕМ событиям, любого вида.
     *
     * <p>ANO-80: фильтр по {@code eventKind == FACT} был неверен. Факт можно внести двумя
     * способами: отдельным событием-ребёнком (у плана меняется только статус) и правкой той
     * же строки плана ({@code PATCH /events/{id}/fact} ставит factAmount и переводит статус).
     * Во втором случае трата живёт на строке вида PLAN, и фильтр по виду её терял — норма не
     * вычитала уже ушедшие деньги и завышала прогноз ровно на них.
     *
     * <p>Так же считает и дашборд в {@code buildProgressBars}: сумма factAmount по всем
     * событиям категории, без разбора вида. Двойного счёта нет — factAmount живёт либо на
     * плане, либо на его факте-ребёнке, но не на обоих.
     */
    private BigDecimal sumFacts(List<FinancialEvent> events) {
        return events.stream()
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumAllPlans(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
