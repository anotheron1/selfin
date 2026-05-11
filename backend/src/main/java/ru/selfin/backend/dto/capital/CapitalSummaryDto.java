package ru.selfin.backend.dto.capital;

import java.math.BigDecimal;
import java.util.List;

public record CapitalSummaryDto(
        BigDecimal total,
        BigDecimal liquid,
        BigDecimal assetsTotal,
        BigDecimal liabilitiesTotal,
        List<CapitalItemDto> items,
        Deltas deltas
) {
    public record Deltas(BigDecimal month, BigDecimal quarter, BigDecimal year) {}
}
