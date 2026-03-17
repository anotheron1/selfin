package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TargetFundDto(
        UUID id,
        String name,
        BigDecimal targetAmount,
        BigDecimal currentBalance,
        FundStatus status,
        Integer priority,
        /** Желаемая дата достижения цели, заданная пользователем */
        LocalDate targetDate,
        /** Умный прогноз: вычисляется сервисом на основе среднемесячного пополнения */
        LocalDate estimatedCompletionDate,
        FundPurchaseType purchaseType,
        BigDecimal creditRate,
        Integer creditTermMonths) {
}
