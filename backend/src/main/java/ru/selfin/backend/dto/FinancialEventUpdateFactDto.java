package ru.selfin.backend.dto;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * DTO для частичного обновления события: ввод фактической суммы и комментария.
 * Используется при клике на транзакцию в экране "Бюджет" (PATCH /events/{id}/fact).
 * Передаёт только изменяемые в UI поля, не требуя пересылки всего события.
 *
 * @param factAmount фактическая сумма; {@code null} — снять отметку об исполнении
 * @param description произвольный комментарий пользователя; {@code null} — без изменений
 */
public record FinancialEventUpdateFactDto(
        @PositiveOrZero BigDecimal factAmount,
        String description) {
}
