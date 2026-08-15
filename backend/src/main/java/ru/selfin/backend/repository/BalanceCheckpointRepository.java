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

    /** Самый ранний чекпоинт — используется как нижняя граница в траектории капитала. */
    Optional<BalanceCheckpoint> findTopByOrderByDateAsc();

    /** Вся история чекпоинтов, от свежих к старым; tiebreak created_at (порядок drift-цепочки). */
    @Query("SELECT cp FROM BalanceCheckpoint cp ORDER BY cp.date DESC, cp.createdAt DESC")
    List<BalanceCheckpoint> findAllByOrderByDateDesc();

    /** Самая ранняя дата чекпоинта. Используется StrategyTimelineService.firstActivityMonth(). */
    @Query("SELECT MIN(b.date) FROM BalanceCheckpoint b")
    Optional<LocalDate> findEarliestCheckpointDate();

    /**
     * Последний чекпоинт счёта с {@code date ≤ t}; tiebreak created_at (ре-якорь дважды за день).
     *
     * <p>{@code JOIN FETCH cp.account} — НЕ для этого метода и НЕ для {@link
     * ru.selfin.backend.service.AccountBalanceService}: тот получает {@code kind}/{@code trackBalance}
     * из параметра {@code Account a}, а не через {@code checkpoint.getAccount()}, и это поле
     * здесь вообще не разыменовывает. Fetch остался как задел с Task 2.1 (тогда планировалось,
     * что Task 2.4 будет читать счёт с чекпоинта именно этим методом); Task 2.4 в итоге пошла
     * другим путём (см. {@link #findAllByOrderByDateDesc}), но fetch по-прежнему безвреден и
     * оставлен.
     *
     * <p>Fetch join безопасен вместе с {@code LIMIT 1}: ассоциация {@code @ManyToOne}, не коллекция —
     * Hibernate не переключается на постраничную выборку в памяти (это ограничение касается только
     * fetch join коллекций), запрос уходит в БД одним SQL с {@code JOIN ... LIMIT 1}.
     */
    @Query("""
        SELECT cp FROM BalanceCheckpoint cp JOIN FETCH cp.account
        WHERE cp.account.id = :accountId AND cp.date <= :date
        ORDER BY cp.date DESC, cp.createdAt DESC LIMIT 1
        """)
    Optional<BalanceCheckpoint> findLatestForAccountAt(@Param("accountId") UUID accountId,
                                                        @Param("date") LocalDate date);
}
