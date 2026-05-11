package ru.selfin.backend.dto.capital;

import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CapitalItemDto(
        UUID id,
        CapitalItemKind kind,
        String name,
        String description,
        Instant createdAt,
        BigDecimal currentValue,
        LocalDate lastValuedAt,
        boolean isArchived
) {}
