package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;

import java.math.BigDecimal;
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

    /** Результат расчёта копилки: delta + выведенный месячный взнос. */
    public record SavingsResult(List<MonthDeltaDto> delta, BigDecimal monthlyContribution) {}
    /** Результат расчёта кредита: delta + выведенный месячный платёж (PMT). */
    public record CreditResult(List<MonthDeltaDto> delta, BigDecimal monthlyPMT) {}

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
        if (purchaseIdx < 0) return new CreditResult(List.of(), BigDecimal.ZERO);

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
        if (purchaseIdx < horizonMonths) {
            out.add(new MonthDeltaDto(purchaseIdx, amount, BigDecimal.ZERO, null, amount));
        }
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
