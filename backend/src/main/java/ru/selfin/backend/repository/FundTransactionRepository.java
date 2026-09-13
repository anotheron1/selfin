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
     *
     * <p><b>ANO-86: фильтр {@code t.fund.deleted} обязателен.</b> Без него удалённая копилка
     * продолжала давать деньги в ликвид: событие {@code FUND_TRANSFER} уже вычло их из
     * остатка счёта, а движение осталось живым — и одна и та же сумма оказывалась
     * одновременно НЕДОСТУПНОЙ (вернуть её было нечем) и ПОСЧИТАННОЙ (входила в капитал).
     * Спека капитала ({@code 2026-05-10-capital-net-worth-design.md:66}) обещает, что
     * {@code FUND_TRANSFER} и {@code FundTransaction} взаимно компенсируются; этот фильтр и
     * есть условие обещания. Удаление копилки убирало одну половину компенсации и оставляло
     * вторую.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.deleted = false
              AND t.fund.deleted = false
              AND t.transactionDate <= :date
              AND t.fund.accountId IS NULL
            """)
    BigDecimal sumEnvelopeFundsByTransactionDateLessThanEqual(@Param("date") LocalDate date);
}
