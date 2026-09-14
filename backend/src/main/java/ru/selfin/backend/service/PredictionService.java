package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public MonthlyForecastDto forecastFromEvents(List<FinancialEvent> monthEvents, LocalDate today) {
        List<CategoryForecastDto> forecasts = new ArrayList<>();
        BigDecimal netDelta = BigDecimal.ZERO;

        for (Category cat : categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse()) {
            List<FinancialEvent> catEvents = monthEvents.stream()
                    .filter(e -> !e.isDeleted())
                    .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                    .toList();

            BigDecimal fact = sumFacts(catEvents);
            BigDecimal pendingPlans = sumPendingPlans(catEvents);
            BigDecimal median = medianIfTrusted(cat);

            BigDecimal beyondPlan = median.subtract(fact).subtract(pendingPlans).max(BigDecimal.ZERO);
            BigDecimal projection = median.max(fact.add(pendingPlans));

            forecasts.add(new CategoryForecastDto(cat.getName(), fact, sumAllPlans(catEvents),
                    projection, beyondPlan, buildHistory(catEvents, median, pendingPlans, today)));
            netDelta = netDelta.add(beyondPlan);
        }

        return new MonthlyForecastDto(forecasts, netDelta);
    }

    /** Медиана категории либо ноль, если месяцев наблюдения меньше порога. */
    private BigDecimal medianIfTrusted(Category cat) {
        CategoryMonthStats stats = getStatsForCategory(cat, HISTORY_WINDOW_MONTHS);
        return stats.monthsOfHistory() >= MIN_HISTORY_MONTHS ? stats.median() : BigDecimal.ZERO;
    }

    /**
     * Compute forecasts fetching events from DB. Use for standalone /forecast endpoint.
     */
    public MonthlyForecastDto forecastMonth(YearMonth month, LocalDate today) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<FinancialEvent> events = eventRepository.findAllByDeletedFalseAndDateBetween(start, end);
        return forecastFromEvents(events, today);
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
     * <p>Фильтр событий — тот же что в {@link #sumFacts}: {@code eventKind = FACT, deleted = false}.
     * {@code EventStatus} не учитывается (все FACT-события — учётные транзакции).
     *
     * <p>Если {@code monthsOfHistory < MIN_HISTORY_MONTHS}, caller не должен учитывать
     * категорию — но median всё равно вычисляется.
     *
     * <p>Percentile-вычисление — линейная интерполяция между соседними точками отсортированного массива.
     */
    public CategoryMonthStats getStatsForCategory(Category cat, int historyWindowMonths) {
        LocalDate today = LocalDate.now(clock);
        YearMonth lastFull = YearMonth.from(today).minusMonths(1);

        LocalDate firstFact = eventRepository.findFirstFactDate();
        if (firstFact == null) {
            return new CategoryMonthStats(cat.getId(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // ANO-80. Месяц первого факта отбрасывается ВСЕГДА, даже если факт пришёлся на первое
        // число: запись первого числа так же может быть занесена задним числом, как и любая
        // другая. Правило не пытается угадать, вёлся ли учёт с начала месяца, — и потому
        // проверяется тестом без оговорок. Замерено: неполный первый месяц занижал медианы
        // на 13–27 % (эталонный стенд, «Авто» 44 379 → 38 598, «Медицина» 16 615 → 12 076).
        YearMonth firstObserved = YearMonth.from(firstFact).plusMonths(1);
        YearMonth windowStart = lastFull.minusMonths(historyWindowMonths - 1L);
        if (windowStart.isBefore(firstObserved)) windowStart = firstObserved;
        if (windowStart.isAfter(lastFull)) {
            return new CategoryMonthStats(cat.getId(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<YearMonth> observedMonths = new ArrayList<>();
        for (YearMonth m = windowStart; !m.isAfter(lastFull); m = m.plusMonths(1)) {
            observedMonths.add(m);
        }

        Map<YearMonth, BigDecimal> monthlyTotals = eventRepository
                .findFactsByDateRange(windowStart.atDay(1), lastFull.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                .collect(Collectors.groupingBy(
                        e -> YearMonth.from(e.getDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO,
                                BigDecimal::add)
                ));

        // Месяц наблюдения без траты в категории — полноценный ноль в ряду, а не пропуск.
        // Без этого «Одежда», покупаемая раз в квартал, закладывалась бы каждый месяц целиком:
        // на стенде такая категория давала медиану 5 283 вместо честного нуля.
        List<BigDecimal> sorted = observedMonths.stream()
                .map(m -> monthlyTotals.getOrDefault(m, BigDecimal.ZERO))
                .sorted()
                .toList();

        return new CategoryMonthStats(
                cat.getId(),
                observedMonths.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.25),
                percentile(sorted, 0.75)
        );
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
    private BigDecimal sumPendingPlans(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getFactAmount() == null)
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .filter(e -> e.getStatus() == EventStatus.PLANNED)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
