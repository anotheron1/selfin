package ru.selfin.backend.dto.strategy;

/**
 * Фаза точки временной шкалы стратегии. См. spec, раздел 2, инвариант I3.
 *
 * <ul>
 *   <li>{@code PAST} — yearMonth &lt; currentMonth. Балансовые поля прогноза null.</li>
 *   <li>{@code CURRENT} — yearMonth = currentMonth. {@code balance} = {@code liquidAt(today)}.</li>
 *   <li>{@code FUTURE} — yearMonth &gt; currentMonth. Все балансовые поля заполнены.</li>
 * </ul>
 */
public enum StrategyPointPhase {
    PAST,
    CURRENT,
    FUTURE
}
