package ru.selfin.backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.dto.IndefinitePlanRuleDto;
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

    @Query("""
            SELECT new ru.selfin.backend.dto.IndefinitePlanRuleDto(
                r.id, r.category.id, r.eventType, r.plannedAmount, r.priority, r.description, r.frequency,
                r.dayOfMonth, r.monthOfYear, r.startDate,
                (SELECT max(e.date) FROM FinancialEvent e
                 WHERE e.recurringRule.id = r.id AND e.deleted = false AND e.status = 'PLANNED')
            )
            FROM RecurringRule r
            WHERE r.deleted = false AND r.endDate IS NULL AND r.eventType IN ('EXPENSE', 'INCOME')
            ORDER BY r.id
            """)
    List<IndefinitePlanRuleDto> findIndefinitePlanRules();

    /**
     * Pessimistic lock на правиле для безопасного lazy-extend под конкурентным
     * доступом (см. spec → Секция 2 «Конкурентность»).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RecurringRule r WHERE r.id = :id")
    Optional<RecurringRule> findForUpdate(@Param("id") UUID id);
}
