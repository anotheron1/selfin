package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.FundTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FundTransactionRepository extends JpaRepository<FundTransaction, UUID> {
    Optional<FundTransaction> findByIdempotencyKey(UUID idempotencyKey);

    boolean existsByIdempotencyKey(UUID idempotencyKey);

    List<FundTransaction> findByFundIdAndDeletedFalseAndTransactionDateAfter(UUID fundId, LocalDate since);

    /** Суммарный баланс всех копилок на дату {@code date} — используется в расчёте капитала. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.deleted = false
              AND t.transactionDate <= :date
            """)
    BigDecimal sumByTransactionDateLessThanEqual(@Param("date") LocalDate date);
}
