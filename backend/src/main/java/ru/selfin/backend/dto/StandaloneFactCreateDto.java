package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param rawInput исходный текст суммы, если её ввели выражением («450+1230+890», ANO-33).
 * @param onCredit «в кредит» (срез 4, п. 1): только у расхода; {@code null} — нет
 */
public record StandaloneFactCreateDto(
        @NotNull LocalDate date,
        @NotNull UUID categoryId,
        @NotNull EventType type,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority,
        String rawInput,
        Boolean onCredit) {

    /** Без отметки «в кредит». */
    public StandaloneFactCreateDto(LocalDate date, UUID categoryId, EventType type, BigDecimal factAmount,
                                   String description, Priority priority, String rawInput) {
        this(date, categoryId, type, factAmount, description, priority, rawInput, null);
    }
}
