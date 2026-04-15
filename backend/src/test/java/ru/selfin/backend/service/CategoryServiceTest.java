// CategoryServiceTest.java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock FinancialEventRepository eventRepository;
    @InjectMocks CategoryService categoryService;

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
}
