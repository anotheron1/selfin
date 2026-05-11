package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapitalItemRepository extends JpaRepository<CapitalItem, UUID> {

    /** Найти живой item по id (с фильтром {@code deleted=false}). */
    @Query("SELECT i FROM CapitalItem i WHERE i.id = :id AND i.deleted = false")
    Optional<CapitalItem> findActiveById(@Param("id") UUID id);

    /** Все живые item'ы, опционально отфильтрованные по типу. */
    @Query("""
            SELECT i FROM CapitalItem i
            WHERE i.deleted = false
              AND (:kind IS NULL OR i.kind = :kind)
            ORDER BY i.createdAt ASC
            """)
    List<CapitalItem> findAllActive(@Param("kind") CapitalItemKind kind);
}
