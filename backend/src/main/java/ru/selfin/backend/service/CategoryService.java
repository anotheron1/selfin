package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.dto.CategoryDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.repository.CategoryRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<CategoryDto> findAll() {
        return categoryRepository.findAllByDeletedFalse().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public CategoryDto create(CategoryCreateDto dto) {
        Category category = Category.builder()
                .name(dto.name())
                .type(dto.type())
                .mandatory(dto.mandatory())
                .build();
        return toDto(categoryRepository.save(category));
    }

    @Transactional
    public CategoryDto update(UUID id, CategoryCreateDto dto) {
        Category category = categoryRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));
        category.setName(dto.name());
        category.setType(dto.type());
        category.setMandatory(dto.mandatory());
        return toDto(categoryRepository.save(category));
    }

    @Transactional
    public void delete(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));
        category.setDeleted(true);
        categoryRepository.save(category);
    }

    @Transactional
    public CategoryDto toggleMandatory(UUID id) {
        Category category = categoryRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));
        category.setMandatory(!category.isMandatory());
        return toDto(categoryRepository.save(category));
    }

    public CategoryDto toDto(Category c) {
        return new CategoryDto(c.getId(), c.getName(), c.getType(), c.isMandatory());
    }
}
