package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.AnalyticsReportDto.*;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Сервис аналитики: строит агрегированный отчёт по событиям текущего месяца.
 * <p>
 * Отчёт месяца — план-факт по категориям и разбивка по характеру строк. Кассовый календарь с
 * «мостиком», burn rate и дефицит дохода ушли с Р3 (ANO-47): их не показывал ни один экран.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final FinancialEventRepository eventRepository;

    /**
     * Формирует отчёт за месяц, в котором находится {@code asOfDate}: план-факт по категориям и
     * разбивку по характеру строк.
     *
     * @param asOfDate опорная дата расчёта (обычно сегодня)
     * @return агрегированный {@link AnalyticsReportDto}
     */
    @Transactional(readOnly = true)
    public AnalyticsReportDto getReport(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());
        List<FinancialEvent> monthEvents = monthPlanEvents(monthStart, monthEnd);
        return new AnalyticsReportDto(buildPlanFact(monthEvents), buildPriorityBreakdown(monthEvents));
    }

    /**
     * События периода без строк, которых нет в плане ядра (Р4, ANO-206): обсуждаемая и отложенная
     * хотелки живут на «Хотелках», у сконвертированной деньги несёт созданное.
     */
    private List<FinancialEvent> monthPlanEvents(LocalDate start, LocalDate end) {
        return eventRepository.findAllByDeletedFalseAndDateBetween(start, end).stream()
                .filter(e -> !e.outsideMonthPlan()).toList();
    }

    /**
     * Строит отчёт план-факт по категориям.
     * <p>
     * Агрегирует суммы по {@code categoryName + type},
     * delta = fact - planned (отрицательная = перерасход/недобор).
     *
     * @param events события месяца
     * @return {@link PlanFactReport} с итогами по INCOME и EXPENSE
     */
    private PlanFactReport buildPlanFact(List<FinancialEvent> events) {
        // Ключ: categoryName + "|" + type
        record Key(String name, EventType type) {}

        Map<Key, BigDecimal[]> acc = new LinkedHashMap<>();
        for (FinancialEvent e : events) {
            Key key = new Key(e.getCategory().getName(), e.getType());
            BigDecimal[] sums = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(orZero(e.getPlannedAmount()));
            sums[1] = sums[1].add(orZero(e.getFactAmount()));
        }

        List<CategoryPlanFact> categories = new ArrayList<>();
        BigDecimal totalPlannedIncome = BigDecimal.ZERO;
        BigDecimal totalFactIncome = BigDecimal.ZERO;
        BigDecimal totalPlannedExpense = BigDecimal.ZERO;
        BigDecimal totalFactExpense = BigDecimal.ZERO;

        for (Map.Entry<Key, BigDecimal[]> entry : acc.entrySet()) {
            BigDecimal planned = entry.getValue()[0];
            BigDecimal fact = entry.getValue()[1];
            BigDecimal delta = fact.subtract(planned);
            categories.add(new CategoryPlanFact(entry.getKey().name(), entry.getKey().type().name(), planned, fact, delta));

            if (entry.getKey().type() == EventType.INCOME) {
                totalPlannedIncome = totalPlannedIncome.add(planned);
                totalFactIncome = totalFactIncome.add(fact);
            } else if (entry.getKey().type() == EventType.EXPENSE) {
                // Перевод в копилку — не расход (Р4): иначе «Итого» расходов не сходится ни со
                // строками над ним, ни с «Расходами» журнала.
                totalPlannedExpense = totalPlannedExpense.add(planned);
                totalFactExpense = totalFactExpense.add(fact);
            }
        }

        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        categories.sort((a, b) -> collator.compare(a.categoryName(), b.categoryName()));
        return new PlanFactReport(categories, totalPlannedIncome, totalFactIncome, totalPlannedExpense, totalFactExpense);
    }

    /**
     * Строит разбивку бюджета по приоритетам категорий.
     * <p>
     * Расходы группируются по приоритету (HIGH / MEDIUM / LOW).
     * Доходы не разбиваются по приоритету — суммируется только суммарный фактический доход.
     *
     * @param events события месяца
     * @return {@link AnalyticsReportDto.PriorityBreakdown}
     */
    private AnalyticsReportDto.PriorityBreakdown buildPriorityBreakdown(List<FinancialEvent> events) {
        BigDecimal highPlanned = BigDecimal.ZERO, highFact = BigDecimal.ZERO;
        BigDecimal mediumPlanned = BigDecimal.ZERO, mediumFact = BigDecimal.ZERO;
        BigDecimal lowPlanned = BigDecimal.ZERO, lowFact = BigDecimal.ZERO;
        BigDecimal totalIncomeFact = BigDecimal.ZERO;

        for (FinancialEvent e : events) {
            boolean isPlan = e.getEventKind() == EventKind.PLAN;
            boolean isFact = e.getEventKind() == EventKind.FACT;

            if (e.getType() == EventType.INCOME) {
                if (isFact) totalIncomeFact = totalIncomeFact.add(orZero(e.getFactAmount()));
                continue;
            }
            if (e.getType() == EventType.FUND_TRANSFER) continue; // transfers are not expense priorities
            BigDecimal planned = isPlan ? orZero(e.getPlannedAmount()) : BigDecimal.ZERO;
            BigDecimal fact    = isFact ? orZero(e.getFactAmount())    : BigDecimal.ZERO;
            switch (e.getPriority()) {
                case HIGH   -> { highPlanned = highPlanned.add(planned); highFact = highFact.add(fact); }
                case MEDIUM -> { mediumPlanned = mediumPlanned.add(planned); mediumFact = mediumFact.add(fact); }
                case LOW    -> { lowPlanned = lowPlanned.add(planned); lowFact = lowFact.add(fact); }
            }
        }
        return new AnalyticsReportDto.PriorityBreakdown(
                highPlanned, highFact, mediumPlanned, mediumFact,
                lowPlanned, lowFact, totalIncomeFact);
    }

    /** Возвращает {@code BigDecimal.ZERO} если значение {@code null}. */
    private BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Строит многомесячный отчёт план-факт по категориям.
     * Возвращает строки: итоговые (Доходы / Расходы / Переводы) + категории + «Доходы минус расходы».
     *
     * @param startDate начало периода
     * @param endDate   конец периода
     * @return {@link MultiMonthReportDto}
     */
    @Transactional(readOnly = true)
    public MultiMonthReportDto getMultiMonthReport(LocalDate startDate, LocalDate endDate) {
        List<FinancialEvent> events = monthPlanEvents(startDate, endDate);

        // Build sorted month list
        List<YearMonth> months = new ArrayList<>();
        YearMonth current = YearMonth.from(startDate);
        YearMonth last = YearMonth.from(endDate);
        while (!current.isAfter(last)) {
            months.add(current);
            current = current.plusMonths(1);
        }
        DateTimeFormatter ymFmt = DateTimeFormatter.ofPattern("yyyy-MM");
        List<String> monthLabels = months.stream().map(ym -> ym.format(ymFmt)).toList();

        // Group events by (yearMonth, categoryId)
        record EventKey(YearMonth month, UUID categoryId) {}

        Map<EventKey, List<FinancialEvent>> grouped = events.stream()
                .collect(Collectors.groupingBy(e -> new EventKey(YearMonth.from(e.getDate()), e.getCategory().getId())));

        // Collect category metadata
        Map<UUID, String> categoryNames = events.stream()
                .collect(Collectors.toMap(e -> e.getCategory().getId(), e -> e.getCategory().getName(), (a, b) -> a));
        Map<UUID, CategoryType> categoryTypes = events.stream()
                .collect(Collectors.toMap(e -> e.getCategory().getId(), e -> e.getCategory().getType(), (a, b) -> a));
        Map<UUID, EventType> categoryEventTypes = events.stream()
                .collect(Collectors.toMap(e -> e.getCategory().getId(), e -> e.getType(), (a, b) -> a));

        // Totals accumulators
        Map<String, BigDecimal> totalIncomePlanned = new HashMap<>();
        Map<String, BigDecimal> totalIncomeActual = new HashMap<>();
        Map<String, BigDecimal> totalExpensePlanned = new HashMap<>();
        Map<String, BigDecimal> totalExpenseActual = new HashMap<>();
        Map<String, BigDecimal> totalFundTransferPlanned = new HashMap<>();
        Map<String, BigDecimal> totalFundTransferActual = new HashMap<>();

        // Build per-category rows (sorted alphabetically)
        List<RowDto> categoryRows = new ArrayList<>();
        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        List<UUID> sortedCategories = categoryNames.keySet().stream()
                .sorted((a, b) -> collator.compare(categoryNames.get(a), categoryNames.get(b)))
                .toList();

        for (UUID catId : sortedCategories) {
            EventType evtType = categoryEventTypes.get(catId);
            CategoryType catType = categoryTypes.get(catId);
            List<MonthValueDto> values = new ArrayList<>();

            for (YearMonth ym : months) {
                String monthLabel = ym.format(ymFmt);
                List<FinancialEvent> monthEvents = grouped.getOrDefault(new EventKey(ym, catId), List.of());

                BigDecimal planned = monthEvents.stream()
                        .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal actual = monthEvents.stream()
                        .filter(e -> e.getFactAmount() != null)
                        .map(FinancialEvent::getFactAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                boolean hasAnyFact = monthEvents.stream().anyMatch(e -> e.getFactAmount() != null);

                values.add(new MonthValueDto(monthLabel, planned, hasAnyFact ? actual : null));

                // Accumulate totals
                if (evtType == EventType.INCOME) {
                    totalIncomePlanned.merge(monthLabel, planned, BigDecimal::add);
                    if (hasAnyFact) totalIncomeActual.merge(monthLabel, actual, BigDecimal::add);
                } else if (evtType == EventType.EXPENSE) {
                    totalExpensePlanned.merge(monthLabel, planned, BigDecimal::add);
                    if (hasAnyFact) totalExpenseActual.merge(monthLabel, actual, BigDecimal::add);
                } else if (evtType == EventType.FUND_TRANSFER) {
                    totalFundTransferPlanned.merge(monthLabel, planned, BigDecimal::add);
                    if (hasAnyFact) totalFundTransferActual.merge(monthLabel, actual, BigDecimal::add);
                }
            }
            categoryRows.add(new RowDto(RowType.CATEGORY, categoryNames.get(catId), catType, values));
        }

        // Assemble final result: totals interleaved with their category rows
        List<RowDto> result = new ArrayList<>();
        result.add(buildTotalRow(RowType.TOTAL_INCOME, "Доходы", null, monthLabels, totalIncomePlanned, totalIncomeActual));
        categoryRows.stream().filter(r -> r.categoryType() == CategoryType.INCOME).forEach(result::add);
        result.add(buildTotalRow(RowType.TOTAL_EXPENSE, "Расходы", null, monthLabels, totalExpensePlanned, totalExpenseActual));
        categoryRows.stream().filter(r -> r.categoryType() == CategoryType.EXPENSE).forEach(result::add);
        result.add(buildTotalRow(RowType.TOTAL_FUND_TRANSFER, "Переводы в копилки", null, monthLabels, totalFundTransferPlanned, totalFundTransferActual));

        // Р4 (ANO-206): строка считает ровно то, что написано. «Баланс» в банке — остаток на счёте,
        // а перевод в свою копилку — не расход: переводы стоят своей строкой прямо над ней.
        List<MonthValueDto> netValues = monthLabels.stream().map(m -> {
            BigDecimal plannedNet = totalIncomePlanned.getOrDefault(m, BigDecimal.ZERO)
                    .subtract(totalExpensePlanned.getOrDefault(m, BigDecimal.ZERO));
            BigDecimal actualNet = totalIncomeActual.getOrDefault(m, BigDecimal.ZERO)
                    .subtract(totalExpenseActual.getOrDefault(m, BigDecimal.ZERO));
            boolean hasActual = totalIncomeActual.containsKey(m) || totalExpenseActual.containsKey(m);
            return new MonthValueDto(m, plannedNet, hasActual ? actualNet : null);
        }).toList();
        result.add(new RowDto(RowType.BALANCE, "Доходы минус расходы", null, netValues));

        return new MultiMonthReportDto(monthLabels, result);
    }

    /**
     * Формирует итоговую строку (TOTAL_INCOME / TOTAL_EXPENSE) для мультимесячного отчёта.
     *
     * @param type    тип строки (определяет визуальный стиль на фронте)
     * @param label   человекочитаемая метка («Итого доходы» и т.д.)
     * @param catType тип категории (INCOME / EXPENSE)
     * @param months  список меток месяцев (ключи для плановых/фактических карт)
     * @param planned плановые суммы по месяцам
     * @param actual  фактические суммы по месяцам ({@code null}-значение = факт отсутствует)
     * @return строка отчёта с помесячными значениями
     */
    private RowDto buildTotalRow(RowType type, String label, CategoryType catType,
                                  List<String> months,
                                  Map<String, BigDecimal> planned, Map<String, BigDecimal> actual) {
        List<MonthValueDto> values = months.stream().map(m ->
                new MonthValueDto(m,
                        planned.getOrDefault(m, BigDecimal.ZERO),
                        actual.containsKey(m) ? actual.get(m) : null)
        ).toList();
        return new RowDto(type, label, catType, values);
    }
}
