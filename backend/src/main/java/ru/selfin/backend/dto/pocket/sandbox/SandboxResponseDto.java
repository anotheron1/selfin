package ru.selfin.backend.dto.pocket.sandbox;

import ru.selfin.backend.dto.pocket.PocketResultDto;

import java.util.List;

/**
 * Ответ POST /api/v1/pocket/sandbox (спека sandbox §4).
 * Пустая примерка валидна: baseline = fitted, itemDeltas пуст —
 * первый вызов инициализирует окно списком items.
 *
 * @param baseline реальный кармашек (с резервированием §6)
 * @param fitted   кармашек с примеркой
 */
public record SandboxResponseDto(
        PocketResultDto baseline,
        PocketResultDto fitted,
        List<ItemDeltaDto> itemDeltas,
        List<SandboxItemDto> items
) {}
