package ru.selfin.backend.dto.pocket.sandbox;

import ru.selfin.backend.dto.pocket.SandboxRef;

import java.util.List;

/**
 * Дневной вектор одного элемента примерки (задел под гибрид, спека sandbox §4).
 *
 * <p>Порядок в ответе: сначала tryOn в порядке запроса, затем exclude в порядке запроса.
 * Инвариант (§4): fitted.trajectory[d] = baseline.trajectory[d] + Σ по items Σ days≤d.
 *
 * @param ref null для ad-hoc записей tryOn
 */
public record ItemDeltaDto(SandboxRef ref, List<DayDeltaDto> days) {}
