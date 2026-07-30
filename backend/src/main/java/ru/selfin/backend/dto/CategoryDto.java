package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;

import java.util.UUID;

/**
 * @param primaryIncome «основной доход» — задаёт горизонт кармашка «до дохода» (ANO-35)
 */
public record CategoryDto(
        UUID id,
        String name,
        CategoryType type,
        Priority priority,
        boolean isSystem,
        boolean forecastEnabled,
        boolean primaryIncome) {
}
