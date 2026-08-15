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

    /**
     * Суммарный баланс копилок БЕЗ привязки к счёту на дату {@code date} — используется в
     * расчёте капитала ({@code CapitalService.liquidAt}, спека §4.4).
     *
     * <p>{@code fund.accountId IS NULL} — копилки, У КОТОРЫХ ЗАДАН {@code accountId}, сюда
     * сознательно не попадают: их деньги уже лежат внутри баланса своего счёта (учтён через
     * {@code AccountBalanceService.freeMoneyAt}/{@code semiLiquidAt}), и повторное сложение
     * дало бы задвоение (ANO-9 Task 2.3, спека §3.3/§4.4).
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.deleted = false
              AND t.transactionDate <= :date
              AND t.fund.accountId IS NULL
            """)
    BigDecimal sumByTransactionDateLessThanEqual(@Param("date") LocalDate date);
}
