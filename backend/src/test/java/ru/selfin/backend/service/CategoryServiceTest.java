// CategoryServiceTest.java
package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.SystemCategory;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock FinancialEventRepository eventRepository;
    @Mock AccountService accountService;
    CategoryService categoryService;

    /** Сегодня — 20 нояб. 2026: окно порядка категорий — с 21 авг. по 20 дек. */
    final Clock clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository, eventRepository, accountService, clock);
    }

    @Test
    void update_systemCategory_throwsWhenRenameAttempted() {
        UUID id = UUID.randomUUID();
        Category system = Category.builder()
                .id(id).name("Хотелки").type(CategoryType.EXPENSE)
                .priority(Priority.LOW).system(true).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(system));

        CategoryCreateDto dto = new CategoryCreateDto("Новое имя", CategoryType.EXPENSE, Priority.LOW, null);

        assertThatThrownBy(() -> categoryService.update(id, dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("System categories cannot be renamed");
    }

    @Test
    void update_typeChange_withActiveEvents_throws() {
        UUID id = UUID.randomUUID();
        Category cat = Category.builder()
                .id(id).name("Еда").type(CategoryType.EXPENSE)
                .priority(Priority.HIGH).system(false).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(cat));
        when(eventRepository.existsByCategoryIdAndDeletedFalse(id)).thenReturn(true);

        CategoryCreateDto dto = new CategoryCreateDto("Еда", CategoryType.INCOME, Priority.HIGH, null);

        assertThatThrownBy(() -> categoryService.update(id, dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot change type");
    }

    @Test
    void update_typeChange_noActiveEvents_succeeds() {
        UUID id = UUID.randomUUID();
        Category cat = Category.builder()
                .id(id).name("Еда").type(CategoryType.EXPENSE)
                .priority(Priority.HIGH).system(false).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(cat));
        when(eventRepository.existsByCategoryIdAndDeletedFalse(id)).thenReturn(false);
        when(categoryRepository.save(cat)).thenReturn(cat);

        // No exception expected
        categoryService.update(id, new CategoryCreateDto("Еда", CategoryType.INCOME, Priority.HIGH, null));
    }

    /**
     * §8 спеки счетов: удалённая категория-зона обнуляется у счёта, а счёт продолжает жить.
     * Событиям категория остаётся (это их история), счёту — нет (это его живой смысл:
     * «эта карта обслуживает бензин», и указывать на удалённую категорию он не может).
     * Само обнуление проверяет {@code AccountServiceTest.clearPurposeCategory_...}.
     */
    @Test
    void delete_clearsPurposeCategoryOnAccounts() {
        UUID id = UUID.randomUUID();
        Category fuel = Category.builder()
                .id(id).name("Бензин").type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM).system(false).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(fuel));

        categoryService.delete(id);

        assertThat(fuel.isDeleted()).isTrue();
        verify(accountService).clearPurposeCategory(id);
    }

    @Test
    void findAll_sortsByTypePriorityAndCyrillicName() {
        // Create categories in scrambled order to verify sorting
        Category zebra = Category.builder()
                .id(UUID.randomUUID())
                .name("Зарплата")
                .type(CategoryType.INCOME)
                .priority(Priority.HIGH)
                .system(false)
                .deleted(false)
                .build();

        Category yabloko = Category.builder()
                .id(UUID.randomUUID())
                .name("Яблоки")
                .type(CategoryType.INCOME)
                .priority(Priority.LOW)
                .system(false)
                .deleted(false)
                .build();

        Category autos = Category.builder()
                .id(UUID.randomUUID())
                .name("Авто")
                .type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM)
                .system(false)
                .deleted(false)
                .build();

        Category zhilyo = Category.builder()
                .id(UUID.randomUUID())
                .name("Жилье")
                .type(CategoryType.EXPENSE)
                .priority(Priority.HIGH)
                .system(false)
                .deleted(false)
                .build();

        Category edam = Category.builder()
                .id(UUID.randomUUID())
                .name("Еда")
                .type(CategoryType.EXPENSE)
                .priority(Priority.HIGH)
                .system(false)
                .deleted(false)
                .build();

        // Return categories in scrambled order
        when(categoryRepository.findAllByDeletedFalse())
                .thenReturn(List.of(zebra, yabloko, autos, zhilyo, edam));

        var result = categoryService.findAll();

        // Verify result is sorted: EXPENSE before INCOME, then by priority (HIGH→MEDIUM→LOW),
        // then by Cyrillic name within same type+priority.
        // EXPENSE/HIGH: Еда, Жилье  (Е < Ж alphabetically)
        // EXPENSE/MEDIUM: Авто
        // INCOME/HIGH: Зарплата
        // INCOME/LOW: Яблоки
        assertThat(result)
                .hasSize(5)
                .extracting(dto -> dto.name())
                .containsExactly("Еда", "Жилье", "Авто", "Зарплата", "Яблоки");
    }

    // ---------- Срез 3 п. 14: порядок категорий ----------

    @Test
    @DisplayName("Порядок: у кого больше живых строк с 90 дней назад до 30 вперёд — выше; при равенстве — характер, потом алфавит")
    void findAll_moreRowsInWindowFirst() {
        // Числа разводят ветки: по характеру и алфавиту было бы «Жильё, Авто, Продукты», по строкам — наоборот.
        Category auto = cat("Авто", CategoryType.EXPENSE, Priority.MEDIUM);
        Category food = cat("Продукты", CategoryType.EXPENSE, Priority.LOW);
        Category home = cat("Жильё", CategoryType.EXPENSE, Priority.HIGH);
        Category salary = cat("Зарплата", CategoryType.INCOME, Priority.HIGH);
        when(categoryRepository.findAllByDeletedFalse()).thenReturn(List.of(auto, food, home, salary));
        when(categoryRepository.countRowsByCategory(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 12, 20)))
                .thenReturn(List.of(new Object[]{food.getId(), 5L}, new Object[]{auto.getId(), 2L},
                        new Object[]{salary.getId(), 9L}));

        assertThat(categoryService.findAll()).extracting(dto -> dto.name())
                .as("расходы по-прежнему перед доходами: форма и «Настройки» делят их по типу")
                .containsExactly("Продукты", "Авто", "Жильё", "Зарплата");
    }

    // ---------- Срез 3 п. 13: системные — по признаку ----------

    @Test
    @DisplayName("Системная категория находится по признаку, а не по имени: переименованная — та же")
    void systemCategory_foundByKind() {
        Category mine = cat("Мой кредит", CategoryType.EXPENSE, Priority.MEDIUM);
        mine.setSystemKind(SystemCategory.CREDIT);
        when(categoryRepository.findBySystemKind(SystemCategory.CREDIT)).thenReturn(Optional.of(mine));

        assertThat(categoryService.systemCategory(SystemCategory.CREDIT)).isSameAs(mine);
    }

    @Test
    @DisplayName("Системной нет — заводится под своим именем, системной категорией расходов с признаком")
    void systemCategory_missing_isCreated() {
        when(categoryRepository.findBySystemKind(SystemCategory.FUND_TRANSFER)).thenReturn(Optional.empty());
        when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Category created = categoryService.systemCategory(SystemCategory.FUND_TRANSFER);

        assertThat(created.getName()).isEqualTo("Накопление");
        assertThat(created.getSystemKind()).isEqualTo(SystemCategory.FUND_TRANSFER);
        assertThat(created.isSystem()).isTrue();
        assertThat(created.getType()).isEqualTo(CategoryType.EXPENSE);
    }

    @Test
    @DisplayName("Своя категория с именем системной системной не становится — системная заводится рядом под другим именем")
    void systemCategory_ownCategoryWithSameName_isNotTaken() {
        when(categoryRepository.findBySystemKind(SystemCategory.CREDIT)).thenReturn(Optional.empty());
        when(categoryRepository.existsByName("Кредит")).thenReturn(true);
        when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Category created = categoryService.systemCategory(SystemCategory.CREDIT);

        assertThat(created.getName()).isEqualTo("Кредит 2");
        assertThat(created.getSystemKind()).isEqualTo(SystemCategory.CREDIT);
    }

    @Test
    @DisplayName("Своя «Накопление» уже есть — системная заводится под старым именем «Переводы в копилки», как в V37")
    void systemCategory_ownSavings_takesOldName() {
        when(categoryRepository.findBySystemKind(SystemCategory.FUND_TRANSFER)).thenReturn(Optional.empty());
        when(categoryRepository.existsByName("Накопление")).thenReturn(true);
        when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(categoryService.systemCategory(SystemCategory.FUND_TRANSFER).getName()).isEqualTo("Переводы в копилки");
    }

    @Test
    @DisplayName("Удалённая системная возвращается — та же строка, а не вторая (ANO-224)")
    void systemCategory_deleted_isRevived() {
        Category goals = cat("Цели", CategoryType.EXPENSE, Priority.MEDIUM);
        goals.setSystemKind(SystemCategory.GOAL_SPENDING);
        goals.setDeleted(true);
        when(categoryRepository.findBySystemKind(SystemCategory.GOAL_SPENDING)).thenReturn(Optional.of(goals));
        when(categoryRepository.save(goals)).thenReturn(goals);

        assertThat(categoryService.systemCategory(SystemCategory.GOAL_SPENDING)).isSameAs(goals);
        assertThat(goals.isDeleted()).isFalse();
        assertThat(goals.isSystem()).isTrue();
    }

    @Test
    @DisplayName("Новая «Хотелки» человека — своя категория: не системная и без признака")
    void create_withSystemName_isNotSystem() {
        when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        categoryService.create(new CategoryCreateDto("Хотелки", CategoryType.EXPENSE, Priority.LOW, null));

        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(saved.capture());
        assertThat(saved.getValue().isSystem()).isFalse();
        assertThat(saved.getValue().getSystemKind()).isNull();
    }

    private static Category cat(String name, CategoryType type, Priority priority) {
        return Category.builder().id(UUID.randomUUID()).name(name).type(type).priority(priority).build();
    }
}
