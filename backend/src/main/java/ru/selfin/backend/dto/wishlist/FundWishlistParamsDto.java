package ru.selfin.backend.dto.wishlist;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Параметры примерки копилки или кредита, которые «Что с капиталом» переносит в запись (ANO-162).
 *
 * <p>Только то, что меняет примерка. Раньше блок писал их полной перезаписью {@code PUT /funds}, а
 * отсутствующий счёт сервер читает как «отвязать» — так отвязывает форма «Целей» (ANO-158).
 * Замер 28.09: копилка на счёте «Эталон» после фиксации — накоплено 5 000 → 0, счёта нет.
 *
 * <p>Сумма здесь — полная цель, как её показывает «Что с капиталом» ({@code WishlistItemDto.amount}),
 * а не остаток, как в примерке «Хотелок» ({@code SandboxFixRequestDto}). Границы ставки и срока —
 * те же, что у {@code TargetFundCreateDto}: от них считает «можно ли записать» диалог фиксации.
 *
 * @param targetAmount     цель из примерки
 * @param targetDate       срок; {@code null} — не меняется
 * @param creditRate       ставка кредита; {@code null} — не меняется
 * @param creditTermMonths срок кредита в месяцах; {@code null} — не меняется
 */
public record FundWishlistParamsDto(
        @NotNull @PositiveOrZero BigDecimal targetAmount,
        LocalDate targetDate,
        @DecimalMin("0.01") @DecimalMax("99.99") BigDecimal creditRate,
        @Min(1) @Max(360) Integer creditTermMonths) {
}
