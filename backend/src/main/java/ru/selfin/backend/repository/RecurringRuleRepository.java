package ru.selfin.backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.RecurringRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, UUID> {

    /**
     * «Активное» = не soft-deleted И без end_date. Используется в lazy-extend.
     */
    @Query("SELECT r.id FROM RecurringRule r " +
           "WHERE r.deleted = false AND r.endDate IS NULL")
    List<UUID> findIndefiniteActiveIds();

    /**
     * Pessimistic lock на правиле для безопасного lazy-extend под конкурентным
     * доступом (см. spec → Секция 2 «Конкурентность»).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RecurringRule r WHERE r.id = :id")
    Optional<RecurringRule> findForUpdate(@Param("id") UUID id);
}
