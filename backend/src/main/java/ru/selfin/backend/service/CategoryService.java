package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.dto.CategoryDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.SystemCategory;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
    private final FinancialEventRepository eventRepository;

    /**
     * Возвращает все активные (не удалённые) категории.
     *
     * @return список DTO категорий; пустой список если ни одной категории нет
     */
    public List<CategoryDto> findAll() {
        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        List<Integer> priorityOrder = List.of(
                Priority.HIGH.ordinal(), Priority.MEDIUM.ordinal(), Priority.LOW.ordinal());
        return categoryRepository.findAllByDeletedFalse().stream()
                .sorted(Comparator
                        .comparing((Category c) -> c.getType().name())
                        .thenComparingInt(c -> priorityOrder.indexOf(c.getPriority().ordinal()))
                        .thenComparing(Category::getName, collator::compare))
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
        boolean isSystem = SystemCategory.WISHLIST_NAME.equals(dto.name());
        Category category = Category.builder()
                .name(dto.name())
                .type(dto.type())
                .priority(dto.priority() != null ? dto.priority() : Priority.MEDIUM)
                .system(isSystem)
                .build();
        category.setForecastEnabled(dto.forecastEnabled() != null && dto.forecastEnabled());
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

        if (category.isSystem() && !category.getName().equals(dto.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "System categories cannot be renamed");
        }
        if (!category.getType().equals(dto.type())
                && eventRepository.existsByCategoryIdAndDeletedFalse(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot change type: category has active events");
        }

        category.setName(dto.name());
        category.setType(dto.type());
        category.setPriority(dto.priority() != null ? dto.priority() : Priority.MEDIUM);
        if (dto.forecastEnabled() != null) {
            category.setForecastEnabled(dto.forecastEnabled());
        }
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
     * Циклически меняет приоритет категории: HIGH → MEDIUM → LOW → HIGH.
     * Используется для быстрого переключения из UI без полного PUT.
     *
     * @param id идентификатор категории
     * @return категория с обновлённым приоритетом
     * @throws ResourceNotFoundException если категория не найдена или удалена
     */
    @Transactional
    public CategoryDto cyclePriority(UUID id) {
        Category category = categoryRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        category.setPriority(nextPriority(category.getPriority()));
        return toDto(categoryRepository.save(category));
    }

    Priority nextPriority(Priority current) {
        return switch (current) {
            case HIGH -> Priority.MEDIUM;
            case MEDIUM -> Priority.LOW;
            case LOW -> Priority.HIGH;
        };
    }

    /**
     * Конвертирует entity категории в DTO.
     *
     * @param c entity категории
     * @return DTO для передачи клиенту
     */
    public CategoryDto toDto(Category c) {
        return new CategoryDto(c.getId(), c.getName(), c.getType(), c.getPriority(), c.isSystem(), c.isForecastEnabled());
    }
}
