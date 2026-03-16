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
 * CQRS и кэширование не применяются — скорость PostgreSQL на данных
 * пользователя достаточна.
 *
 * <p>Стартовая точка расчёта — последний {@code BalanceCheckpoint} по дате.
 * Если чекпоинта нет — баланс считается от нуля (обратная совместимость).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;

    /**
     * Собирает данные главного дашборда для даты {@code asOfDate}.
     * <ol>
     *   <li><b>Текущий баланс (факт)</b> — чекпоинт + события от даты чекпоинта до asOfDate.
     *       Для каждого события: если факт есть — берём факт, иначе план.</li>
     *   <li><b>Прогноз на конец месяца</b> — текущий баланс + плановые суммы будущих событий.</li>
     *   <li>Первый день потенциального кассового разрыва.</li>
     *   <li>Прогресс-бары расходов по категориям (всегда за текущий месяц целиком).</li>
     * </ol>
     *
     * @param asOfDate дата расчёта; обычно "сегодня"
     * @return агрегированный DTO для рендеринга дашборда
     */
    public DashboardDto getDashboard(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());

        // --- 1. Определяем стартовую точку по последнему чекпоинту ---
        Optional<BalanceCheckpoint> latestCheckpoint = checkpointRepository.findTopByOrderByDateDesc();

        BigDecimal startBalance = BigDecimal.ZERO;
        LocalDate effectiveStart = monthStart; // по умолчанию — начало месяца

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

        // --- 2. События текущего месяца (для прогресс-баров и кассового разрыва) ---
        List<FinancialEvent> monthEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

        // --- 3. Текущий баланс (факт) = стартовый баланс + только ИСПОЛНЕННЫЕ события до asOfDate ---
        // PLANNED-события (без factAmount) не учитываются: деньги не потрачены = в балансе не убываем.
        final LocalDate start = effectiveStart;
        BigDecimal currentBalance = startBalance.add(
                monthEvents.stream()
                        .filter(e -> !e.getDate().isBefore(start) && !e.getDate().isAfter(asOfDate))
                        .filter(e -> e.getFactAmount() != null)   // только исполненные факты
                        .map(this::signedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        // --- 4. Прогноз на конец месяца = факт + плановые суммы ещё не исполненных событий (сегодня и далее) ---
        BigDecimal forecastDelta = monthEvents.stream()
                .filter(e -> !e.getDate().isBefore(asOfDate))    // сегодня и будущие (не только isAfter)
                .filter(e -> e.getFactAmount() == null)           // только не исполненные
                .map(e -> {
                    BigDecimal amount = e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO;
                    return e.getType() == EventType.INCOME ? amount : amount.negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal endOfMonthForecast = currentBalance.add(forecastDelta);

        // --- 5. Кассовый разрыв ---
        DashboardDto.CashGapAlert cashGapAlert = detectCashGap(monthEvents, currentBalance, asOfDate, monthEnd);

        // --- 6. Прогресс-бары по категориям (всегда за весь месяц) ---
        List<DashboardDto.CategoryProgressBar> progressBars = buildProgressBars(monthEvents);

        return new DashboardDto(currentBalance, endOfMonthForecast, cashGapAlert, progressBars);
    }

    /**
     * Знаковая сумма события: доход — положительная, расход/перевод — отрицательная.
     * Приоритет: факт, если задан; иначе — план.
     */
    private BigDecimal signedAmount(FinancialEvent e) {
        BigDecimal amount = e.getFactAmount() != null ? e.getFactAmount() : e.getPlannedAmount();
        if (amount == null) return BigDecimal.ZERO;
        return e.getType() == EventType.INCOME ? amount : amount.negate();
    }

    /** Суммарная знаковая сумма списка событий. */
    private BigDecimal netSum(List<FinancialEvent> events) {
        return events.stream().map(this::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Обнаруживает первый день после {@code from}, в котором нарастающий баланс
     * уходит в минус при последовательном применении плановых сумм событий.
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
     * Строит прогресс-бары план/факт по категориям расходов за месяц.
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
