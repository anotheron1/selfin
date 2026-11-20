package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.SystemCategory;

import java.util.UUID;

/**
 * @param primaryIncome «основной доход» — задаёт горизонт кармашка «до дохода» (ANO-35)
 * @param systemKind    вид системной категории (срез 3 п. 13): по нему форма узнаёт «Накопление»; {@code null} у своей
 */
public record CategoryDto(
        UUID id,
        String name,
        CategoryType type,
        Priority priority,
        boolean isSystem,
        boolean forecastEnabled,
        boolean primaryIncome,
        SystemCategory systemKind) {
}
