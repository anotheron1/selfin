package ru.selfin.backend.service;

import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Раскладка «item → дневные синтетические события» (спека sandbox §5). Pure, без Spring.
 *
 * <p>Единые правила для примерки (ANO-16, чанк 2) и резервирования FIXED-копилок
 * в реальном кармашке (§6): одна раскладка, два потребителя.
 */
public final class SandboxLayout {

    private SandboxLayout() {}

    /**
     * Максимум месяцев растяжки: следующий месяц..месяц цели включительно.
     * ≤ 0, если цель в текущем месяце или в прошлом (раскладывать некуда).
     */
    public static int maxStretchMonths(LocalDate asOf, LocalDate target) {
        YearMonth from = YearMonth.from(asOf);
        YearMonth to = YearMonth.from(target);
        return (int) from.until(to, java.time.temporal.ChronoUnit.MONTHS);
    }

    /**
     * n равных взносов amount/n (HALF_UP, последний добирает остаток копеек) в первые n
     * подряд месяцев начиная со следующего после asOf («начинаю копить сейчас»).
     * День взноса = первый плановый доход месяца из {@code incomeDates}; нет дохода → 1-е;
     * месяц взноса совпал с месяцем цели → не позже target.
     *
     * @param incomeDates отсортированные даты плановых доходов (PLAN PLANNED INCOME, без хотелок)
     */
    public static List<EventSnapshot> layoutSavings(String description, BigDecimal amount,
            LocalDate target, int n, LocalDate asOf, List<LocalDate> incomeDates,
            SyntheticKind kind) {
        if (n < 1) return List.of();
        BigDecimal monthly = amount.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        YearMonth targetMonth = YearMonth.from(target);

        List<EventSnapshot> out = new ArrayList<>(n);
        BigDecimal paid = BigDecimal.ZERO;
        YearMonth month = YearMonth.from(asOf).plusMonths(1);
        for (int i = 0; i < n; i++, month = month.plusMonths(1)) {
            BigDecimal chunk = (i == n - 1) ? amount.subtract(paid) : monthly; // добор копеек
            paid = paid.add(chunk);
            LocalDate day = contributionDay(month, incomeDates);
            if (month.equals(targetMonth) && day.isAfter(target)) {
                day = target; // взнос не позже покупки (§5)
            }
            out.add(synthetic(description, chunk, day, kind));
        }
        return out;
    }

    /** Разовая примерочная трата: один расход в дату. */
    public static List<EventSnapshot> layoutOneOff(String description, BigDecimal amount,
            LocalDate date) {
        return List.of(synthetic(description, amount, date, SyntheticKind.TRY_ON));
    }

    /**
     * Аннуитетный платёж — единственная формула PMT в проекте
     * ({@link WishlistSimulationService#computeCreditDelta} делегирует сюда).
     */
    public static BigDecimal monthlyPmt(BigDecimal amount, BigDecimal annualRatePct, int termMonths) {
        double monthlyRate = annualRatePct.doubleValue() / 100.0 / 12.0;
        double pmtRaw;
        if (monthlyRate == 0.0) {
            pmtRaw = amount.doubleValue() / termMonths;
        } else {
            double f = Math.pow(1 + monthlyRate, termMonths);
            pmtRaw = amount.doubleValue() * monthlyRate * f / (f - 1);
        }
        return BigDecimal.valueOf(pmtRaw).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Кредит по оси счёта: выдача и покупка взаимно гасятся (нетто 0), остаётся серия
     * PMT-расходов с месяца после покупки, в день числа покупки (кламп по длине месяца,
     * правило day-of-month как в recurring).
     */
    public static List<EventSnapshot> layoutCredit(String description, BigDecimal amount,
            LocalDate date, BigDecimal annualRatePct, int termMonths) {
        if (termMonths <= 0) return List.of();
        BigDecimal pmt = monthlyPmt(amount, annualRatePct, termMonths);
        int day = date.getDayOfMonth();

        List<EventSnapshot> out = new ArrayList<>(termMonths);
        for (int p = 1; p <= termMonths; p++) {
            YearMonth month = YearMonth.from(date).plusMonths(p);
            LocalDate payDay = month.atDay(Math.min(day, month.lengthOfMonth()));
            out.add(synthetic(description, pmt, payDay, SyntheticKind.TRY_ON));
        }
        return out;
    }

    // ── внутреннее ──────────────────────────────────────────────────────────

    /** Первый плановый доход месяца; нет дохода → 1-е число (консервативно). */
    private static LocalDate contributionDay(YearMonth month, List<LocalDate> incomeDates) {
        return incomeDates.stream()
                .filter(d -> YearMonth.from(d).equals(month))
                .min(LocalDate::compareTo)
                .orElse(month.atDay(1));
    }

    /** Синтетика обязана проходить фильтры движка: PLAN(PLANNED), без факта, без wishlist. */
    private static EventSnapshot synthetic(String description, BigDecimal amount,
            LocalDate date, SyntheticKind kind) {
        return new EventSnapshot(null, date, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.MEDIUM, amount, null, null, false,
                description, kind);
    }
}
