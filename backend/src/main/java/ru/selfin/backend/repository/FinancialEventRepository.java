package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.FinancialEvent;

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
}
