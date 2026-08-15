package ru.selfin.backend.dto.pocket;

/**
 * Типы строк разбивки «почему столько» (спека §5 + sandbox §6). Порядок = порядок рендера.
 *
 * <p>Строки после {@code POCKET} — информационные: они НЕ входят в инвариант
 * {@code STARTING − OVERDUE − EXPENSES − CONTRIB + INCOME − FORECAST = MIN},
 * {@code MIN − BUFFER = POCKET}. Порядок это и означает: всё до кармашка объясняет, из чего он
 * сложился, всё после — оговорки к нему.
 */
public enum BreakdownType {
    STARTING_BALANCE, OVERDUE_RESERVE, PLANNED_EXPENSES, SAVINGS_CONTRIBUTIONS, PLANNED_INCOME,
    UNPLANNED_FORECAST, TRAJECTORY_MIN, BUFFER, POCKET,
    /** Сколько нужно, чтобы вернуть кредитки к планке (ANO-9 §4.2). Информационная. */
    CREDIT_RESTORE,
    WISHLIST_INFO
}
