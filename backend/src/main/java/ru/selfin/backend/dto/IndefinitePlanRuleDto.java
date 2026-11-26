package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** lastPlannedDate is the latest active planned occurrence, or null if none exists. */
public record IndefinitePlanRuleDto(
        UUID id,
        UUID categoryId,
        EventType type,
        BigDecimal plannedAmount,
        Priority priority,
        String description,
        RecurringFrequency frequency,
        Integer dayOfMonth,
        Integer monthOfYear,
        LocalDate startDate,
        LocalDate lastPlannedDate
) {}
