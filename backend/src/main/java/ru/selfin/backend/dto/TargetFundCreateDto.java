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
import java.util.UUID;

/**
 * @param accountId счёт, на котором физически лежат деньги цели (спека §3.3). {@code null} —
 *                  виртуальный конверт, как раньше: пополняется переводом и держит собственный
 *                  остаток. Если задан, {@code currentBalance} перестаёт быть источником правды
 *                  и вычисляется из остатка счёта, а перевод в такую копилку запрещён — деньги
 *                  двигаются на самом счёте. Одна цель на счёт.
 */
public record TargetFundCreateDto(
                @NotBlank String name,
                @NotNull @PositiveOrZero BigDecimal targetAmount,
                Integer priority,
                LocalDate targetDate,
                FundPurchaseType purchaseType,
                @DecimalMin("0.01") @DecimalMax("99.99") BigDecimal creditRate,
                @Min(1) @Max(360) Integer creditTermMonths,
                UUID accountId) {

        /** Совместимость с местами, где копилка создаётся виртуальным конвертом. */
        public TargetFundCreateDto(String name, BigDecimal targetAmount, Integer priority,
                                   LocalDate targetDate, FundPurchaseType purchaseType,
                                   BigDecimal creditRate, Integer creditTermMonths) {
                this(name, targetAmount, priority, targetDate, purchaseType, creditRate,
                        creditTermMonths, null);
        }
}
