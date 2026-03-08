package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.dto.CategoryDto;
import ru.selfin.backend.service.CategoryService;

import java.util.List;
import java.util.UUID;

/**
 * REST-контроллер категорий.
 * Категории создаются один раз и редко меняются; сиды базовых категорий
 * задаются в миграции {@code V2__seed_categories.sql}.
 *
 * @see ru.selfin.backend.service.CategoryService
 */
@Tag(name = "Категории", description = "Управление категориями доходов и расходов")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "Получить все активные категории")
    @GetMapping
    public List<CategoryDto> getAll() {
        return categoryService.findAll();
    }

    @Operation(summary = "Создать новую категорию")
    @PostMapping
    public CategoryDto create(@Valid @RequestBody CategoryCreateDto dto) {
        return categoryService.create(dto);
    }

    @Operation(summary = "Обновить категорию")
    @PutMapping("/{id}")
    public CategoryDto update(
            @Parameter(description = "ID категории") @PathVariable UUID id,
            @Valid @RequestBody CategoryCreateDto dto) {
        return categoryService.update(id, dto);
    }

    @Operation(summary = "Мягко удалить категорию (soft delete)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "ID категории") @PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Переключить флаг 'обязательная' у категории")
    @PatchMapping("/{id}/mandatory")
    public CategoryDto toggleMandatory(
            @Parameter(description = "ID категории") @PathVariable UUID id) {
        return categoryService.toggleMandatory(id);
    }
}
