package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.BalanceCheckpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceCheckpointRepository extends JpaRepository<BalanceCheckpoint, UUID> {

    /** Самый свежий чекпоинт по дате (не по created_at). */
    Optional<BalanceCheckpoint> findTopByOrderByDateDesc();

    /** Вся история чекпоинтов, от свежих к старым. */
    List<BalanceCheckpoint> findAllByOrderByDateDesc();
}
