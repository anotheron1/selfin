package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.pocket.SandboxRef;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    /** ANO-39: «сегодня» приходит извне — иначе календарную логику не проверить детерминированно. */
    private final Clock clock;

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
        // Р1: основа — ядро без зафиксированного, дельты включённых фронт кладёт сверху
        // (composeTimeline). При открытии блока включено всё зафиксированное, и основа плюс его
        // дельты — ровно ядро, то есть линия «Стратегии». Взять ядро основой как есть значило бы
        // посчитать зафиксированное дважды (ANO-142). Разбивка основы блоку не нужна.
        TimelineSnapshot snap = baselineBuilder.build(horizonMonths, false);
        YearMonth current = snap.currentMonth();

        StrategyTimelineDto baselineDto = new StrategyTimelineDto(
                snap.firstMonth(), current, snap.horizonEnd(),
                snap.predictionWindowMonths(), snap.fanEnabled(), withoutFixed(snap.points(), snap.heldByRef()));

        // ANO-107: отложенные тоже — с пустой дельтой (mapEventToItem, mapFundToItem). Раньше их
        // выбрасывали, и разделу «Отложено» неоткуда было их взять: «Отложить» было дорогой в один
        // конец. В расчёт они по-прежнему не входят — I5 спеки 29.05.
        List<FinancialEvent> wishlistEvents = eventRepository.findAllWishlistEvents();
        List<TargetFund> wishlistFunds = fundRepository.findAllWishlistFunds();

        List<WishlistItemDto> items = new ArrayList<>();
        for (FinancialEvent e : wishlistEvents) {
            items.add(mapEventToItem(e, current, horizonMonths, snap.heldByRef().get(SandboxRef.event(e.getId()))));
        }
        for (TargetFund f : wishlistFunds) {
            items.add(mapFundToItem(f, current, horizonMonths, snap.heldByRef().get(SandboxRef.fund(f.getId()))));
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
        if (req.targetDate() == null || req.kind() == null || req.amount() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "kind, amount, targetDate are required");
        }
        YearMonth current = YearMonth.now(clock);
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
     * Суммарный delta-вектор FIXED WISHLIST-items БЕЗ конверсии, для наложения на капитал /strategy.
     *
     * <p>Счёт такая хотелка уже двигает в остатке Стратегии — его держит ядро (ANO-108, Р1). Капитал
     * за планами не следует, поэтому Стратегия берёт отсюда только капитал.
     *
     * <p>Копилки здесь не собираются: их взносы остаток Стратегии держит через ядро, а покупку на
     * дату цели график капитала Стратегии не показывает — это вопрос капитала, а не остатка (карта
     * C1). Блок «Что с капиталом» накладывает и капитал копилок — там это примерка.
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

    // ====== Основа и зафиксированное из ядра (Р1) ======

    /**
     * Основа «Что с капиталом» — ядро без зафиксированного: всё, что ядро держит по ссылкам
     * примерки, возвращается в остаток с того месяца, где держалось, и дальше.
     */
    static List<StrategyTimelinePointDto> withoutFixed(List<StrategyTimelinePointDto> points,
                                                      Map<SandboxRef, Map<YearMonth, BigDecimal>> heldByRef) {
        Map<YearMonth, BigDecimal> heldPerMonth = new TreeMap<>();
        heldByRef.values().forEach(byMonth -> byMonth.forEach((ym, amount) ->
                heldPerMonth.merge(ym, amount, BigDecimal::add)));

        List<StrategyTimelinePointDto> base = new ArrayList<>(points.size());
        BigDecimal returned = BigDecimal.ZERO;
        for (StrategyTimelinePointDto p : points) {
            if (p.phase() == StrategyPointPhase.PAST) {
                base.add(p);
                continue;
            }
            BigDecimal month = heldPerMonth.getOrDefault(p.yearMonth(), BigDecimal.ZERO);
            returned = returned.add(month);
            base.add(new StrategyTimelinePointDto(
                    p.yearMonth(), p.phase(),
                    p.balance().add(returned), p.income(), p.expense().subtract(month), p.nettoFlow().add(month),
                    p.balanceConfirmed().add(returned), p.balanceLow().add(returned), p.balanceHigh().add(returned),
                    p.capital(), p.assets(), p.liabilities(),
                    p.breakdown()));
        }
        return base;
    }

    /**
     * Дельта зафиксированного, которое держит ядро: по оси счёта — ровно его строки в ядре, по оси
     * капитала — прежняя формула. Месяцы раньше первой будущей точки (текущий) ложатся в неё: её
     * остаток их уже содержит. Иначе основа без зафиксированного плюс дельта не дали бы ядро.
     */
    static List<MonthDeltaDto> heldOnAccount(List<MonthDeltaDto> formula, Map<YearMonth, BigDecimal> held,
                                             YearMonth current) {
        Map<Integer, MonthDeltaDto> byIndex = new TreeMap<>();
        for (MonthDeltaDto d : formula) {
            byIndex.merge(d.monthIndex(), new MonthDeltaDto(d.monthIndex(), BigDecimal.ZERO,
                    d.capitalDelta(), d.fundDelta(), d.liabilityDelta()), WishlistSimulationService::plus);
        }
        held.forEach((ym, amount) -> {
            int idx = Math.max(0, monthIndexOf(ym.atDay(1), current));
            byIndex.merge(idx, new MonthDeltaDto(idx, amount.negate(), BigDecimal.ZERO, null, null),
                    WishlistSimulationService::plus);
        });
        return List.copyOf(byIndex.values());
    }

    private static MonthDeltaDto plus(MonthDeltaDto a, MonthDeltaDto b) {
        return new MonthDeltaDto(a.monthIndex(), a.accountDelta().add(b.accountDelta()),
                a.capitalDelta().add(b.capitalDelta()),
                sumOrNull(a.fundDelta(), b.fundDelta()), sumOrNull(a.liabilityDelta(), b.liabilityDelta()));
    }

    private static BigDecimal sumOrNull(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        return b == null ? a : a.add(b);
    }

    // ====== Private mapping helpers ======

    /** @param held сколько держит ядро по месяцам; {@code null} — хотелки в ядре нет */
    private WishlistItemDto mapEventToItem(FinancialEvent e, YearMonth current, int horizonMonths,
                                           Map<YearMonth, BigDecimal> held) {
        BigDecimal amount = e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO;
        // ANO-142: у сконвертированной хотелки деньги несёт артефакт — план уже в baseline.
        // Дельта сверху посчитала бы её второй раз.
        boolean converted = e.getConvertedToEventId() != null || e.getConvertedToFundId() != null;
        // ANO-107: отложенная приходит для раздела «Отложено», но в расчёт не входит.
        boolean dismissed = e.getWishlistStatus() == WishlistStatus.DISMISSED;
        List<MonthDeltaDto> delta = (e.getDate() != null && !converted && !dismissed)
                ? computeWishlistDelta(amount, e.getDate(), current, horizonMonths)
                : List.of();
        if (held != null) delta = heldOnAccount(delta, held, current);
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

    /** @param held сколько держит ядро по месяцам; {@code null} — копилки в ядре нет */
    private WishlistItemDto mapFundToItem(TargetFund f, YearMonth current, int horizonMonths,
                                          Map<YearMonth, BigDecimal> held) {
        String kind = f.getPurchaseType() == FundPurchaseType.CREDIT ? "CREDIT" : "SAVINGS";
        BigDecimal amount = f.getTargetAmount() != null ? f.getTargetAmount() : BigDecimal.ZERO;

        List<MonthDeltaDto> delta;
        BigDecimal monthlyContrib = null;
        BigDecimal monthlyPmt = null;

        // ANO-142: у сконвертированной хотелки деньги несёт артефакт — копилка из конверсии со своей
        // дельтой. Дельта исходной посчитала бы покупку второй раз.
        boolean converted = f.getConvertedToEventId() != null || f.getConvertedToFundId() != null;
        // ANO-107: отложенная приходит для раздела «Отложено», но в расчёт не входит.
        boolean dismissed = f.getWishlistStatus() == WishlistStatus.DISMISSED;
        if (f.getTargetDate() != null && !converted && !dismissed) {
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
        if (held != null) {
            delta = heldOnAccount(delta, held, current);
            // Взнос на карточке — тот, что держит ядро: остаток до цели поровну, а не вся цель.
            if (!held.isEmpty()) monthlyContrib = held.values().iterator().next();
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
        // cashLiquidAt, а не liquidAt: вклад в ограничения хотелок не входит (ANO-46). Потолок
        // кредита от этого падает — банк вклад учёл бы, а мы нет; принято осознанно, чтобы
        // «распечатать вклад» оставалось решением пользователя, а не молчаливым допущением.
        BigDecimal currentCapital = capitalService.cashLiquidAt(LocalDate.now(clock));

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

        // Единственная формула PMT в проекте — SandboxLayout.monthlyPmt (ANO-16 §5).
        double monthlyRate = annualRatePct.doubleValue() / 100.0 / 12.0;
        BigDecimal pmt = SandboxLayout.monthlyPmt(amount, annualRatePct, termMonths);
        double pmtRaw = pmt.doubleValue();

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
