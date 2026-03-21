package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Аналитический сервис: синхронная агрегация данных для Дашборда.
 *
 * <h3>Архитектурные принципы</h3>
 * <ul>
 *   <li>CQRS и кэширование не применяются — производительности PostgreSQL на данных
 *       одного пользователя достаточно.</li>
 *   <li>Стартовая точка расчёта — последний {@link BalanceCheckpoint} по дате.
 *       Если чекпоинта нет — баланс считается от нуля (обратная совместимость).</li>
 *   <li>Прошлые PLANNED-события (без factAmount) в баланс не входят:
 *       деньги не потрачены — в балансе не убываем.</li>
 * </ul>
 *
 * <h3>Двойной зарплатный горизонт</h3>
 * Сервис ищет два ближайших неисполненных INCOME-события в пределах 70 дней и вычисляет:
 * <ol>
 *   <li>{@code balanceBeforeNextSalary} — баланс в последний день перед первой зп
 *       («低шая точка» текущего периода; показывает, насколько туго будет).</li>
 *   <li>{@code balanceAfterNextSalary} — баланс в конце дня получения первой зп
 *       (стартовый капитал следующего периода).</li>
 *   <li>{@code balanceBeforeSecondSalary} — баланс в последний день перед второй зп
 *       («низшая точка» следующего периода).</li>
 * </ol>
 * Горизонт обнаружения кассового разрыва расширяется до второй зп, чтобы
 * отрицательный баланс между первой и второй зп был виден на дашборде.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    /** Максимальный горизонт поиска зп-событий и кассового разрыва (в днях). */
    static final int FORECAST_HORIZON_DAYS = 70;

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;

    /**
     * Собирает данные главного дашборда для даты {@code asOfDate}.
     *
     * <h4>Шаги расчёта:</h4>
     * <ol>
     *   <li><b>Стартовая точка</b> — последний {@code BalanceCheckpoint}.
     *       Если чекпоинт до начала текущего месяца — добавляется «мостик» событий.</li>
     *   <li><b>Текущий баланс (факт)</b> — чекпоинт + только ИСПОЛНЕННЫЕ события
     *       (с фактической суммой) от даты чекпоинта до {@code asOfDate} включительно.</li>
     *   <li><b>Прогноз конец месяца</b> — текущий баланс + плановые суммы
     *       неисполненных событий от сегодня до конца месяца.</li>
     *   <li><b>Два зарплатных горизонта</b> — поиск двух ближайших неисполненных
     *       INCOME-событий и расчёт трёх прогнозных точек (до/после/до2).</li>
     *   <li><b>Кассовый разрыв</b> — первый день с отрицательным нарастающим балансом
     *       в расширенном горизонте (до второй зп или конца месяца).</li>
     *   <li><b>Прогресс-бары</b> — план vs факт по расходным категориям за весь месяц.</li>
     * </ol>
     *
     * @param asOfDate дата расчёта; обычно «сегодня»
     * @return агрегированный DTO для рендеринга дашборда
     */
    public DashboardDto getDashboard(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());

        // --- 1. Определяем стартовую точку по последнему чекпоинту ---
        Optional<BalanceCheckpoint> latestCheckpoint = checkpointRepository.findTopByOrderByDateDesc();

        BigDecimal startBalance = BigDecimal.ZERO;
        LocalDate effectiveStart = monthStart;

        if (latestCheckpoint.isPresent()) {
            BalanceCheckpoint cp = latestCheckpoint.get();
            startBalance = cp.getAmount();

            if (cp.getDate().isBefore(monthStart)) {
                // Чекпоинт из прошлого месяца — суммируем «мостик» событий
                // от даты чекпоинта до конца предыдущего месяца
                List<FinancialEvent> bridgeEvents = eventRepository
                        .findAllByDeletedFalseAndDateBetween(cp.getDate(), monthStart.minusDays(1));
                startBalance = startBalance.add(netSum(bridgeEvents));
                effectiveStart = monthStart;
            } else {
                effectiveStart = cp.getDate();
            }
        }

        // --- 2. События текущего месяца (для прогресс-баров и прогноза EOM) ---
        List<FinancialEvent> monthEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

        // --- 3. Текущий баланс (факт) = стартовый баланс + только ИСПОЛНЕННЫЕ события до asOfDate ---
        // PLANNED-события (без factAmount) не учитываются: деньги не потрачены — баланс не убываем.
        final LocalDate start = effectiveStart;
        BigDecimal currentBalance = startBalance.add(
                monthEvents.stream()
                        .filter(e -> !e.getDate().isBefore(start) && !e.getDate().isAfter(asOfDate))
                        .filter(e -> e.getFactAmount() != null)
                        .map(this::signedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        // --- 4. Прогноз конец месяца = факт + плановые суммы ещё не исполненных событий (сегодня и далее) ---
        BigDecimal forecastDelta = monthEvents.stream()
                .filter(e -> !e.getDate().isBefore(asOfDate))
                .filter(e -> e.getFactAmount() == null)
                .map(this::plannedSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal endOfMonthForecast = currentBalance.add(forecastDelta);

        // --- 5. Два зарплатных горизонта (кросс-месячный, до 70 дней) ---
        LocalDate horizonEnd = asOfDate.plusDays(FORECAST_HORIZON_DAYS);
        List<FinancialEvent> horizonEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(asOfDate, horizonEnd);

        // Первые два неисполненных INCOME-события строго в будущем (не сегодня)
        List<LocalDate> salaryDates = horizonEvents.stream()
                .filter(e -> e.getType() == EventType.INCOME && e.getFactAmount() == null)
                .filter(e -> e.getDate().isAfter(asOfDate))
                .map(FinancialEvent::getDate)
                .distinct()
                .sorted()
                .limit(2)
                .toList();

        LocalDate nextSalaryDate = salaryDates.size() >= 1 ? salaryDates.get(0) : null;
        LocalDate secondSalaryDate = salaryDates.size() >= 2 ? salaryDates.get(1) : null;

        BigDecimal balanceBeforeNextSalary = null;
        BigDecimal balanceAfterNextSalary = null;
        BigDecimal balanceBeforeSecondSalary = null;

        if (nextSalaryDate != null) {
            balanceBeforeNextSalary = calcBalanceBefore(horizonEvents, currentBalance, asOfDate, nextSalaryDate);
            balanceAfterNextSalary = calcBalanceAfter(horizonEvents, balanceBeforeNextSalary, nextSalaryDate);

            if (secondSalaryDate != null) {
                balanceBeforeSecondSalary = calcBalanceBefore(
                        horizonEvents, balanceAfterNextSalary, nextSalaryDate, secondSalaryDate);
            }
        }

        // --- 6. Кассовый разрыв — горизонт расширен до второй зп или конца месяца ---
        // Если есть вторая зп — смотрим до неё включительно, иначе до первой зп, иначе конец месяца.
        LocalDate gapHorizon = secondSalaryDate != null ? secondSalaryDate
                : nextSalaryDate != null ? nextSalaryDate
                : monthEnd;
        DashboardDto.CashGapAlert cashGapAlert = detectCashGap(horizonEvents, currentBalance, asOfDate, gapHorizon);

        // --- 7. Прогресс-бары по категориям (всегда за весь месяц) ---
        List<DashboardDto.CategoryProgressBar> progressBars = buildProgressBars(monthEvents);

        return new DashboardDto(
                currentBalance,
                endOfMonthForecast,
                nextSalaryDate,
                balanceBeforeNextSalary,
                balanceAfterNextSalary,
                secondSalaryDate,
                balanceBeforeSecondSalary,
                cashGapAlert,
                progressBars);
    }

    /**
     * Вычисляет прогнозный баланс в последний день <em>перед</em> датой {@code salaryDate}.
     *
     * <p>Алгоритм: берёт {@code startBalance} и прибавляет знаковые плановые суммы всех
     * неисполненных событий в диапазоне {@code (from, salaryDate)} — от {@code from}
     * включительно до {@code salaryDate} не включительно. Это «низшая точка» периода:
     * именно столько останется на счёте прямо перед поступлением зарплаты.
     *
     * @param events       список событий из расширенного горизонта
     * @param startBalance баланс, от которого строим прогноз
     * @param from         начало диапазона (включительно)
     * @param salaryDate   дата зарплаты (не включается в диапазон)
     * @return прогнозный баланс накануне {@code salaryDate}
     */
    private BigDecimal calcBalanceBefore(List<FinancialEvent> events, BigDecimal startBalance,
            LocalDate from, LocalDate salaryDate) {
        BigDecimal delta = events.stream()
                .filter(e -> !e.getDate().isBefore(from) && e.getDate().isBefore(salaryDate))
                .filter(e -> e.getFactAmount() == null)
                .map(this::plannedSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return startBalance.add(delta);
    }

    /**
     * Вычисляет прогнозный баланс в конце дня {@code salaryDate} (включая само поступление
     * зарплаты и все прочие неисполненные события того же дня).
     *
     * <p>Результат — «стартовый капитал» следующего периода: столько будет доступно
     * сразу после получения зп, до начала расходов следующего цикла.
     *
     * @param events              список событий из расширенного горизонта
     * @param balanceBeforeSalary баланс накануне {@code salaryDate} (результат {@link #calcBalanceBefore})
     * @param salaryDate          дата получения зарплаты
     * @return прогнозный баланс в конце дня {@code salaryDate}
     */
    private BigDecimal calcBalanceAfter(List<FinancialEvent> events, BigDecimal balanceBeforeSalary,
            LocalDate salaryDate) {
        BigDecimal deltaOnDay = events.stream()
                .filter(e -> e.getDate().equals(salaryDate))
                .filter(e -> e.getFactAmount() == null)
                .map(this::plannedSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return balanceBeforeSalary.add(deltaOnDay);
    }

    /**
     * Знаковая сумма события: доход — положительная, расход/перевод — отрицательная.
     * Приоритет: факт, если задан; иначе — план.
     *
     * @param e финансовое событие
     * @return знаковая сумма (может быть нулём если обе суммы {@code null})
     */
    private BigDecimal signedAmount(FinancialEvent e) {
        BigDecimal amount = e.getFactAmount() != null ? e.getFactAmount() : e.getPlannedAmount();
        if (amount == null) return BigDecimal.ZERO;
        return e.getType() == EventType.INCOME ? amount : amount.negate();
    }

    /**
     * Знаковая плановая сумма события (только {@code plannedAmount}, факт игнорируется).
     * Используется для прогнозов по ещё не исполненным событиям.
     *
     * @param e финансовое событие
     * @return знаковая плановая сумма (нуль если {@code plannedAmount == null})
     */
    private BigDecimal plannedSignedAmount(FinancialEvent e) {
        BigDecimal amount = e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO;
        return e.getType() == EventType.INCOME ? amount : amount.negate();
    }

    /**
     * Суммарная знаковая сумма списка событий (факт приоритетнее плана).
     *
     * @param events список событий
     * @return алгебраическая сумма знаковых сумм
     */
    private BigDecimal netSum(List<FinancialEvent> events) {
        return events.stream().map(this::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Обнаруживает первый день после {@code from}, в котором нарастающий баланс
     * уходит в минус при последовательном применении сумм событий.
     *
     * <p>Для каждого события используется факт если он есть, иначе план.
     * Перебор идёт по дням: {@code from + 1 day} → {@code until}.
     *
     * @param events       список событий (могут выходить за пределы {@code until} — лишние фильтруются)
     * @param startBalance текущий баланс на дату {@code from}
     * @param from         начало диапазона (не включается в проверку)
     * @param until        конец диапазона (включительно)
     * @return алерт с датой и суммой первого кассового разрыва, или {@code null} если разрывов нет
     */
    private DashboardDto.CashGapAlert detectCashGap(
            List<FinancialEvent> events, BigDecimal startBalance,
            LocalDate from, LocalDate until) {

        BigDecimal runningBalance = startBalance;
        LocalDate current = from.plusDays(1);

        Map<LocalDate, List<FinancialEvent>> byDate = events.stream()
                .filter(e -> e.getDate().isAfter(from) && !e.getDate().isAfter(until))
                .collect(Collectors.groupingBy(FinancialEvent::getDate));

        while (!current.isAfter(until)) {
            List<FinancialEvent> dayEvents = byDate.getOrDefault(current, List.of());
            for (FinancialEvent e : dayEvents) {
                BigDecimal amount = e.getFactAmount() != null ? e.getFactAmount()
                        : e.getPlannedAmount() != null ? e.getPlannedAmount()
                        : BigDecimal.ZERO;
                runningBalance = e.getType() == EventType.INCOME
                        ? runningBalance.add(amount)
                        : runningBalance.subtract(amount);
            }
            if (runningBalance.compareTo(BigDecimal.ZERO) < 0) {
                return new DashboardDto.CashGapAlert(current, runningBalance);
            }
            current = current.plusDays(1);
        }
        return null;
    }

    /**
     * Строит прогресс-бары план/факт по категориям расходов за месяц.
     *
     * <p>Для каждой категории суммируются все плановые и фактические суммы событий
     * типа {@link EventType#EXPENSE} за месяц. Процент = {@code fact / plan * 100},
     * может превышать 100 (перерасход). Список сортируется по имени категории
     * в русском алфавитном порядке.
     *
     * @param events события текущего месяца (могут включать INCOME — они фильтруются)
     * @return список прогресс-баров, отсортированных по имени категории
     */
    private List<DashboardDto.CategoryProgressBar> buildProgressBars(List<FinancialEvent> events) {
        Map<String, List<FinancialEvent>> byCategory = events.stream()
                .filter(e -> e.getType() == EventType.EXPENSE)
                .collect(Collectors.groupingBy(e -> e.getCategory().getName()));

        List<DashboardDto.CategoryProgressBar> bars = new ArrayList<>();
        for (Map.Entry<String, List<FinancialEvent>> entry : byCategory.entrySet()) {
            BigDecimal planned = entry.getValue().stream()
                    .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fact = entry.getValue().stream()
                    .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int pct = planned.compareTo(BigDecimal.ZERO) == 0 ? 0
                    : fact.multiply(BigDecimal.valueOf(100))
                            .divide(planned, 0, RoundingMode.HALF_UP).intValue();
            bars.add(new DashboardDto.CategoryProgressBar(entry.getKey(), fact, planned, pct));
        }
        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        bars.sort((a, b) -> collator.compare(a.categoryName(), b.categoryName()));
        return bars;
    }
}
