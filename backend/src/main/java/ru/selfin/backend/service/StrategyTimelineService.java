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
 * Координатор стратегической шкалы. Базовый timeline собирает {@link BaselineTimelineBuilder}:
 * зафиксированная хотелка в нём — обычный план, в балансе, расходе и разбивке месяца один раз.
 * Поверх накладывается только её след на капитале.
 *
 * <p>ANO-108: раньше наложение шло и на баланс, а baseline уже держал хотелку планом — зафиксированная
 * хотелка на 50 000 роняла баланс на 100 000.
 *
 * <p>applyDeltas: deltas are per-month FLOWS with RUNNING SUMS. Maintain runCapital;
 * for the point at month-offset k (where k=1 is current+1, i.e. monthIndex=k-1), first accumulate
 * all deltas with monthIndex == k-1 into the running total, then add it to that point's capital.
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
        TimelineSnapshot snap = baselineBuilder.build(horizonMonths, withBreakdown,
                BaselineTimelineBuilder.Wishlist.FIXED_AS_PLAN);
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
     * Накладывает на точки timeline след зафиксированных хотелок на капитале.
     *
     * <p>Только капитал: баланс хотелку уже держит — она план baseline. Будущий капитал baseline —
     * последний известный, планы его не двигают, поэтому покупку хотелки на графике капитала
     * показывает только наложение.
     *
     * <p>Семантика: deltas — это потоки (flows), а не одноразовые сдвиги. Поддерживаем running total
     * runCapital. Для точки с k-м смещением (k=1 → current+1, monthIndex=k-1): сначала накапливаем
     * все дельты с monthIndex=k-1, затем прибавляем итог к capital. Прошлые и текущая точки не
     * затрагиваются.
     */
    private List<StrategyTimelinePointDto> applyDeltas(
            List<StrategyTimelinePointDto> points,
            YearMonth currentMonth,
            List<MonthDeltaDto> deltas) {
        if (deltas.isEmpty()) return points;

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
                if (d.monthIndex() == monthIndex && d.capitalDelta() != null) {
                    runCapital = runCapital.add(d.capitalDelta());
                }
            }
            // Apply running total
            result.add(new StrategyTimelinePointDto(
                    p.yearMonth(), p.phase(),
                    p.balance(), p.income(), p.expense(), p.nettoFlow(),
                    p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                    p.capital() != null ? p.capital().add(runCapital) : null,
                    p.assets(), p.liabilities(),
                    p.breakdown()
            ));
        }
        return result;
    }
}
