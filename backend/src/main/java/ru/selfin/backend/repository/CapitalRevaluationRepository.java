package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.CapitalRevaluation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapitalRevaluationRepository extends JpaRepository<CapitalRevaluation, UUID> {

    /** Найти живую переоценку по id. */
    @Query("SELECT r FROM CapitalRevaluation r WHERE r.id = :id AND r.deleted = false")
    Optional<CapitalRevaluation> findActiveById(@Param("id") UUID id);

    /** История переоценок одного item'а: новейшие сверху, soft-deleted скрыты. */
    @Query("""
            SELECT r FROM CapitalRevaluation r
            WHERE r.itemId = :itemId
              AND r.deleted = false
            ORDER BY r.valuedAt DESC, r.createdAt DESC
            """)
    List<CapitalRevaluation> findHistoryByItemId(@Param("itemId") UUID itemId);

    /**
     * Срез значений всех живых item'ов на дату {@code asOfDate}.
     * Для каждого item'а возвращает последнюю живую переоценку с {@code valued_at ≤ asOfDate}
     * (tie-break по {@code created_at DESC}). Item'ы без подходящей переоценки в результат не попадают.
     *
     * <p>Реализация — один SQL с {@code DISTINCT ON} (PostgreSQL). Не делать N+1.
     */
    @Query(value = """
            SELECT DISTINCT ON (r.item_id)
                   r.item_id  AS itemId,
                   i.kind     AS kind,
                   r.value    AS value
            FROM capital_revaluations r
            JOIN capital_items i ON i.id = r.item_id
            WHERE r.is_deleted = false
              AND i.is_deleted = false
              AND r.valued_at <= :asOfDate
            ORDER BY r.item_id, r.valued_at DESC, r.created_at DESC
            """, nativeQuery = true)
    List<CapitalSnapshotProjection> snapshotAt(@Param("asOfDate") LocalDate asOfDate);

    /** Самая ранняя дата переоценки (для дефолтного {@code from} в траектории). */
    @Query("SELECT MIN(r.valuedAt) FROM CapitalRevaluation r WHERE r.deleted = false")
    Optional<LocalDate> findEarliestValuedAt();
}
