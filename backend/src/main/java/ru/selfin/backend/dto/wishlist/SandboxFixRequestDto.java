package ru.selfin.backend.dto.wishlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Параметры примерки, которые «Зафиксировать» переносит в реальный план (ANO-34 §1).
 *
 * <p>До этого фиксация меняла только статус, а подкрученные в окне примерки сумма/дата/растяжка
 * жили в клиентском черновике и терялись: пользователь видел одно, а план получал другое.
 *
 * @param sourceKind       WISHLIST (событие) | SAVINGS | CREDIT (фонд)
 * @param amount           сумма из примерки. ВНИМАНИЕ: у фонда это ОСТАТОК (сколько ещё
 *                         докопить), а не полная цель — см. {@code PocketSandboxService.buildItems}
 * @param date             дата из примерки; обязательна при {@code stretchMonths} 0/null,
 *                         иначе выводится из растяжки
 * @param stretchMonths    растяжка ползунком; ≥ 1 задаёт дату цели и побеждает поле даты
 * @param creditRate       ставка (только CREDIT), null — не менять
 * @param creditTermMonths срок (только CREDIT), null — не менять
 */
public record SandboxFixRequestDto(
        @NotBlank String sourceKind,
        @NotNull @Positive BigDecimal amount,
        LocalDate date,
        @PositiveOrZero Integer stretchMonths,
        BigDecimal creditRate,
        Integer creditTermMonths
) {
    /** Растяжка как число: null трактуется как «без растяжки». */
    public int stretchOrZero() {
        return stretchMonths != null ? stretchMonths : 0;
    }
}
