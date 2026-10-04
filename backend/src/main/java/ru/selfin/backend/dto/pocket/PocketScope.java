package ru.selfin.backend.dto.pocket;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Скоуп кармашка (спека §4 + ANO-14 §4): NEXT_INCOME (дефолт) | SECOND_INCOME |
 * MONTHS:n (1..36) | DATE:yyyy-MM-dd.
 * Парсер бросает IllegalArgumentException — обвязка мапит на 400.
 */
public record PocketScope(Type type, Integer months, LocalDate date) {

    public enum Type { NEXT_INCOME, SECOND_INCOME, MONTHS, DATE }

    public static final int MAX_MONTHS = 36;

    /**
     * Дальше этой даты ядро не считает (Р1): конец месяца через {@link #MAX_MONTHS} от сегодня.
     * «Стратегия» берёт из траектории точку на последний день каждого месяца, и последний её
     * месяц должен быть посчитан целиком. Границу берут и проверка скоупа {@code DATE}, и
     * продление повторяющихся правил — иначе траты конца месяца в точку не попали бы.
     */
    public static LocalDate maxEnd(LocalDate asOf) {
        return java.time.YearMonth.from(asOf).plusMonths(MAX_MONTHS).atEndOfMonth();
    }

    public static PocketScope parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("NEXT_INCOME")) {
            return new PocketScope(Type.NEXT_INCOME, null, null);
        }
        if (raw.equals("SECOND_INCOME")) {
            return new PocketScope(Type.SECOND_INCOME, null, null);
        }
        if (raw.startsWith("MONTHS:")) {
            int n;
            try {
                n = Integer.parseInt(raw.substring("MONTHS:".length()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid scope: " + raw);
            }
            if (n < 1 || n > MAX_MONTHS) {
                throw new IllegalArgumentException("MONTHS must be in [1, " + MAX_MONTHS + "]: " + raw);
            }
            return new PocketScope(Type.MONTHS, n, null);
        }
        if (raw.startsWith("DATE:")) {
            try {
                return new PocketScope(Type.DATE, null, LocalDate.parse(raw.substring("DATE:".length())));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid scope date: " + raw);
            }
        }
        throw new IllegalArgumentException("Unknown scope: " + raw);
    }
}
