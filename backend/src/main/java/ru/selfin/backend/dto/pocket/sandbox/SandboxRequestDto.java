package ru.selfin.backend.dto.pocket.sandbox;

import ru.selfin.backend.dto.pocket.SandboxRef;

import java.util.List;

/**
 * Запрос POST /api/v1/pocket/sandbox (спека sandbox §4).
 *
 * @param scope   тот же парсер {@code PocketScope}, что GET /pocket
 * @param tryOn   включённые элементы примерки (может быть пустым)
 * @param exclude FIXED-элементы, выключенные из baseline («примерка отказа»);
 *                каждый обязан операционально сидеть в baseline (§9)
 */
public record SandboxRequestDto(
        String scope,
        List<TryOnDto> tryOn,
        List<SandboxRef> exclude
) {
    public SandboxRequestDto {
        tryOn = tryOn != null ? tryOn : List.of();
        exclude = exclude != null ? exclude : List.of();
    }
}
