package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.EventStatus;

/**
 * DTO для частичного обновления события (ввод фактической суммы).
 * Используется при клике на транзакцию в экране "Бюджет".
 */
public record FinancialEventUpdateFactDto(
        Double factAmount,
        String description,
        EventStatus status) {
}
