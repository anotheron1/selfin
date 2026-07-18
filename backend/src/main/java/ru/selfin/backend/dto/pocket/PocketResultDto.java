package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Ответ GET /api/v1/pocket (спека §3.6, §6). Один ответ кормит все представления. */
public record PocketResultDto(
        BigDecimal pocket,
        BigDecimal currentBalance,
        BigDecimal buffer,
        /** Дата последнего якоря остатка; null — якоря ещё не было (ANO-15: напоминалка возраста). */
        LocalDate checkpointDate,
        Horizon horizon,
        MinPoint minPoint,
        List<BreakdownLine> breakdown,
        List<TrajectoryPoint> trajectory,
        List<WishlistCandidate> wishlistCandidates
) {
    public record Horizon(PocketScope.Type type, LocalDate endDate, String label, boolean fallback) {}
    /**
     * Точка минимума; drivenBy = описание самого крупного планового расхода дня минимума.
     * null — если минимум в день 0, если в день минимума нет расходов-событий (типовой случай:
     * минимум создан размазкой прогноза незапланированных) или у события нет описания.
     */
    public record MinPoint(LocalDate date, BigDecimal balance, String drivenBy) {}
    /** Точка траектории с дневными суммами (спека §3.6, дополнение 2026-07-04): прогноз входит в expense. */
    public record TrajectoryPoint(LocalDate date, BigDecimal balance, BigDecimal income, BigDecimal expense) {}
    public record BreakdownLine(BreakdownType type, String label, BigDecimal amount, List<String> details) {}
    public record WishlistCandidate(java.util.UUID id, String description,
                                    BigDecimal plannedAmount, LocalDate date, boolean fixed) {}
}
