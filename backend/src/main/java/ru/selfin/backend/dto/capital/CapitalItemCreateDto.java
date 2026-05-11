package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.*;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CapitalItemCreateDto(
        @NotNull CapitalItemKind kind,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @PositiveOrZero BigDecimal initialValue,
        @PastOrPresent LocalDate initialValuedAt
) {}
