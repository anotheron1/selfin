package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.Size;

public record CapitalItemUpdateDto(
        @Size(min = 1, max = 255) String name,
        @Size(max = 2000) String description
) {}
