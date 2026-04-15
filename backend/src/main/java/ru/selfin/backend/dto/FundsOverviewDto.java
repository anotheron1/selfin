package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

public record FundsOverviewDto(
        BigDecimal pocketBalance,
        List<TargetFundDto> funds,
        BigDecimal predictionAdjustedPocket,  // null when delta < 100 ₽ (effectively zero)
        List<String> forecastContributors     // e.g. ["Прочее (+4к)", "Транспорт (+3.5к)"]
) {}
