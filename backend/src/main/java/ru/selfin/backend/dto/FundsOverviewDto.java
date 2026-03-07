package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

public record FundsOverviewDto(
        BigDecimal pocketBalance,
        List<TargetFundDto> funds) {
}
