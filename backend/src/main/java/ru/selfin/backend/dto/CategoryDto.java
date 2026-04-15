package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;

import java.util.UUID;

public record CategoryDto(
        UUID id,
        String name,
        CategoryType type,
        Priority priority,
        boolean isSystem,
        boolean forecastEnabled) {
}
