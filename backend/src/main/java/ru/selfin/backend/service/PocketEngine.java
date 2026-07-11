package ru.selfin.backend.service;

import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Чистый движок кармашка (спека 2026-07-02-pocket-core-design.md §3, §5).
 * Ни одного обращения к БД и Spring-зависимостей — только вход → выход.
 *
 * <p>Формула: кармашек(скоуп) = min прогнозной траектории баланса − буфер.
 * Breakdown-инвариант: STARTING − OVERDUE − EXPENSES(≤min) + INCOME(≤min) − FORECAST(≤min) = MIN;
 * MIN − BUFFER = POCKET.
 */
public final class PocketEngine {

    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private PocketEngine() {}

    public static PocketResultDto calculate(PocketInput in) {
        // 1. Текущий баланс: checkpoint + факты (правило §3.2) от даты чекпоинта до asOfDate.
        BigDecimal currentBalance = in.checkpointAmount();
        for (EventSnapshot e : in.events()) {
            if (e.wishlistStatus() != null || e.factAmount() == null || e.date() == null) continue;
            if (e.date().isAfter(in.asOfDate())) continue;
            if (in.checkpointDate() != null && e.date().isBefore(in.checkpointDate())) continue;
            currentBalance = currentBalance.add(signed(e.type(), e.factAmount()));
        }

        // 2. День 0: − резерв просрочки − плановые расходы сегодняшнего дня.
        //    Плановые доходы с датой ≤ asOfDate НЕ учитываются (консервативная асимметрия §3.3.2).
        BigDecimal overdue = in.overdueEvents().stream()
                .map(EventSnapshot::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayExpenses = in.events().stream()
                .filter(PocketEngine::isPendingPlan)
                .filter(e -> e.wishlistStatus() == null)
                .filter(e -> in.asOfDate().equals(e.date()) && e.type() != EventType.INCOME)
                .map(EventSnapshot::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Окно прогноза незапланированных: asOf+1 .. min(конец месяца, горизонт) (§3.5).
        LocalDate monthEnd = in.asOfDate().withDayOfMonth(in.asOfDate().lengthOfMonth());
        LocalDate forecastEnd = monthEnd.isBefore(in.horizonEnd()) ? monthEnd : in.horizonEnd();
        long forecastDays = ChronoUnit.DAYS.between(in.asOfDate(), forecastEnd); // дней в (asOf, forecastEnd]
        BigDecimal forecastTotal = forecastDays > 0 && in.unplannedForecast() != null
                ? in.unplannedForecast().max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal dailyForecast = forecastDays > 0
                ? forecastTotal.divide(BigDecimal.valueOf(forecastDays), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 4. Плановые события будущих дней (фильтр хотелок §3.2 применён).
        Map<LocalDate, List<EventSnapshot>> futureByDay = in.events().stream()
                .filter(PocketEngine::isPendingPlan)
                .filter(PocketEngine::allowedInTrajectory)
                .filter(e -> e.date() != null
                        && e.date().isAfter(in.asOfDate()) && !e.date().isAfter(in.horizonEnd()))
                .collect(Collectors.groupingBy(EventSnapshot::date));

        // 5. Траектория + минимум + суммы-до-минимума (для breakdown-инварианта §5).
        List<PocketResultDto.TrajectoryPoint> trajectory = new ArrayList<>();
        BigDecimal running = currentBalance.subtract(overdue).subtract(todayExpenses);
        trajectory.add(new PocketResultDto.TrajectoryPoint(
                in.asOfDate(), running, BigDecimal.ZERO, overdue.add(todayExpenses)));

        BigDecimal minBalance = running;
        LocalDate minDate = in.asOfDate();
        String minDrivenBy = null;
        BigDecimal expensesCum = todayExpenses;
        BigDecimal incomeCum = BigDecimal.ZERO;
        BigDecimal forecastCum = BigDecimal.ZERO;
        BigDecimal expensesAtMin = todayExpenses;
        BigDecimal incomeAtMin = BigDecimal.ZERO;
        BigDecimal forecastAtMin = BigDecimal.ZERO;
        BigDecimal forecastSpread = BigDecimal.ZERO;

        for (LocalDate d = in.asOfDate().plusDays(1); !d.isAfter(in.horizonEnd()); d = d.plusDays(1)) {
            BigDecimal dayIncome = BigDecimal.ZERO;
            BigDecimal dayExpense = BigDecimal.ZERO;
            BigDecimal dayTopExpenseAmount = BigDecimal.ZERO;
            String dayTopExpense = null;
            for (EventSnapshot e : futureByDay.getOrDefault(d, List.of())) {
                BigDecimal amount = e.plannedAmount() != null ? e.plannedAmount() : BigDecimal.ZERO;
                if (e.type() == EventType.INCOME) {
                    dayIncome = dayIncome.add(amount);
                    incomeCum = incomeCum.add(amount);
                    running = running.add(amount);
                } else {
                    dayExpense = dayExpense.add(amount);
                    expensesCum = expensesCum.add(amount);
                    running = running.subtract(amount);
                    if (amount.compareTo(dayTopExpenseAmount) > 0) {
                        dayTopExpenseAmount = amount;
                        dayTopExpense = e.description();
                    }
                }
            }
            if (forecastDays > 0 && !d.isAfter(forecastEnd)) {
                BigDecimal dayForecast = d.equals(forecastEnd)
                        ? forecastTotal.subtract(forecastSpread) // остаток на последний день — сумма сходится точно
                        : dailyForecast;
                forecastSpread = forecastSpread.add(dayForecast);
                forecastCum = forecastCum.add(dayForecast);
                dayExpense = dayExpense.add(dayForecast);
                running = running.subtract(dayForecast);
            }
            trajectory.add(new PocketResultDto.TrajectoryPoint(d, running, dayIncome, dayExpense));
            if (running.compareTo(minBalance) < 0) {
                minBalance = running;
                minDate = d;
                minDrivenBy = dayTopExpense;
                expensesAtMin = expensesCum;
                incomeAtMin = incomeCum;
                forecastAtMin = forecastCum;
            }
        }

        BigDecimal buffer = in.bufferAmount() != null ? in.bufferAmount() : BigDecimal.ZERO;
        BigDecimal pocket = minBalance.subtract(buffer);

        // 6. Кандидаты-хотелки — ТОЛЬКО из отдельной выборки (§3.1): OPEN любые
        //    + FIXED-неконвертированные без даты. Датированные FIXED уже в траектории из events.
        List<PocketResultDto.WishlistCandidate> candidates = in.wishlistEvents().stream()
                .filter(e -> e.wishlistStatus() == WishlistStatus.OPEN
                        || (e.wishlistStatus() == WishlistStatus.FIXED && !e.converted() && e.date() == null))
                .map(e -> new PocketResultDto.WishlistCandidate(e.id(), e.description(),
                        e.plannedAmount(), e.date(), e.wishlistStatus() == WishlistStatus.FIXED))
                .toList();

        List<PocketResultDto.BreakdownLine> breakdown = buildBreakdown(in, currentBalance, overdue,
                expensesAtMin, incomeAtMin, forecastAtMin, minBalance, minDate, buffer, pocket, candidates);

        return new PocketResultDto(pocket, currentBalance, buffer,
                new PocketResultDto.Horizon(in.scope().type(), in.horizonEnd(),
                        horizonLabel(in), in.horizonFallback()),
                new PocketResultDto.MinPoint(minDate, minBalance, minDrivenBy),
                breakdown, trajectory, candidates);
    }

    // ── правила фильтрации (спека §3.2) ─────────────────────────────────────

    /** PLAN(PLANNED) без факта — ещё не исполнен, участвует в прогнозе. */
    private static boolean isPendingPlan(EventSnapshot e) {
        return e.factAmount() == null
                && e.eventKind() == EventKind.PLAN && e.status() == EventStatus.PLANNED;
    }

    /** Фильтр хотелок для траектории: обычные события + датированные FIXED-неконвертированные. */
    private static boolean allowedInTrajectory(EventSnapshot e) {
        if (e.wishlistStatus() == null) return true;
        return e.wishlistStatus() == WishlistStatus.FIXED && !e.converted() && e.date() != null;
    }

    private static BigDecimal signed(EventType type, BigDecimal amount) {
        return type == EventType.INCOME ? amount : amount.negate();
    }

    // ── breakdown (спека §5) ────────────────────────────────────────────────

    private static List<PocketResultDto.BreakdownLine> buildBreakdown(
            PocketInput in, BigDecimal currentBalance, BigDecimal overdue,
            BigDecimal expensesAtMin, BigDecimal incomeAtMin, BigDecimal forecastAtMin,
            BigDecimal minBalance, LocalDate minDate, BigDecimal buffer, BigDecimal pocket,
            List<PocketResultDto.WishlistCandidate> candidates) {

        List<PocketResultDto.BreakdownLine> lines = new ArrayList<>();
        String minDateLabel = DD_MM.format(minDate);

        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.STARTING_BALANCE,
                in.checkpointDate() != null
                        ? "Остаток на счёте (чекпоинт " + DD_MM.format(in.checkpointDate()) + " + движение)"
                        : "Остаток на счёте (по событиям, чекпоинта нет)",
                currentBalance, List.of()));

        if (overdue.signum() != 0) {
            List<String> details = in.overdueEvents().stream()
                    .map(e -> e.description() != null ? e.description() : "без описания").toList();
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.OVERDUE_RESERVE,
                    "Просроченные обязательства (" + in.overdueEvents().size() + " шт)",
                    overdue.negate(), details));
        }
        if (expensesAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.PLANNED_EXPENSES,
                    "Плановые расходы до " + minDateLabel, expensesAtMin.negate(), List.of()));
        }
        if (incomeAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.PLANNED_INCOME,
                    "Плановые доходы до " + minDateLabel, incomeAtMin, List.of()));
        }
        if (forecastAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.UNPLANNED_FORECAST,
                    "Прогноз незапланированных", forecastAtMin.negate(), in.forecastContributors()));
        }
        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.TRAJECTORY_MIN,
                "Минимум траектории (" + minDateLabel + ")", minBalance, List.of()));
        if (buffer.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.BUFFER,
                    "Буфер (настройка)", buffer.negate(), List.of()));
        }
        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.POCKET, "Кармашек", pocket, List.of()));

        BigDecimal wishlistSum = candidates.stream()
                .map(PocketResultDto.WishlistCandidate::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (wishlistSum.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.WISHLIST_INFO,
                    "Хотелки-кандидаты (не вычтены)", wishlistSum, List.of()));
        }
        return lines;
    }

    /** Шаблоны label горизонта — без категорийных эвристик (спека §4). */
    private static String horizonLabel(PocketInput in) {
        if (in.horizonFallback()) return "30 дней вперёд (нет плановых доходов)";
        return switch (in.scope().type()) {
            case NEXT_INCOME -> "до дохода " + DD_MM.format(in.horizonEnd());
            case SECOND_INCOME -> "до 2-го дохода " + DD_MM.format(in.horizonEnd());
            case MONTHS -> in.scope().months() + " мес (до " + DD_MM.format(in.horizonEnd()) + ")";
            case DATE -> "до " + DD_MM_YYYY.format(in.horizonEnd());
        };
    }
}
