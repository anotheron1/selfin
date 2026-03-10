package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;

import java.math.BigDecimal;
import java.util.List;

public record MultiMonthReportDto(
        List<String> months,   // ["2026-01", "2026-02", ...]
        List<RowDto> rows
) {

    public record RowDto(
            RowType type,
            String label,
            CategoryType categoryType,   // null for total/balance rows
            List<MonthValueDto> values
    ) {}

    public record MonthValueDto(
            String month,
            BigDecimal planned,
            BigDecimal actual            // null = month in the future (no facts yet)
    ) {}

    public enum RowType {
        TOTAL_INCOME,
        TOTAL_EXPENSE,
        TOTAL_FUND_TRANSFER,
        CATEGORY,
        BALANCE
    }
}
