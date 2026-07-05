package ru.selfin.backend.dto.pocket;

/** Типы строк разбивки «почему столько» (спека §5). Порядок = порядок рендера. */
public enum BreakdownType {
    STARTING_BALANCE, OVERDUE_RESERVE, PLANNED_EXPENSES, PLANNED_INCOME,
    UNPLANNED_FORECAST, TRAJECTORY_MIN, BUFFER, POCKET, WISHLIST_INFO
}
