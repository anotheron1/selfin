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
     * <p><b>ANO-156: фильтра по {@code t.fund.deleted} здесь НЕТ, и это важно.</b> Он стоял
     * тут с ANO-86 и закрывал одну дыру: ветка «потрачено на цель» не писала компенсирующее
     * движение, и деньги удалённой копилки висели в капитале вечно. Но флаг «удалена СЕЙЧАС»
     * применялся ко ВСЕМ прошлым датам, а {@code BaselineTimelineBuilder.buildPastPoints}
     * зовёт {@code cashLiquidAt} для каждого прошлого месяца — и удаление копилки сегодня
     * переписывало историю ликвида от даты первого взноса.
     *
     * <p>Теперь обе ветки удаления пишут движение (ANO-156), и флаг здесь не нужен: сумма
     * сама обнуляется с даты выбытия и сама сохраняет прошлое. <b>Возвращать фильтр нельзя</b>
     * — он снова сломает историю; если удалённая копилка вдруг снова начнёт давать деньги в
     * капитал, причину искать в отсутствующей компенсации, а не здесь.
     *
     * <p>Обещание спеки капитала ({@code 2026-05-10-capital-net-worth-design.md:66}) — что
     * {@code FUND_TRANSFER} и {@code FundTransaction} взаимно компенсируются — теперь
     * выполняется тем, чем и должно: парными записями, а не фильтром в запросе.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.deleted = false
              AND t.transactionDate <= :date
              AND t.fund.accountId IS NULL
            """)
    BigDecimal sumEnvelopeFundsByTransactionDateLessThanEqual(@Param("date") LocalDate date);
}
