package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;
import java.util.List;

/**
 * Результат пересчёта одного item'а: delta + выведенные взнос/платёж.
 *
 * @param delta               delta-вектор по месяцам
 * @param monthlyContribution месячный взнос (SAVINGS), иначе null
 * @param monthlyPMT          месячный платёж (CREDIT), иначе null
 */
public record RecomputeResponseDto(
        List<MonthDeltaDto> delta,
        BigDecimal monthlyContribution,
        BigDecimal monthlyPMT
) {}
