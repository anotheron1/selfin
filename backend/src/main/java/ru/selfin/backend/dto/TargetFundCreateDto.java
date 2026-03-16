package ru.selfin.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.selfin.backend.model.enums.FundPurchaseType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TargetFundCreateDto(
                @NotBlank String name,
                @NotNull @PositiveOrZero BigDecimal targetAmount,
                Integer priority,
                LocalDate targetDate,
                FundPurchaseType purchaseType,
                @DecimalMin("0.01") @DecimalMax("99.99") BigDecimal creditRate,
                @Min(1) @Max(360) Integer creditTermMonths) {
}
