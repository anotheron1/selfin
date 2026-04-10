package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.AnalyticsReportDto.*;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Сервис аналитики: строит агрегированный отчёт по событиям текущего месяца.
 * <p>
 * Отчёт включает четыре раздела:
 * <ol>
 *   <li>Кассовый календарь — нарастающий баланс по дням месяца</li>
 *   <li>Отчёт план-факт — по категориям с группировкой INCOME / EXPENSE</li>
 *   <li>Burn rate обязательных трат — с разбивкой по неделям</li>
 *   <li>Дефицит цели дохода — план vs факт по всем доходным событиям</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;

    /** Горизонт кассового календаря: количество дней вперёд от текущей даты. */
    private static final int CASH_FLOW_HORIZON_DAYS = 14;

    /**
     * Формирует полный аналитический отчёт за месяц, в котором находится {@code asOfDate}.
     * Прошлые дни используют фактические суммы (при наличии), будущие — плановые.
     * <p>
     * Кассовый календарь расширяется на {@value #CASH_FLOW_HORIZON_DAYS} дней вперёд
     * от {@code asOfDate}, при необходимости захватывая события следующего месяца.
     * Остальные разделы отчёта (план-факт, burn rate, income gap) остаются в рамках месяца.
     *
     * @param asOfDate опорная дата расчёта (обычно сегодня)
     * @return агрегированный {@link AnalyticsReportDto}
     */
    @Transactional(readOnly = true)
    public AnalyticsReportDto getReport(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());
        LocalDate calendarEnd = asOfDate.plusDays(CASH_FLOW_HORIZON_DAYS);
        if (calendarEnd.isBefore(monthEnd)) {
            calendarEnd = monthEnd;
        }

        List<FinancialEvent> monthEvents = eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

        // Для кассового календаря: если горизонт выходит за пределы месяца,
        // подгружаем события следующего месяца и объединяем
        List<FinancialEvent> cashFlowEvents;
        if (calendarEnd.isAfter(monthEnd)) {
            List<FinancialEvent> extraEvents = eventRepository
                    .findAllByDeletedFalseAndDateBetween(monthEnd.plusDays(1), calendarEnd);
            cashFlowEvents = new ArrayList<>(monthEvents);
            cashFlowEvents.addAll(extraEvents);
        } else {
            cashFlowEvents = monthEvents;
        }

        BigDecimal initialBalance = calcStartBalance(monthStart);

        return new AnalyticsReportDto(
                buildCashFlow(cashFlowEvents, monthStart, calendarEnd, asOfDate, initialBalance),
                buildPlanFact(monthEvents),
                buildMandatoryBurnRate(monthEvents, monthStart, monthEnd),
                buildIncomeGap(monthEvents),
                buildPriorityBreakdown(monthEvents));
    }

    /**
     * Строит кассовый календарь — список дней с нарастающим балансом.
     * <p>
     * Нарастающий баланс стартует от {@code initialBalance} — реального остатка на счёте
     * на начало месяца, рассчитанного через {@link #calcStartBalance(LocalDate)}.
     * <p>
     * Для каждого дня вычисляется {@code dailyIncome} и {@code dailyExpense}:
     * прошлые дни используют {@code factAmount} (при отсутствии — {@code plannedAmount}),
     * будущие — только {@code plannedAmount}.
     * Дни без событий скрываются, за исключением сегодняшнего дня.
     * <p>
     * Диапазон дат может выходить за пределы текущего месяца —
     * параметр {@code endDate} задаётся горизонтом кассового календаря
     * ({@value #CASH_FLOW_HORIZON_DAYS} дней вперёд от текущей даты).
     *
     * @param events         события за весь диапазон {@code [monthStart, endDate]} (без удалённых)
     * @param monthStart     первый день месяца (начало нарастающего баланса)
     * @param endDate        последний день календаря (может быть за пределами месяца)
     * @param today          сегодняшняя дата (граница прошлое / будущее)
     * @param initialBalance начальный баланс на начало месяца из чекпоинта
     * @return упорядоченный список {@link CashFlowDay} от {@code monthStart} до {@code endDate}
     */
    private List<CashFlowDay> buildCashFlow(List<FinancialEvent> events,
                                             LocalDate monthStart, LocalDate endDate,
                                             LocalDate today, BigDecimal initialBalance) {
        // Фильтр EventKind (PLAN vs FACT) здесь не нужен: расширенный диапазон содержит
        // только будущие дни, у которых factAmount = null, поэтому двойного счёта нет.
        // Группируем события по дате
        Map<LocalDate, List<FinancialEvent>> byDate = events.stream()
                .collect(Collectors.groupingBy(FinancialEvent::getDate));

        List<CashFlowDay> days = new ArrayList<>();
        BigDecimal running = initialBalance;

        for (LocalDate d = monthStart; !d.isAfter(endDate); d = d.plusDays(1)) {
            boolean isFuture = d.isAfter(today);
            List<FinancialEvent> dayEvents = byDate.getOrDefault(d, List.of());

            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;

            for (FinancialEvent e : dayEvents) {
                BigDecimal amount = effectiveAmount(e, isFuture);
                if (amount == null) continue;
                if (e.getType() == EventType.INCOME) {
                    income = income.add(amount);
                } else {
                    expense = expense.add(amount);
                }
            }

            running = running.add(income).subtract(expense);
            // Показываем день только если были события или это сегодня
            if (income.compareTo(BigDecimal.ZERO) != 0
                    || expense.compareTo(BigDecimal.ZERO) != 0
                    || d.equals(today)) {
                days.add(new CashFlowDay(d, income, expense, running, isFuture, running.compareTo(BigDecimal.ZERO) < 0));
            }
        }

        return days;
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
            } else {
                totalPlannedExpense = totalPlannedExpense.add(planned);
                totalFactExpense = totalFactExpense.add(fact);
            }
        }

        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        categories.sort((a, b) -> collator.compare(a.categoryName(), b.categoryName()));
        return new PlanFactReport(categories, totalPlannedIncome, totalFactIncome, totalPlannedExpense, totalFactExpense);
    }

    /**
     * Строит burn rate обязательных расходов с разбивкой по неделям текущего месяца.
     * <p>
     * Учитываются только события с {@code mandatory = true} и {@code type = EXPENSE}.
     * Месяц делится на недели по ISO-правилу (понедельник — начало недели),
     * но границы обрезаются по первому и последнему дню месяца.
     *
     * @param events     события месяца
     * @param monthStart первый день месяца
     * @param monthEnd   последний день месяца
     * @return {@link MandatoryBurnRate} с общими итогами и разбивкой по неделям
     */
    private MandatoryBurnRate buildMandatoryBurnRate(List<FinancialEvent> events,
                                                      LocalDate monthStart, LocalDate monthEnd) {
        List<FinancialEvent> mandatory = events.stream()
                .filter(e -> e.getPriority() == Priority.HIGH && e.getType() == EventType.EXPENSE)
                .toList();

        // Разбиваем месяц на недели (пн–вс), обрезая по границам месяца
        List<WeekBurnRate> byWeek = new ArrayList<>();
        WeekFields wf = WeekFields.ISO;
        LocalDate weekStart = monthStart;
        int weekNum = 1;

        while (!weekStart.isAfter(monthEnd)) {
            // Конец недели = воскресенье текущей недели, но не позже конца месяца
            LocalDate weekEnd = weekStart.with(wf.dayOfWeek(), 7);
            if (weekEnd.isAfter(monthEnd)) weekEnd = monthEnd;

            final LocalDate ws = weekStart;
            final LocalDate we = weekEnd;

            BigDecimal planned = BigDecimal.ZERO;
            BigDecimal fact = BigDecimal.ZERO;
            for (FinancialEvent e : mandatory) {
                if (!e.getDate().isBefore(ws) && !e.getDate().isAfter(we)) {
                    planned = planned.add(orZero(e.getPlannedAmount()));
                    fact = fact.add(orZero(e.getFactAmount()));
                }
            }

            byWeek.add(new WeekBurnRate(weekNum, ws, we, planned, fact));
            weekNum++;
            weekStart = weekEnd.plusDays(1);
        }

        BigDecimal totalPlanned = mandatory.stream().map(e -> orZero(e.getPlannedAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFact = mandatory.stream().map(e -> orZero(e.getFactAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new MandatoryBurnRate(totalPlanned, totalFact, byWeek);
    }

    /**
     * Строит анализ дефицита доходов.
     * <p>
     * Суммирует все плановые и фактические доходы за месяц.
     * delta = factIncome - plannedIncome; отрицательная дельта = недобор.
     *
     * @param events события месяца
     * @return {@link IncomeGap}
     */
    private IncomeGap buildIncomeGap(List<FinancialEvent> events) {
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal fact = BigDecimal.ZERO;

        for (FinancialEvent e : events) {
            if (e.getType() == EventType.INCOME) {
                planned = planned.add(orZero(e.getPlannedAmount()));
                fact = fact.add(orZero(e.getFactAmount()));
            }
        }

        return new IncomeGap(planned, fact, fact.subtract(planned));
    }

    /**
     * Вычисляет начальный баланс на начало месяца {@code monthStart}
     * на основе последнего {@code BalanceCheckpoint}.
     * <p>
     * Алгоритм:
     * <ol>
     *   <li>Если чекпоинт отсутствует — возвращает ноль (обратная совместимость).</li>
     *   <li>Если чекпоинт за текущий или более поздний месяц — возвращает сумму чекпоинта как есть.</li>
     *   <li>Если чекпоинт из прошлого месяца — суммирует «мостик» событий
     *       от даты чекпоинта до последнего дня предыдущего месяца включительно.</li>
     * </ol>
     *
     * @param monthStart первый день целевого месяца
     * @return накопленный баланс на начало месяца
     */
    private BigDecimal calcStartBalance(LocalDate monthStart) {
        Optional<BalanceCheckpoint> latestCheckpoint = checkpointRepository.findTopByOrderByDateDesc();
        if (latestCheckpoint.isEmpty()) return BigDecimal.ZERO;

        BalanceCheckpoint cp = latestCheckpoint.get();
        BigDecimal startBalance = cp.getAmount();

        if (cp.getDate().isBefore(monthStart)) {
            // Чекпоинт из прошлого месяца — суммируем «мостик» событий
            // от даты чекпоинта до конца предыдущего месяца
            List<FinancialEvent> bridgeEvents = eventRepository
                    .findAllByDeletedFalseAndDateBetween(cp.getDate(), monthStart.minusDays(1));
            startBalance = startBalance.add(effectiveNetSum(bridgeEvents));
        }
        return startBalance;
    }

    /**
     * Знаковая сумма события: доход — положительная, расход — отрицательная.
     * Приоритет суммы: фактическая, если задана; иначе — плановая.
     *
     * @param e финансовое событие
     * @return знаковая сумма; {@code BigDecimal.ZERO} если обе суммы {@code null}
     */
    private BigDecimal signedAmount(FinancialEvent e) {
        BigDecimal amount = e.getFactAmount() != null ? e.getFactAmount() : e.getPlannedAmount();
        if (amount == null) return BigDecimal.ZERO;
        return e.getType() == EventType.INCOME ? amount : amount.negate();
    }

    /**
     * V12-совместимая суммарная знаковая сумма списка событий.
     * Пропускает PLAN(EXECUTED) — их вклад уже учтён через FACT-записи.
     *
     * @param events список событий (может содержать и PLAN, и FACT записи)
     * @return алгебраическая сумма знаковых сумм без двойного учёта
     * @see #signedAmount(FinancialEvent)
     */
    private BigDecimal effectiveNetSum(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> !(e.getEventKind() == EventKind.PLAN && e.getStatus() == EventStatus.EXECUTED))
                .map(this::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Возвращает эффективную сумму события: для прошлых дней — факт (или план при отсутствии),
     * для будущих — только план.
     *
     * @param event    финансовое событие
     * @param isFuture {@code true} если дата события в будущем
     * @return сумма или {@code null} если ни один из вариантов недоступен
     */
    private BigDecimal effectiveAmount(FinancialEvent event, boolean isFuture) {
        if (isFuture) return event.getPlannedAmount();
        // Для прошлых дней берём только факт: неисполненные события не влияют на реальный баланс.
        // Это согласует нарастающий баланс кассового календаря с currentBalance на дашборде.
        return event.getFactAmount();
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
     * Возвращает строки: итоговые (Доходы / Расходы / Переводы) + категории + Баланс.
     *
     * @param startDate начало периода
     * @param endDate   конец периода
     * @return {@link MultiMonthReportDto}
     */
    @Transactional(readOnly = true)
    public MultiMonthReportDto getMultiMonthReport(LocalDate startDate, LocalDate endDate) {
        List<FinancialEvent> events = eventRepository.findAllByDeletedFalseAndDateBetween(startDate, endDate);

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

        // Balance row: income - expense - fund_transfer
        List<MonthValueDto> balanceValues = monthLabels.stream().map(m -> {
            BigDecimal plannedBalance = totalIncomePlanned.getOrDefault(m, BigDecimal.ZERO)
                    .subtract(totalExpensePlanned.getOrDefault(m, BigDecimal.ZERO))
                    .subtract(totalFundTransferPlanned.getOrDefault(m, BigDecimal.ZERO));
            BigDecimal actualBalance = totalIncomeActual.getOrDefault(m, BigDecimal.ZERO)
                    .subtract(totalExpenseActual.getOrDefault(m, BigDecimal.ZERO))
                    .subtract(totalFundTransferActual.getOrDefault(m, BigDecimal.ZERO));
            boolean hasActual = totalIncomeActual.containsKey(m) || totalExpenseActual.containsKey(m);
            return new MonthValueDto(m, plannedBalance, hasActual ? actualBalance : null);
        }).toList();
        result.add(new RowDto(RowType.BALANCE, "Баланс", null, balanceValues));

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
