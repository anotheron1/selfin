package ru.selfin.backend.repository;

import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Проекция «срез item'а на дату»: для каждого живого item'а — его стоимость,
 * полученная как последняя живая переоценка с {@code valued_at ≤ asOfDate}.
 * <p>Если переоценок нет — item в результат не попадает (внешний код считает вклад = 0).
 */
public interface CapitalSnapshotProjection {
    UUID getItemId();
    CapitalItemKind getKind();
    BigDecimal getValue();
}
