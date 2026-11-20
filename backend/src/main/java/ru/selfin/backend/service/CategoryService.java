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
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.SystemCategory;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.text.Collator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final AccountService accountService;
    /** «Сегодня» окна порядка категорий (ClockInjectionGuardTest). */
    private final Clock clock;

    /** Окно порядка категорий (срез 3 п. 14): строки с датой от стольких дней назад до стольких вперёд. */
    static final int ORDER_DAYS_BACK = 90;
    static final int ORDER_DAYS_AHEAD = 30;

    /**
     * Живые категории в одном порядке для формы «+» и «Настроек» (срез 3 п. 14, ANO-255): расходы, затем доходы;
     * внутри типа первыми — у кого больше живых строк, записей и планов, с датой от 90 дней назад до 30 вперёд.
     * Планы считаются, чтобы новый человек сразу после первого входа видел свои. При равенстве — как раньше:
     * характер, потом алфавит.
     *
     * @return список DTO категорий; пустой список если ни одной категории нет
     */
    public List<CategoryDto> findAll() {
        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        List<Integer> priorityOrder = List.of(
                Priority.HIGH.ordinal(), Priority.MEDIUM.ordinal(), Priority.LOW.ordinal());
        LocalDate today = LocalDate.now(clock);
        Map<UUID, Long> rows = new HashMap<>();
        for (Object[] r : categoryRepository.countRowsByCategory(today.minusDays(ORDER_DAYS_BACK), today.plusDays(ORDER_DAYS_AHEAD))) {
            rows.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return categoryRepository.findAllByDeletedFalse().stream()
                .sorted(Comparator
                        .comparing((Category c) -> c.getType().name())
                        .thenComparing((Category c) -> rows.getOrDefault(c.getId(), 0L), Comparator.reverseOrder())
                        .thenComparingInt(c -> priorityOrder.indexOf(c.getPriority().ordinal()))
                        .thenComparing(Category::getName, collator::compare))
                .map(this::toDto)
                .toList();
    }

    /**
     * Создаёт новую категорию с заданными именем, типом и флагом обязательности. Своя категория с именем
     * системной системной не становится (срез 3 п. 13): системную продукт находит по признаку вида.
     *
     * @param dto данные для создания категории
     * @return созданная категория
     */
    @Transactional
    public CategoryDto create(CategoryCreateDto dto) {
        Category category = Category.builder()
                .name(dto.name())
                .type(dto.type())
                .priority(dto.priority() != null ? dto.priority() : Priority.MEDIUM)
                .build();
        category.setForecastEnabled(dto.forecastEnabled() != null && dto.forecastEnabled());
        category.setPrimaryIncome(resolvePrimaryIncome(dto, dto.type(), false));
        return toDto(categoryRepository.save(category));
    }

    /**
     * Флаг «основной доход» осмыслен только для доходных категорий — на расходной он
     * молча гасится (в БД это же стережёт CHECK-констрейнт, ANO-35).
     */
    private boolean resolvePrimaryIncome(CategoryCreateDto dto, CategoryType type, boolean current) {
        boolean requested = dto.primaryIncome() != null ? dto.primaryIncome() : current;
        return type == CategoryType.INCOME && requested;
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
        category.setPrimaryIncome(resolvePrimaryIncome(dto, dto.type(), category.isPrimaryIncome()));
        return toDto(categoryRepository.save(category));
    }

    /**
     * Помечает категорию как удалённую (soft delete).
     * Связанные финансовые события при этом не удаляются.
     *
     * <p>А вот счета, у которых эта категория была зоной ответственности, ссылку теряют
     * (§8 спеки счетов: «Ссылка обнуляется, счёт остаётся»). Разница с событиями осознанная:
     * событию категория нужна как история («на что потрачено»), а счёту — как живой смысл
     * («эта карта обслуживает бензин»), и указывать на удалённую категорию этот смысл не несёт.
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
        accountService.clearPurposeCategory(id);
    }

    /**
     * Системная категория этого вида — в неё продукт пишет сам: переводы в копилки, траты на цели,
     * хотелки, платежи по кредиту ({@link SystemCategory}). Ищется по признаку вида (срез 3 п. 13), а не
     * по имени: своя категория с именем системной системной не становится.
     *
     * <p>Удаляет категории сам человек, а признак у владельца один (вместе с удалёнными): удалённая
     * «Переводы в копилки» срывала каждый перевод в копилку (ANO-224), «Цели» — удаление копилки (ревью
     * Codex на #139). Удалённая возвращается, отсутствующая заводится — системной категорией расходов;
     * признак основного дохода снимается, иначе тип расходов не сохранить
     * ({@code chk_primary_income_only_income}).
     *
     * @param kind вид системной категории
     * @return живая категория этого вида
     */
    @Transactional
    public Category systemCategory(SystemCategory kind) {
        Category category = categoryRepository.findBySystemKind(kind)
                .orElseGet(() -> Category.builder().name(freeName(kind)).systemKind(kind).build());
        if (category.getId() != null && !category.isDeleted()) return category;
        category.setDeleted(false);
        category.setSystem(true);
        category.setType(CategoryType.EXPENSE);
        category.setPrimaryIncome(false);
        // Записи системных категорий — не обычные траты: в прогнозе их медиана занижала бы свободные,
        // а выключателя у системной категории на экране нет (ревью Codex на #139).
        category.setForecastEnabled(false);
        return categoryRepository.save(category);
    }

    /**
     * Имя новой системной категории: имя у владельца уникально (uq_categories_owner_name), а своя категория
     * с ним может уже быть. «Накопление» тогда — под старым именем, как в V37; остальные — с «2».
     */
    private String freeName(SystemCategory kind) {
        if (!categoryRepository.existsByName(kind.title)) return kind.title;
        // ponytail: одна запасная попытка; занято и запасное имя — вставка откажет, как отказывала и до V37
        return kind == SystemCategory.FUND_TRANSFER ? "Переводы в копилки" : kind.title + " 2";
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
        return new CategoryDto(c.getId(), c.getName(), c.getType(), c.getPriority(), c.isSystem(),
                c.isForecastEnabled(), c.isPrimaryIncome(), c.getSystemKind());
    }
}
