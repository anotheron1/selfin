package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record StandaloneFactCreateDto(
        @NotNull LocalDate date,
        @NotNull UUID categoryId,
        @NotNull EventType type,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority) {
}
