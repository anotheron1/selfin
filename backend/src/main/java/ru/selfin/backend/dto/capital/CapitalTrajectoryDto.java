package ru.selfin.backend.dto.capital;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CapitalTrajectoryDto(List<Point> points) {
    public record Point(
            LocalDate date,
            BigDecimal capital,
            BigDecimal liquid,
            BigDecimal assets,
            BigDecimal liabilities
    ) {}
}
