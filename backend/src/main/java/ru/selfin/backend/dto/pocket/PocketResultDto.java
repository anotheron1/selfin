package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Ответ GET /api/v1/pocket (спека §3.6, §6). Один ответ кормит все представления. */
public record PocketResultDto(
        BigDecimal pocket,
        BigDecimal currentBalance,
        BigDecimal buffer,
        Horizon horizon,
        MinPoint minPoint,
        List<BreakdownLine> breakdown,
        List<TrajectoryPoint> trajectory,
        List<WishlistCandidate> wishlistCandidates
) {
    public record Horizon(PocketScope.Type type, LocalDate endDate, String label, boolean fallback) {}
    public record MinPoint(LocalDate date, BigDecimal balance) {}
    public record TrajectoryPoint(LocalDate date, BigDecimal balance) {}
    public record BreakdownLine(BreakdownType type, String label, BigDecimal amount, List<String> details) {}
    public record WishlistCandidate(java.util.UUID id, String description,
                                    BigDecimal plannedAmount, LocalDate date, boolean fixed) {}
}
