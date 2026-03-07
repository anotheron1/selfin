package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;

import java.util.UUID;

public record CategoryDto(
        UUID id,
        String name,
        CategoryType type,
        boolean mandatory) {
}
