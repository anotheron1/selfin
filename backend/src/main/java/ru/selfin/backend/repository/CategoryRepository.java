package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findAllByDeletedFalse();

    List<Category> findAllByDeletedFalseAndType(CategoryType type);

    Optional<Category> findByNameAndDeletedFalse(String name);
}
