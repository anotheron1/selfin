package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.dto.CategoryDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.repository.CategoryRepository;

import java.util.List;
import java.util.UUID;

/**
 * Сервис управления категориями доходов и расходов.
 * Категории служат классификатором для финансовых событий и задают
 * признак обязательности платежа ({@code mandatory}).
 *
 * <p>Удаление категорий — мягкое (soft delete): категория скрывается из
 * интерфейса, но связанные события сохраняют ссылку и историю.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /**
     * Возвращает все активные (не удалённые) категории.
     *
     * @return список DTO категорий; пустой список если ни одной категории нет
     */
    public List<CategoryDto> findAll() {
        return categoryRepository.findAllByDeletedFalse().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Создаёт новую категорию с заданными именем, типом и флагом обязательности.
     *
     * @param dto данные для создания категории
     * @return созданная категория
     */
    @Transactional
    public CategoryDto create(CategoryCreateDto dto) {
        Category category = Category.builder()
                .name(dto.name())
                .type(dto.type())
                .mandatory(dto.mandatory())
                .build();
        return toDto(categoryRepository.save(category));
    }

    /**
     * Полностью обновляет существующую категорию: имя, тип, флаг обязательности.
     *
     * @param id  идентификатор категории
     * @param dto новые данные
     * @return обновлённая категория
     * @throws ResourceNotFoundException если категория не найдена или удалена
     */
    @Transactional
    public CategoryDto update(UUID id, CategoryCreateDto dto) {
        Category category = categoryRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        category.setName(dto.name());
        category.setType(dto.type());
        category.setMandatory(dto.mandatory());
        return toDto(categoryRepository.save(category));
    }

    /**
     * Помечает категорию как удалённую (soft delete).
     * Связанные финансовые события при этом не удаляются.
     *
     * @param id идентификатор категории
     * @throws ResourceNotFoundException если категория не найдена
     */
    @Transactional
    public void delete(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        category.setDeleted(true);
        categoryRepository.save(category);
    }

    /**
     * Инвертирует флаг {@code mandatory} у категории.
     * Используется для быстрого переключения обязательности из UI без полного PUT.
     *
     * @param id идентификатор категории
     * @return категория с обновлённым флагом
     * @throws ResourceNotFoundException если категория не найдена или удалена
     */
    @Transactional
    public CategoryDto toggleMandatory(UUID id) {
        Category category = categoryRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        category.setMandatory(!category.isMandatory());
        return toDto(categoryRepository.save(category));
    }

    /**
     * Конвертирует entity категории в DTO.
     *
     * @param c entity категории
     * @return DTO для передачи клиенту
     */
    public CategoryDto toDto(Category c) {
        return new CategoryDto(c.getId(), c.getName(), c.getType(), c.isMandatory());
    }
}
