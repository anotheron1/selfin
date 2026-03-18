package ru.selfin.backend.dto;

import java.math.BigDecimal;

public record FundPlannerMonthDto(
        String yearMonth,
        BigDecimal plannedIncome,
        BigDecimal mandatoryExpenses,
        BigDecimal allPlannedExpenses,
        BigDecimal factExpenses
) {}
