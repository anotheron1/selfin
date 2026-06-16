package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Координатор стратегической шкалы. Базовый timeline собирает {@link BaselineTimelineBuilder};
 * поверх него накладываются delta зафиксированных (FIXED) хотелок.
 *
 * <p>applyDeltas: deltas are per-month FLOWS with RUNNING SUMS. Maintain runAccount/runCapital;
 * for the point at month-offset k (where k=1 is current+1, i.e. monthIndex=k-1), first accumulate
 * all deltas with monthIndex == k-1 into the running totals, then add them to that point's fields.
 * This means a single outflow at monthIndex=2 lowers every point from current+3 onward.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StrategyTimelineService {

    private final BaselineTimelineBuilder baselineBuilder;
    private final WishlistSimulationService wishlistSimulationService;

    public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown) {
        TimelineSnapshot snap = baselineBuilder.build(horizonMonths, withBreakdown);
        List<MonthDeltaDto> fixedDeltas = wishlistSimulationService
                .computeDeltaForFixedItems(snap.currentMonth(), horizonMonths);
        List<StrategyTimelinePointDto> overlaid = applyDeltas(snap.points(), snap.currentMonth(), fixedDeltas);
        return new StrategyTimelineDto(
                snap.firstMonth(),
                snap.currentMonth(),
                snap.horizonEnd(),
                snap.predictionWindowMonths(),
                snap.fanEnabled(),
                overlaid
        );
    }

    /**
     * Накладывает delta-векторы на точки timeline.
     *
     * <p>Семантика: deltas — это потоки (flows), а не одноразовые сдвиги.
     * Поддерживаем running totals runAccount и runCapital. Для точки с k-м смещением
     * (k=1 → current+1, monthIndex=k-1): сначала накапливаем все дельты с monthIndex=k-1,
     * затем прибавляем итоговые суммы к balance/balanceConfirmed/balanceLow/balanceHigh/capital.
     * Прошлые и текущая точки не затрагиваются.
     */
    private List<StrategyTimelinePointDto> applyDeltas(
            List<StrategyTimelinePointDto> points,
            YearMonth currentMonth,
            List<MonthDeltaDto> deltas) {
        if (deltas.isEmpty()) return points;

        BigDecimal runAccount = BigDecimal.ZERO;
        BigDecimal runCapital = BigDecimal.ZERO;

        List<StrategyTimelinePointDto> result = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            // Only apply to future points
            if (!p.yearMonth().isAfter(currentMonth)) {
                result.add(p);
                continue;
            }
            // k = month offset from current (current+1 → k=1, monthIndex=k-1=0)
            int k = (p.yearMonth().getYear() - currentMonth.getYear()) * 12
                    + (p.yearMonth().getMonthValue() - currentMonth.getMonthValue());
            int monthIndex = k - 1;
            // Accumulate all deltas for this month
            for (MonthDeltaDto d : deltas) {
                if (d.monthIndex() == monthIndex) {
                    if (d.accountDelta() != null) runAccount = runAccount.add(d.accountDelta());
                    if (d.capitalDelta() != null) runCapital = runCapital.add(d.capitalDelta());
                }
            }
            // Apply running totals
            result.add(new StrategyTimelinePointDto(
                    p.yearMonth(), p.phase(),
                    p.balance() != null ? p.balance().add(runAccount) : null,
                    p.income(), p.expense(), p.nettoFlow(),
                    p.balanceConfirmed() != null ? p.balanceConfirmed().add(runAccount) : null,
                    p.balanceLow() != null ? p.balanceLow().add(runAccount) : null,
                    p.balanceHigh() != null ? p.balanceHigh().add(runAccount) : null,
                    p.capital() != null ? p.capital().add(runCapital) : null,
                    p.assets(), p.liabilities(),
                    p.breakdown()
            ));
        }
        return result;
    }
}
