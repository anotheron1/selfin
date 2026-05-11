package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CapitalRevaluationCreateDto(
        @NotNull @PositiveOrZero BigDecimal value,
        @PastOrPresent LocalDate valuedAt,
        @Size(max = 1000) String note
) {}
