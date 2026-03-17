package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** DTO для ручного создания хотелки. */
public record WishlistCreateDto(
        @NotBlank String description,
        @PositiveOrZero BigDecimal plannedAmount,
        @Size(max = 2048) String url
) {}
