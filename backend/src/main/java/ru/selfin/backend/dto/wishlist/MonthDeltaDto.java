package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;

/**
 * Влияние одного item'а на один месяц горизонта.
 * @param monthIndex     0 = current+1, ... (смещение от текущего месяца)
 * @param accountDelta   изменение баланса счёта в этом месяце
 * @param capitalDelta   изменение капитала в этом месяце
 * @param fundDelta      изменение баланса копилок (для tooltip; опционально)
 * @param liabilityDelta изменение обязательств (для tooltip; опционально)
 */
public record MonthDeltaDto(
        int monthIndex,
        BigDecimal accountDelta,
        BigDecimal capitalDelta,
        BigDecimal fundDelta,
        BigDecimal liabilityDelta
) {}
