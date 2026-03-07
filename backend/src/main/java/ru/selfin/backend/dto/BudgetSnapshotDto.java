package ru.selfin.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO снимка бюджета для ответа API.
 * Не включает snapshotData (JSONB) — он большой, запрашивается отдельно при
 * необходимости.
 */
public record BudgetSnapshotDto(
        UUID id,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDateTime snapshotDate) {
}
