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

    /** Для аналитики дашборда — все события за месяц */
    List<FinancialEvent> findAllByDeletedFalseAndDateBetween(LocalDate start, LocalDate end);

    /** Идемпотентность: ищем событие по ключу клиента */
    Optional<FinancialEvent> findByIdempotencyKey(UUID idempotencyKey);

    /** Хотелки: LOW-priority PLANNED события с датой раньше сегодня (нереализованные) */
    List<FinancialEvent> findAllByDeletedFalseAndPriorityAndStatusAndDateBeforeOrderByDateAsc(
            Priority priority, EventStatus status, LocalDate date);

    /**
     * Сумма эффективных сумм по типу события для расчёта баланса кармашка
     * (без привязки к дате — все события всех периодов).
     * Эффективная сумма: factAmount если исполнено (factAmount != null), иначе plannedAmount.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.factAmount IS NOT NULL THEN e.factAmount " +
           "ELSE e.plannedAmount END), 0) " +
           "FROM FinancialEvent e WHERE e.type = :type AND e.deleted = false")
    BigDecimal sumEffectiveByType(@Param("type") EventType type);

    /**
     * Сумма эффективных сумм по типу события начиная с указанной даты (включительно).
     * Используется для расчёта кармашка при наличии {@code BalanceCheckpoint}:
     * тогда к сумме чекпоинта прибавляются только события после него.
     * Эффективная сумма: factAmount если исполнено (factAmount != null), иначе plannedAmount.
     *
     * @param type     тип события (INCOME или EXPENSE)
     * @param fromDate нижняя граница дат событий (включительно)
     * @return сумма эффективных сумм; {@code 0} если событий нет
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.factAmount IS NOT NULL THEN e.factAmount " +
           "ELSE e.plannedAmount END), 0) " +
           "FROM FinancialEvent e WHERE e.type = :type AND e.deleted = false AND e.date >= :fromDate")
    BigDecimal sumEffectiveByTypeFromDate(@Param("type") EventType type, @Param("fromDate") LocalDate fromDate);
}
