package ru.selfin.backend.dto.pocket.sandbox;

import ru.selfin.backend.dto.pocket.SandboxRef;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Один включённый элемент примерки (спека sandbox §4).
 *
 * @param ref            существующая хотелка/копилка; null = ad-hoc трата
 * @param amount         сумма (полная; для растяжки делится на взносы)
 * @param date           дата покупки/цели — обязательна, строго в будущем (§9)
 * @param stretchMonths  0/null = разовая трата в дату; n ≥ 1 = взносы «первые n месяцев» (§5)
 * @param creditRate     годовая ставка, % — только для кредита (взаимоисключимо с растяжкой)
 * @param creditTermMonths срок кредита в месяцах — только для кредита
 */
public record TryOnDto(
        SandboxRef ref,
        BigDecimal amount,
        LocalDate date,
        Integer stretchMonths,
        BigDecimal creditRate,
        Integer creditTermMonths
) {}
