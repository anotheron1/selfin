package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.SystemCategory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findAllByDeletedFalse();

    List<Category> findAllByDeletedFalseAndType(CategoryType type);

    /** Категория по имени, удалённая тоже: имя уникально у владельца (uq_categories_owner_name). */
    Optional<Category> findByName(String name);

    boolean existsByName(String name);

    /** Системная категория этого вида, удалённая тоже: у владельца она одна (uq_categories_owner_system_kind, V37). */
    Optional<Category> findBySystemKind(SystemCategory kind);

    /**
     * Сколько у каждой категории живых строк — записей и планов — с датой в окне (порядок категорий, срез 3 п. 14).
     * Строка — {@code [id категории, число]}.
     */
    @Query("""
            SELECT e.category.id, COUNT(e) FROM FinancialEvent e
             WHERE e.deleted = false AND e.category IS NOT NULL AND e.date BETWEEN :from AND :to
             GROUP BY e.category.id
            """)
    List<Object[]> countRowsByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Категории, для которых разрешён прогноз PredictionService (поле {@code forecast_enabled = true}).
     * Используется StrategyTimelineService для построения fan chart.
     */
    List<Category> findAllByForecastEnabledTrueAndDeletedFalse();

    /**
     * Отмечена ли хоть одна категория как «основной доход» (ANO-35). Если нет —
     * горизонт «до дохода» считается по любому доходу, как раньше.
     */
    boolean existsByPrimaryIncomeTrueAndDeletedFalse();
}
