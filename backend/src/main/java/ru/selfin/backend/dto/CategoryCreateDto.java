package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryCreateDto(
        @NotBlank String name,
        @NotNull CategoryType type,
        Priority priority,
        Boolean forecastEnabled,     // nullable: null = don't change on update, false on create
        Boolean primaryIncome) {     // «основной доход», та же nullable-семантика (ANO-35)

    /** Прежняя сигнатура (без флага основного дохода) — щадит существующие вызовы. */
    public CategoryCreateDto(String name, CategoryType type, Priority priority, Boolean forecastEnabled) {
        this(name, type, priority, forecastEnabled, null);
    }
}
