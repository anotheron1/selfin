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

    /**
     * Самый свежий чекпоинт: по дате, при равных датах — по created_at (поздний побеждает).
     * Tiebreak обязателен (ANO-15 §4): ре-якорь дважды за день — типовой кейс «исправил
     * опечатку», без него якорь недетерминирован. НЕ заменять на derived-имя без ORDER BY!
     */
    @Query("SELECT cp FROM BalanceCheckpoint cp ORDER BY cp.date DESC, cp.createdAt DESC LIMIT 1")
    Optional<BalanceCheckpoint> findTopByOrderByDateDesc();

    /** Самый ранний чекпоинт — используется как нижняя граница в траектории капитала. */
    Optional<BalanceCheckpoint> findTopByOrderByDateAsc();

    /** Последний чекпоинт с {@code date ≤ asOfDate} (капитал на дату); tiebreak как выше. */
    @Query("""
        SELECT cp FROM BalanceCheckpoint cp WHERE cp.date <= :date
        ORDER BY cp.date DESC, cp.createdAt DESC LIMIT 1
        """)
    Optional<BalanceCheckpoint> findTopByDateLessThanEqualOrderByDateDesc(@Param("date") LocalDate date);

    /** Вся история чекпоинтов, от свежих к старым; tiebreak created_at (порядок drift-цепочки). */
    @Query("SELECT cp FROM BalanceCheckpoint cp ORDER BY cp.date DESC, cp.createdAt DESC")
    List<BalanceCheckpoint> findAllByOrderByDateDesc();

    /** Самая ранняя дата чекпоинта. Используется StrategyTimelineService.firstActivityMonth(). */
    @Query("SELECT MIN(b.date) FROM BalanceCheckpoint b")
    Optional<LocalDate> findEarliestCheckpointDate();
}
