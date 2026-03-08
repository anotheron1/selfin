package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Аналитический сервис: синхронная агрегация данных для Дашборда.
 * CQRS и кэширование не применяются — скорость PostgreSQL на данных
 * пользователя достаточна.
 *
 * <p>Все вычисления производятся в рамках одного SELECT за текущий месяц,
 * что минимизирует round-trips к БД.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final FinancialEventRepository eventRepository;

    /**
     * Собирает данные главного дашборда для даты {@code asOfDate}.
     * Алгоритм за один проход по событиям месяца вычисляет:
     * <ol>
     *   <li>Текущий баланс — факт доходов минус факт расходов по дату включительно.
     *       Если факт отсутствует — берётся план.</li>
     *   <li>Прогноз конца месяца — текущий баланс плюс плановые суммы будущих событий.</li>
     *   <li>Первый день потенциального кассового разрыва (или {@code null}).</li>
     *   <li>Прогресс-бары расходов по категориям: план vs факт.</li>
     * </ol>
     *
     * @param asOfDate дата расчёта; обычно "сегодня", но может быть любой датой в месяце
     * @return агрегированный DTO для рендеринга дашборда
     */
    public DashboardDto getDashboard(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());

        List<FinancialEvent> monthEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

        // --- 1. Текущий баланс (факт: доходы - расходы до asOfDate включительно) ---
        BigDecimal currentBalance = monthEvents.stream()
                .filter(e -> !e.getDate().isAfter(asOfDate))
                .map(e -> {
                    BigDecimal amount = e.getFactAmount() != null ? e.getFactAmount() : e.getPlannedAmount();
                    if (amount == null)
                        return BigDecimal.ZERO;
                    return e.getType() == EventType.INCOME ? amount : amount.negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // --- 2. Прогноз на конец месяца (план оставшихся + текущий баланс) ---
        BigDecimal forecastDelta = monthEvents.stream()
                .filter(e -> e.getDate().isAfter(asOfDate))
                .map(e -> {
                    BigDecimal amount = e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO;
                    return e.getType() == EventType.INCOME ? amount : amount.negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal endOfMonthForecast = currentBalance.add(forecastDelta);

        // --- 3. Кассовый разрыв: ищем ближайший день с отрицательным нарастающим балансом ---
        DashboardDto.CashGapAlert cashGapAlert = detectCashGap(monthEvents, currentBalance, asOfDate, monthEnd);

        // --- 4. Прогресс-бары по категориям расходов ---
        List<DashboardDto.CategoryProgressBar> progressBars = buildProgressBars(monthEvents);

        return new DashboardDto(currentBalance, endOfMonthForecast, cashGapAlert, progressBars);
    }

    /**
     * Обнаруживает первый день после {@code from}, в котором нарастающий баланс
     * уходит в минус при последовательном применении плановых сумм событий.
     *
     * <p>Алгоритм: стартует с {@code startBalance}, затем день за днём применяет
     * плановые расходы и доходы из {@code [from+1, until]}. При первом отрицательном
     * значении возвращает {@link DashboardDto.CashGapAlert} с датой и дефицитом.
     *
     * @param events       все события текущего месяца
     * @param startBalance текущий баланс на дату {@code from}
     * @param from         дата "сегодня" (не включается в перебор)
     * @param until        последний день месяца (включается)
     * @return алерт с датой и суммой разрыва, или {@code null} если разрыва нет
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
                BigDecimal amount = e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO;
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
     * Строит список прогресс-баров плана/факта по категориям расходов за месяц.
     * Группирует расходные события по имени категории, суммирует план и факт,
     * вычисляет процент исполнения {@code fact/plan * 100} (округление к ближайшему целому).
     * Если плановая сумма равна нулю — процент считается равным 0.
     *
     * @param events все события текущего месяца (доходы фильтруются внутри метода)
     * @return список прогресс-баров; пустой список если расходных событий нет
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
        return bars;
    }
}
