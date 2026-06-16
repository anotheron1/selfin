package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;
import ru.selfin.backend.dto.wishlist.RecomputeRequestDto;
import ru.selfin.backend.dto.wishlist.RecomputeResponseDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;
import ru.selfin.backend.dto.wishlist.WishlistConstraintsDto;
import ru.selfin.backend.dto.wishlist.WishlistItemDto;
import ru.selfin.backend.dto.wishlist.WishlistSimulationDto;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Считает влияние (delta-вектор) каждого wishlist-item'а на горизонт месяцев.
 * Чистая математика в статических методах — переиспользуется и для GET /simulation,
 * и для наложения FIXED-items в {@link StrategyTimelineService}.
 *
 * <p>Соглашение об индексах: {@code monthIndex = 0} соответствует {@code current + 1}.
 * Item с целевой датой в {@code current + N} месяцев → {@code monthIndex = N - 1}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class WishlistSimulationService {

    private final BaselineTimelineBuilder baselineBuilder;
    private final FinancialEventRepository eventRepository;
    private final TargetFundRepository fundRepository;
    private final UserSettingsService userSettingsService;
    private final CapitalService capitalService;

    /** Результат расчёта копилки: delta + выведенный месячный взнос. */
    public record SavingsResult(List<MonthDeltaDto> delta, BigDecimal monthlyContribution) {}
    /** Результат расчёта кредита: delta + выведенный месячный платёж (PMT). */
    public record CreditResult(List<MonthDeltaDto> delta, BigDecimal monthlyPMT) {}

    // ====== Instance methods (stateful, use repos) ======

    /**
     * Полный ответ GET /api/v1/wishlist/simulation.
     * Собирает baseline, items, thresholds, constraints.
     */
    public WishlistSimulationDto getSimulation(int horizonMonths) {
        TimelineSnapshot snap = baselineBuilder.build(horizonMonths, true);
        YearMonth current = snap.currentMonth();

        StrategyTimelineDto baselineDto = new StrategyTimelineDto(
                snap.firstMonth(), current, snap.horizonEnd(),
                snap.predictionWindowMonths(), snap.fanEnabled(), snap.points());

        // Collect all wishlist events and funds, drop DISMISSED
        List<FinancialEvent> wishlistEvents = eventRepository.findAllWishlistEvents().stream()
                .filter(e -> e.getWishlistStatus() != WishlistStatus.DISMISSED)
                .toList();
        List<TargetFund> wishlistFunds = fundRepository.findAllWishlistFunds().stream()
                .filter(f -> f.getWishlistStatus() != WishlistStatus.DISMISSED)
                .toList();

        List<WishlistItemDto> items = new ArrayList<>();
        for (FinancialEvent e : wishlistEvents) {
            items.add(mapEventToItem(e, current, horizonMonths));
        }
        for (TargetFund f : wishlistFunds) {
            items.add(mapFundToItem(f, current, horizonMonths));
        }

        WishlistThresholdsDto thresholds = userSettingsService.getWishlistSettings();
        WishlistConstraintsDto constraints = computeConstraints(current);

        return new WishlistSimulationDto(baselineDto, items, thresholds, constraints);
    }

    /**
     * Пересчитывает delta-вектор одного item'а по «черновым» параметрам слайдеров
     * (без сохранения). Диспетчеризует по {@code kind} на статические compute*Delta-хелперы.
     * Горизонт фиксирован 36 месяцев (как и дефолт {@link #getSimulation(int)}).
     *
     * @param req kind + сумма + дата (+ ставка/срок для CREDIT)
     * @return delta + выведенные monthlyContribution (SAVINGS) / monthlyPMT (CREDIT)
     */
    public RecomputeResponseDto recomputeItemDelta(RecomputeRequestDto req) {
        YearMonth current = YearMonth.now();
        int horizonMonths = 36;
        BigDecimal amount = req.amount() != null ? req.amount() : BigDecimal.ZERO;
        String kind = req.kind();

        if ("CREDIT".equals(kind)) {
            BigDecimal rate = req.rate() != null ? req.rate() : BigDecimal.ZERO;
            int term = req.termMonths() != null ? req.termMonths() : 0;
            CreditResult cr = computeCreditDelta(amount, req.targetDate(), current, horizonMonths, rate, term);
            return new RecomputeResponseDto(cr.delta(), null, cr.monthlyPMT());
        }
        if ("SAVINGS".equals(kind)) {
            SavingsResult sr = computeSavingsDelta(amount, req.targetDate(), current, horizonMonths);
            return new RecomputeResponseDto(sr.delta(), sr.monthlyContribution(), null);
        }
        // WISHLIST (default): single-month outflow.
        List<MonthDeltaDto> delta = computeWishlistDelta(amount, req.targetDate(), current, horizonMonths);
        return new RecomputeResponseDto(delta, null, null);
    }

    /**
     * Суммарный delta-вектор FIXED WISHLIST-items БЕЗ конверсии, для наложения на /strategy timeline.
     *
     * <p>Funds are real pockets already represented in the baseline's liquidAt; overlaying their
     * synthetic delta would double-count. WISHLIST LOW events contribute nothing to the baseline
     * (no planned event/FACT), so only they are overlaid here. The /wishlist simulation page still
     * models funds fully — that's its sandbox purpose.
     *
     * <p>Поэтому здесь собираются ТОЛЬКО WISHLIST-события (LOW-хотелки) и исключаются все
     * TargetFund-производные items (SAVINGS/CREDIT).
     */
    public List<MonthDeltaDto> computeDeltaForFixedItems(YearMonth current, int horizonMonths) {
        List<FinancialEvent> fixedEvents = eventRepository
                .findByWishlistStatusAndDeletedFalse(WishlistStatus.FIXED).stream()
                .filter(e -> e.getConvertedToEventId() == null
                        && e.getConvertedToFundId() == null
                        && e.getDate() != null)
                .toList();

        List<MonthDeltaDto> all = new ArrayList<>();
        for (FinancialEvent e : fixedEvents) {
            if (e.getPlannedAmount() == null) continue;
            all.addAll(computeWishlistDelta(e.getPlannedAmount(), e.getDate(), current, horizonMonths));
        }
        return all;
    }

    // ====== Private mapping helpers ======

    private WishlistItemDto mapEventToItem(FinancialEvent e, YearMonth current, int horizonMonths) {
        BigDecimal amount = e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO;
        List<MonthDeltaDto> delta = (e.getDate() != null)
                ? computeWishlistDelta(amount, e.getDate(), current, horizonMonths)
                : List.of();
        WishlistItemDto.ConvertedToDto convertedTo = buildConvertedTo(e.getConvertedToEventId(), e.getConvertedToFundId());
        String name = (e.getDescription() != null && !e.getDescription().isBlank())
                ? e.getDescription()
                : (e.getCategory() != null ? e.getCategory().getName() : "");
        return new WishlistItemDto(
                e.getId(),
                "WISHLIST",
                name,
                amount,
                e.getDate(),
                e.getWishlistStatus() != null ? e.getWishlistStatus().name() : null,
                convertedTo,
                delta,
                e.getCategory() != null ? e.getCategory().getId() : null,
                null, null, null, null
        );
    }

    private WishlistItemDto mapFundToItem(TargetFund f, YearMonth current, int horizonMonths) {
        String kind = f.getPurchaseType() == FundPurchaseType.CREDIT ? "CREDIT" : "SAVINGS";
        BigDecimal amount = f.getTargetAmount() != null ? f.getTargetAmount() : BigDecimal.ZERO;

        List<MonthDeltaDto> delta;
        BigDecimal monthlyContrib = null;
        BigDecimal monthlyPmt = null;

        if (f.getTargetDate() != null) {
            if (f.getPurchaseType() == FundPurchaseType.CREDIT
                    && f.getCreditRate() != null && f.getCreditTermMonths() != null) {
                CreditResult cr = computeCreditDelta(amount, f.getTargetDate(), current, horizonMonths,
                        f.getCreditRate(), f.getCreditTermMonths());
                delta = cr.delta();
                monthlyPmt = cr.monthlyPMT();
            } else {
                SavingsResult sr = computeSavingsDelta(amount, f.getTargetDate(), current, horizonMonths);
                delta = sr.delta();
                monthlyContrib = sr.monthlyContribution();
            }
        } else {
            delta = List.of();
        }

        WishlistItemDto.ConvertedToDto convertedTo = buildConvertedTo(f.getConvertedToEventId(), f.getConvertedToFundId());

        return new WishlistItemDto(
                f.getId(),
                kind,
                f.getName(),
                amount,
                f.getTargetDate(),
                f.getWishlistStatus() != null ? f.getWishlistStatus().name() : null,
                convertedTo,
                delta,
                null,
                monthlyContrib,
                f.getCreditRate(),
                f.getCreditTermMonths(),
                monthlyPmt
        );
    }

    private WishlistItemDto.ConvertedToDto buildConvertedTo(java.util.UUID eventId, java.util.UUID fundId) {
        if (eventId != null) return new WishlistItemDto.ConvertedToDto("EVENT", eventId);
        if (fundId != null) return new WishlistItemDto.ConvertedToDto("FUND", fundId);
        return null;
    }

    private WishlistConstraintsDto computeConstraints(YearMonth current) {
        // 6-month window of facts for averages
        LocalDate from = current.minusMonths(6).atDay(1);
        LocalDate to = current.minusMonths(1).atEndOfMonth();
        List<FinancialEvent> recentFacts = eventRepository.findFactsByDateRange(from, to);

        BigDecimal totalIncome = recentFacts.stream()
                .filter(e -> e.getType() == EventType.INCOME)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpense = recentFacts.stream()
                .filter(e -> e.getType() == EventType.EXPENSE)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Divide by actual months with data, not a hard 6: onboarding users with <6 months of facts
        // would otherwise see understated averages. Clamp to [1,6] to avoid div-by-zero / inflation.
        long actualMonthsWithData = recentFacts.stream()
                .filter(e -> e.getDate() != null)
                .map(e -> YearMonth.from(e.getDate()))
                .distinct()
                .count();
        long divisor = Math.min(6, Math.max(1, actualMonthsWithData));

        BigDecimal monthlyIncomeAvg = totalIncome.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
        BigDecimal monthlyExpenseAvg = totalExpense.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
        BigDecimal currentCapital = capitalService.liquidAt(LocalDate.now());

        // Max wishlist: 6 months income
        BigDecimal maxWishlist = monthlyIncomeAvg.multiply(BigDecimal.valueOf(6));
        // Max credit: 3x capital or 36 months income
        BigDecimal maxCredit = currentCapital.multiply(BigDecimal.valueOf(3))
                .max(monthlyIncomeAvg.multiply(BigDecimal.valueOf(36)));

        return new WishlistConstraintsDto(monthlyExpenseAvg, monthlyIncomeAvg,
                currentCapital, maxWishlist, maxCredit);
    }

    // ====== Static math methods (pure, no Spring) ======

    /**
     * Разовая хотелка: один отток в месяц целевой даты. Уменьшает счёт и капитал на сумму.
     * Возвращает пустой список, если дата в прошлом или за горизонтом.
     */
    public static List<MonthDeltaDto> computeWishlistDelta(
            BigDecimal amount, LocalDate targetDate, YearMonth current, int horizonMonths) {
        int idx = monthIndexOf(targetDate, current);
        if (idx < 0 || idx >= horizonMonths) return List.of();
        List<MonthDeltaDto> out = new ArrayList<>(1);
        out.add(new MonthDeltaDto(idx, amount.negate(), amount.negate(), null, null));
        return out;
    }

    /** monthIndex для targetDate: (current+1)=0. Возвращает -1, если targetDate раньше current+1. */
    static int monthIndexOf(LocalDate targetDate, YearMonth current) {
        YearMonth target = YearMonth.from(targetDate);
        int diff = (target.getYear() - current.getYear()) * 12
                + (target.getMonthValue() - current.getMonthValue());
        return diff - 1;   // current+1 → 0
    }

    /**
     * Копилка: равномерные взносы КАЖДЫЙ месяц 0..purchaseIdx (включительно = purchaseIdx+1 месяцев),
     * в последний месяц — покупка.
     *
     * <p>account (расчётный счёт) и capital (чистая стоимость) — независимые оси; копилка-pocket
     * выступает посредником и видна только в tooltip:
     * <ul>
     *   <li>месяцы 0..purchaseIdx-1: account −= monthly, fund += monthly, capital = 0
     *       (деньги переехали в копилку, всё ещё мои);</li>
     *   <li>месяц purchaseIdx: account −= monthly (последний взнос), capital −= amount (потребление),
     *       fund += (monthly − amount) (копилка наполнилась и потрачена).</li>
     * </ul>
     * Итог: account падает на amount равномерно за (purchaseIdx+1) месяцев; capital падает на amount
     * в месяц покупки. monthly = amount / (purchaseIdx + 1).
     *
     * <p>purchaseIdx == 0 (цель в current+1, нет времени копить) → одна запись: account −amount,
     * capital −amount (вырождается в разовый отток). Возвращает пустой список, если дата в прошлом
     * или за горизонтом.
     */
    public static SavingsResult computeSavingsDelta(
            BigDecimal amount, LocalDate targetDate, YearMonth current, int horizonMonths) {
        int purchaseIdx = monthIndexOf(targetDate, current);
        if (purchaseIdx < 0 || purchaseIdx >= horizonMonths) {
            return new SavingsResult(List.of(), BigDecimal.ZERO);
        }
        if (purchaseIdx == 0) {
            // Нет времени копить — разовый отток.
            return new SavingsResult(
                    List.of(new MonthDeltaDto(0, amount.negate(), amount.negate(), BigDecimal.ZERO, null)),
                    amount);
        }
        int contribMonths = purchaseIdx + 1;   // взносы в месяцах 0..purchaseIdx включительно
        BigDecimal monthly = amount.divide(BigDecimal.valueOf(contribMonths), 2, java.math.RoundingMode.HALF_UP);

        List<MonthDeltaDto> out = new ArrayList<>();
        for (int i = 0; i < purchaseIdx; i++) {
            out.add(new MonthDeltaDto(i, monthly.negate(), BigDecimal.ZERO, monthly, null));
        }
        // Месяц покупки: последний взнос + потребление.
        out.add(new MonthDeltaDto(purchaseIdx, monthly.negate(), amount.negate(),
                monthly.subtract(amount), null));
        return new SavingsResult(out, monthly);
    }

    /**
     * Кредит: в месяц покупки сумма зачисляется на счёт (account +amount) и появляется
     * обязательство (liability +amount, capital неизменен — актив компенсирует долг).
     * Далее аннуитетный PMT каждый месяц: account -PMT, principalPart гасит долг
     * (liability -principalPart), capital растёт на principalPart (долг тает).
     * Серия PMT обрезается по горизонту.
     */
    public static CreditResult computeCreditDelta(
            BigDecimal amount, LocalDate targetDate, YearMonth current, int horizonMonths,
            BigDecimal annualRatePct, int termMonths) {
        int purchaseIdx = monthIndexOf(targetDate, current);
        // Symmetric with savings: a purchase outside the horizon yields no delta AND no PMT.
        if (purchaseIdx < 0 || purchaseIdx >= horizonMonths) return new CreditResult(List.of(), BigDecimal.ZERO);
        // Defensive: a malformed credit with non-positive term would divide by zero below.
        if (termMonths <= 0) return new CreditResult(List.of(), BigDecimal.ZERO);

        double monthlyRate = annualRatePct.doubleValue() / 100.0 / 12.0;
        double pmtRaw;
        if (monthlyRate == 0.0) {
            pmtRaw = amount.doubleValue() / termMonths;
        } else {
            double f = Math.pow(1 + monthlyRate, termMonths);
            pmtRaw = amount.doubleValue() * monthlyRate * f / (f - 1);
        }
        BigDecimal pmt = BigDecimal.valueOf(pmtRaw).setScale(2, java.math.RoundingMode.HALF_UP);

        List<MonthDeltaDto> out = new ArrayList<>();
        // purchaseIdx is guaranteed in [0, horizonMonths) by the guard above.
        out.add(new MonthDeltaDto(purchaseIdx, amount, BigDecimal.ZERO, null, amount));
        double remaining = amount.doubleValue();
        for (int p = 1; p <= termMonths; p++) {
            int idx = purchaseIdx + p;
            if (idx >= horizonMonths) break;
            double interest = remaining * monthlyRate;
            double principal = pmtRaw - interest;
            remaining -= principal;
            BigDecimal principalBd = BigDecimal.valueOf(principal).setScale(2, java.math.RoundingMode.HALF_UP);
            out.add(new MonthDeltaDto(
                    idx,
                    pmt.negate(),
                    principalBd,                 // capital grows as debt shrinks
                    null,
                    principalBd.negate()         // liability shrinks
            ));
        }
        return new CreditResult(out, pmt);
    }
}
