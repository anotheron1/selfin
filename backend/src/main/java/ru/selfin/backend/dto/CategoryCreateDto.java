package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryCreateDto(
        @NotBlank String name,
        @NotNull CategoryType type,
        boolean mandatory) {
}
