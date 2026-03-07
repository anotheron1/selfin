package ru.selfin.backend.dto;

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
        /** Умный прогноз: вычисляется сервисом при ответе */
        LocalDate estimatedCompletionDate) {
}
