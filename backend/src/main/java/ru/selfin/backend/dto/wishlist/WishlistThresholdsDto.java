package ru.selfin.backend.dto.wishlist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Пороги «Хотелок».
 *
 * <p>Р5 (ANO-93): подушка одна — НЗ кармашка ({@code /settings/pocket}). Порог «Подушка, мес.»
 * ({@code cashBufferMonths}) ушёл, но живёт в сохранённых записях и в телах старых клиентов —
 * страница из кэша, сеятель CI из {@code main}. Лишнее поле пропускается: без этого разбор
 * сохранённой записи падал, сервис молча отдавал умолчание, и «Мин. капитал» терялся.
 *
 * @param capitalThresholdRub null = критерий капитала выключен
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WishlistThresholdsDto(
        BigDecimal capitalThresholdRub
) {}
