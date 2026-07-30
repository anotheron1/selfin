package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO для ручного создания хотелки.
 *
 * @param date желаемый срок; null — «когда-нибудь». Хотелка со сроком в траекторию
 *             всё равно не попадает (она OPEN), но дата переносится из примерки,
 *             чтобы её не пришлось вводить второй раз (ANO-34).
 */
public record WishlistCreateDto(
        @NotBlank String description,
        @PositiveOrZero BigDecimal plannedAmount,
        @Size(max = 2048) String url,
        LocalDate date
) {
    /** Прежняя сигнатура (без срока) — щадит существующие вызовы и тесты. */
    public WishlistCreateDto(String description, BigDecimal plannedAmount, String url) {
        this(description, plannedAmount, url, null);
    }
}
