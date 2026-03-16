package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CategoryServiceTest {

    private final CategoryRepository repo = mock(CategoryRepository.class);
    private final CategoryService service = new CategoryService(repo);

    @Test
    @DisplayName("findAll: категории отсортированы по имени в русском алфавитном порядке")
    void findAll_sortsCyrillicAlphabetically() {
        when(repo.findAllByDeletedFalse()).thenReturn(List.of(
                cat("Еда"),
                cat("Бензин"),
                cat("Аренда"),
                cat("Здоровье")
        ));

        List<String> names = service.findAll().stream()
                .map(dto -> dto.name())
                .toList();

        assertThat(names).containsExactly("Аренда", "Бензин", "Еда", "Здоровье");
    }

    private Category cat(String name) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }
}
