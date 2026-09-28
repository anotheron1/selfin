package ru.selfin.backend.dto.wishlist;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Параметры примерки хотелки-события, которые «Что с капиталом» переносит в запись (ANO-162).
 *
 * <p>Только то, что меняет примерка. Раньше блок писал их полной перезаписью {@code PUT /events},
 * и всё, чего он не знал, уходило в {@code null}: исходный текст, а описание пустой хотелки
 * становилось именем категории. Хотелку без срока {@code PUT} не писал вовсе — дата обязательна,
 * и подкрученная сумма терялась.
 *
 * @param plannedAmount сумма из примерки
 * @param date          срок из примерки или из диалога фиксации; {@code null} — срок не меняется
 *                      (у хотелки без срока его и нет — выдумывать нельзя, ANO-29)
 */
public record EventWishlistParamsDto(
        @NotNull @PositiveOrZero BigDecimal plannedAmount,
        LocalDate date) {
}
