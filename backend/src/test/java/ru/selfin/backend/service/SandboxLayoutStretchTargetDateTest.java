package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило «дата цели из ползунка» (§8 + ANO-34 §1). Раньше жило на клиенте
 * (sandboxMath.stretchTargetDate) — случаи перенесены оттуда дословно, чтобы
 * переезд правила на сервер не потерял покрытие.
 */
class SandboxLayoutStretchTargetDateTest {

    @Test
    void stretchTargetDate_isLastDayOfLastContributionMonth() {
        // today 2026-07-18, растяжка 3 → последний взнос в октябре
        assertThat(SandboxLayout.stretchTargetDate(LocalDate.of(2026, 7, 18), 3))
                .isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(SandboxLayout.stretchTargetDate(LocalDate.of(2026, 7, 18), 5))
                .isEqualTo(LocalDate.of(2026, 12, 31));
        // переход через год
        assertThat(SandboxLayout.stretchTargetDate(LocalDate.of(2026, 12, 10), 2))
                .isEqualTo(LocalDate.of(2027, 2, 28));
        // растяжка ≤ 0 трактуется как 1 (следующий месяц)
        assertThat(SandboxLayout.stretchTargetDate(LocalDate.of(2026, 7, 18), 0))
                .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void stretchTargetDate_isInverseOfMaxStretchMonths() {
        // Ради этого правило и существует: резерв §6 обязан воспроизвести ту же
        // раскладку, что показывала примерка, при любом stretch < max.
        for (int stretch = 1; stretch <= 12; stretch++) {
            LocalDate asOf = LocalDate.of(2026, 1, 31);   // последний день месяца — граничный случай
            assertThat(SandboxLayout.maxStretchMonths(asOf, SandboxLayout.stretchTargetDate(asOf, stretch)))
                    .isEqualTo(stretch);
        }
    }
}
