package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
import java.util.Set;
import java.util.UUID;

public interface FinancialEventRepository extends JpaRepository<FinancialEvent, UUID> {

    List<FinancialEvent> findAllByDeletedFalseAndDateBetweenOrderByDateAsc(
            LocalDate start, LocalDate end);

    boolean existsByCategoryIdAndDeletedFalse(UUID categoryId);

    List<FinancialEvent> findAllByDeletedFalseAndDateBetween(LocalDate start, LocalDate end);

    Optional<FinancialEvent> findByIdempotencyKey(UUID idempotencyKey);

    /** All non-deleted events with given priority, ordered by createdAt. */
    List<FinancialEvent> findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(Priority priority);

    /** Хотелки: LOW-priority PLANNED события без даты ИЛИ с датой раньше начала текущего месяца. */
    @Query("SELECT e FROM FinancialEvent e WHERE e.deleted = false " +
           "AND e.priority = :priority AND e.status = :status " +
           "AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "AND (e.date IS NULL OR e.date < :cutoff) " +
           "ORDER BY e.createdAt ASC")
    List<FinancialEvent> findWishlistItems(
        @Param("priority") Priority priority,
        @Param("status") EventStatus status,
        @Param("cutoff") LocalDate cutoff);

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
     * Сумма фактически случившихся (любых записей с factAmount, включая PLAN-FUND_TRANSFER)
     * по типу в диапазоне дат {@code [from..to]} включительно. Используется в расчёте капитала.
     */
    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.factAmount IS NOT NULL
          AND e.deleted = false
          AND e.date >= :from
          AND e.date <= :to
        """)
    BigDecimal sumFactByTypeBetween(@Param("type") EventType type,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

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

    // --- Strategy ---

    /**
     * Все PLAN-события в диапазоне дат (включая recurring-материализованные).
     * Используется StrategyTimelineService для построения {@code balanceConfirmed} и breakdown будущих точек.
     */
    @Query("SELECT e FROM FinancialEvent e " +
           "WHERE e.deleted = false " +
           "  AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "  AND e.date >= :startDate AND e.date <= :endDate")
    List<FinancialEvent> findPlannedEventsByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    @Query("SELECT MIN(e.date) FROM FinancialEvent e " +
           "WHERE e.eventKind = ru.selfin.backend.model.EventKind.FACT " +
           "  AND e.deleted = false")
    Optional<LocalDate> findEarliestFactDate();

    // --- Recurring ---

    @Query("SELECT MAX(e.date) FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'PLANNED'")
    Optional<LocalDate> findMaxActiveDateByRule(@Param("ruleId") UUID ruleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FinancialEvent e SET e.deleted = true, e.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'PLANNED' " +
           "  AND e.date >= :fromDate")
    int softDeletePlanEventsByRuleFromDate(@Param("ruleId") UUID ruleId,
                                           @Param("fromDate") LocalDate fromDate);

    @Query("SELECT e.date FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'EXECUTED'")
    Set<LocalDate> findExecutedDatesByRule(@Param("ruleId") UUID ruleId);

    @Query("SELECT MAX(e.date) FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'EXECUTED'")
    Optional<LocalDate> findMaxExecutedDateByRule(@Param("ruleId") UUID ruleId);

    @Query("SELECT e FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.date = :date " +
           "  AND e.deleted = false")
    Optional<FinancialEvent> findActiveByRuleAndDate(@Param("ruleId") UUID ruleId,
                                                     @Param("date") LocalDate date);

    // --- Wishlist ---

    /** Все хотелки (LOW-события с явным статусом). Для страницы /wishlist. */
    @Query("SELECT e FROM FinancialEvent e " +
           "WHERE e.priority = ru.selfin.backend.model.enums.Priority.LOW " +
           "  AND e.wishlistStatus IS NOT NULL " +
           "  AND e.deleted = false")
    List<FinancialEvent> findAllWishlistEvents();

    /** Хотелки-события с конкретным статусом (например FIXED для timeline). */
    List<FinancialEvent> findByWishlistStatusAndDeletedFalse(
            ru.selfin.backend.model.enums.WishlistStatus status);

    // --- Pocket (ANO-12) ---

    /**
     * Просроченные обязательные расходы БЕЗ границы месяца (спека §3.4):
     * PLAN(PLANNED) HIGH EXPENSE с датой в прошлом и без FACT-детей.
     * Возвращает события (не сумму) — движку нужны details для breakdown.
     * wishlistStatus проверять не нужно: хотелки всегда LOW (DB constraint), HIGH-фильтр их исключает.
     */
    @Query("""
        SELECT e FROM FinancialEvent e
        WHERE e.eventKind = ru.selfin.backend.model.EventKind.PLAN
          AND e.type = ru.selfin.backend.model.enums.EventType.EXPENSE
          AND e.priority = ru.selfin.backend.model.enums.Priority.HIGH
          AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
          AND e.date < :today
          AND e.deleted = false
          AND NOT EXISTS (
              SELECT 1 FROM FinancialEvent f
              WHERE f.parentEventId = e.id AND f.deleted = false
          )
        """)
    List<FinancialEvent> findOverdueMandatoryExpenses(@Param("today") LocalDate today);

    /**
     * Дата ближайшего будущего плана-дохода ЛЮБОЙ категории (горизонт NEXT_INCOME, спека §4).
     * Хотелки исключены явно (income-хотелок не бывает, но фильтр дешёвый и страхует).
     */
    @Query("""
        SELECT MIN(e.date) FROM FinancialEvent e
        WHERE e.deleted = false
          AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN
          AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
          AND e.type = ru.selfin.backend.model.enums.EventType.INCOME
          AND e.wishlistStatus IS NULL
          AND e.date > :after AND e.date <= :until
        """)
    Optional<LocalDate> findNextPlannedIncomeDate(
        @Param("after") LocalDate after, @Param("until") LocalDate until);

    /** Хотелки нескольких статусов одним запросом (вход движка wishlistEvents, спека §3.1). */
    List<FinancialEvent> findByWishlistStatusInAndDeletedFalse(
            java.util.Collection<ru.selfin.backend.model.enums.WishlistStatus> statuses);
}
