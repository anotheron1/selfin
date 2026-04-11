package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialEventRepository extends JpaRepository<FinancialEvent, UUID> {

    List<FinancialEvent> findAllByDeletedFalseAndDateBetweenOrderByDateAsc(
            LocalDate start, LocalDate end);

    List<FinancialEvent> findAllByDeletedFalseAndDateBetween(LocalDate start, LocalDate end);

    Optional<FinancialEvent> findByIdempotencyKey(UUID idempotencyKey);

    /** Хотелки: LOW-priority PLANNED события без даты ИЛИ с датой раньше сегодня. */
    @Query("SELECT e FROM FinancialEvent e WHERE e.deleted = false " +
           "AND e.priority = :priority AND e.status = :status " +
           "AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "AND (e.date IS NULL OR e.date < :today) " +
           "ORDER BY e.createdAt ASC")
    List<FinancialEvent> findWishlistItems(
        @Param("priority") Priority priority,
        @Param("status") EventStatus status,
        @Param("today") LocalDate today);

    /**
     * Сумма эффективных сумм по типу для расчёта баланса кармашка (без привязки к дате).
     * После миграции:
     *   FACT.factAmount              → реально потраченное/полученное
     *   PLAN(PLANNED).plannedAmount  → запланированное (ещё не исполненное)
     *   PLAN(EXECUTED).planned       → 0 (исполнение уже учтено через FACT-записи)
     */
    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.FACT
                THEN e.factAmount
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.PLAN
                 AND e.status <> ru.selfin.backend.model.enums.EventStatus.EXECUTED
                THEN e.plannedAmount
            ELSE 0
        END), 0)
        FROM FinancialEvent e WHERE e.type = :type AND e.deleted = false
        """)
    BigDecimal sumEffectiveByType(@Param("type") EventType type);

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.FACT
                THEN e.factAmount
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.PLAN
                 AND e.status <> ru.selfin.backend.model.enums.EventStatus.EXECUTED
                THEN e.plannedAmount
            ELSE 0
        END), 0)
        FROM FinancialEvent e WHERE e.type = :type AND e.deleted = false AND e.date >= :fromDate
        """)
    BigDecimal sumEffectiveByTypeFromDate(@Param("type") EventType type, @Param("fromDate") LocalDate fromDate);

    /**
     * Сумма фактических (только FACT-записи) по типу.
     * После миграции factAmount гарантированно есть только у FACT-записей.
     */
    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.deleted = false
        """)
    BigDecimal sumFactExecutedByType(@Param("type") EventType type);

    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.deleted = false AND e.date >= :fromDate
        """)
    BigDecimal sumFactExecutedByTypeFromDate(@Param("type") EventType type, @Param("fromDate") LocalDate fromDate);

    /** Планировщик фондов: все не-удалённые PLANы с любым статусом кроме CANCELLED */
    @Query("SELECT e FROM FinancialEvent e WHERE e.deleted = false " +
           "AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "AND e.status <> :excludeStatus")
    List<FinancialEvent> findAllByDeletedFalseAndStatusNot(@Param("excludeStatus") EventStatus excludeStatus);

    /**
     * Агрегаты фактов по родительским планам для обогащения DTO.
     * Возвращает (parentEventId, count, totalAmount) для каждого уникального parentEventId из списка.
     */
    @Query("""
        SELECT e.parentEventId as parentEventId, COUNT(e) as count, SUM(e.factAmount) as totalAmount
        FROM FinancialEvent e
        WHERE e.parentEventId IN :planIds
          AND e.deleted = false
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
        GROUP BY e.parentEventId
        """)
    List<FactAggregateProjection> findFactAggregatesByPlanIds(@Param("planIds") List<UUID> planIds);

    /**
     * Сумма фактических сумм по типу без фильтра eventKind.
     * Необходим для FUND_TRANSFER: события, созданные через doTransfer, имеют
     * eventKind=PLAN (DB default), а не FACT, поэтому стандартный
     * sumFactExecutedByType их не видит.
     */
    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.factAmount IS NOT NULL
          AND e.deleted = false
        """)
    BigDecimal sumAllFactByType(@Param("type") EventType type);

    /** Аналог {@link #sumAllFactByType} с фильтром по дате. */
    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.factAmount IS NOT NULL
          AND e.deleted = false AND e.date >= :fromDate
        """)
    BigDecimal sumAllFactByTypeFromDate(@Param("type") EventType type, @Param("fromDate") LocalDate fromDate);

    /**
     * FACT-записи в диапазоне дат (для FundPlannerService).
     * Возвращает только eventKind=FACT записи с factAmount != null.
     */
    @Query("""
        SELECT e FROM FinancialEvent e
        WHERE e.deleted = false
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.date >= :startDate AND e.date <= :endDate
        """)
    List<FinancialEvent> findFactsByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    /**
     * Просроченные обязательные (HIGH) расходы текущего месяца, которые ещё не исполнены
     * и не имеют привязанного FACT-ребёнка (чтобы не резервировать дважды).
     * Используется для резервирования в балансе кармашка и прогнозах.
     */
    @Query("""
        SELECT COALESCE(SUM(e.plannedAmount), 0) FROM FinancialEvent e
        WHERE e.eventKind = ru.selfin.backend.model.EventKind.PLAN
          AND e.type = ru.selfin.backend.model.enums.EventType.EXPENSE
          AND e.priority = ru.selfin.backend.model.enums.Priority.HIGH
          AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
          AND e.date >= :monthStart
          AND e.date < :today
          AND e.deleted = false
          AND NOT EXISTS (
              SELECT 1 FROM FinancialEvent f
              WHERE f.parentEventId = e.id
                AND f.deleted = false
          )
        """)
    BigDecimal sumOverdueMandatoryExpenses(
        @Param("monthStart") LocalDate monthStart,
        @Param("today") LocalDate today);
}
