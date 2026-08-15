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
     *
     * <p>Допущение одного счёта: запрос слеп к {@code account}, что корректно ровно пока
     * счёт один (инвариант V20). Снимается в Task 2.4.
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

    /**
     * Последний чекпоинт счёта с {@code date ≤ t}; tiebreak created_at (ре-якорь дважды за день).
     * {@code JOIN FETCH cp.account} — счёт нужен сразу вызывающему
     * ({@link ru.selfin.backend.service.AccountBalanceService} читает {@code kind}/
     * {@code trackBalance} с него), а поле ленивое и вне транзакции кидает
     * {@code LazyInitializationException} (см. Javadoc {@link ru.selfin.backend.model.BalanceCheckpoint#getAccount()}).
     * Fetch join безопасен вместе с {@code LIMIT 1}: ассоциация {@code @ManyToOne}, не коллекция —
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

    /** История чекпоинтов одного счёта, от свежих к старым (цепочка дрейфа считается внутри счёта). */
    @Query("""
        SELECT cp FROM BalanceCheckpoint cp JOIN FETCH cp.account
        WHERE cp.account.id = :accountId
        ORDER BY cp.date DESC, cp.createdAt DESC
        """)
    List<BalanceCheckpoint> findAllForAccountOrderByDateDesc(@Param("accountId") UUID accountId);
}
