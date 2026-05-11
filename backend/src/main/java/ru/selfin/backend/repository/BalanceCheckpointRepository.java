package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.BalanceCheckpoint;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceCheckpointRepository extends JpaRepository<BalanceCheckpoint, UUID> {

    /** Самый свежий чекпоинт по дате (не по created_at). */
    Optional<BalanceCheckpoint> findTopByOrderByDateDesc();

    /** Самый ранний чекпоинт — используется как нижняя граница в траектории капитала. */
    Optional<BalanceCheckpoint> findTopByOrderByDateAsc();

    /** Последний чекпоинт с {@code date ≤ asOfDate} — нужен для расчёта капитала на дату. */
    @Query("SELECT cp FROM BalanceCheckpoint cp WHERE cp.date <= :date ORDER BY cp.date DESC LIMIT 1")
    Optional<BalanceCheckpoint> findTopByDateLessThanEqualOrderByDateDesc(@Param("date") LocalDate date);

    /** Вся история чекпоинтов, от свежих к старым. */
    List<BalanceCheckpoint> findAllByOrderByDateDesc();
}
