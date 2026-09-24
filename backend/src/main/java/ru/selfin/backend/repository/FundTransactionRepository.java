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
     * Суммарный баланс копилок-конвертов на дату {@code date} — используется в расчёте
     * капитала ({@code CapitalService.liquidAt}, спека §4.4).
     *
     * <p>Копилка, которая В ЭТОТ ДЕНЬ жила на счёте, сюда сознательно не попадает: её деньги
     * лежат внутри баланса счёта (учтён через {@code AccountBalanceService.freeMoneyAt}/
     * {@code semiLiquidAt}), и повторное сложение дало бы задвоение (ANO-9 Task 2.3, спека
     * §3.3/§4.4).
     *
     * <p><b>ANO-163: условие читает историю привязок, а не {@code t.fund.accountId}.</b> Здесь
     * стояло {@code t.fund.accountId IS NULL} — признак «на счёте СЕЙЧАС», применённый ко всем
     * прошлым датам. Привязка копилки сегодня уменьшала капитал за каждый прошлый месяц на её
     * взносы, отвязка возвращала. Замерено на стенде: минус 20 000 в августе от привязки
     * 16 сентября. <b>Возвращать признак нельзя</b> по той же причине, что и {@code deleted}
     * ниже. День привязки — день на счёте, день отвязки — день конверта
     * ({@link ru.selfin.backend.model.FundAccountLink}).
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
              AND NOT EXISTS (
                    SELECT 1 FROM FundAccountLink l
                    WHERE l.fundId = t.fund.id
                      AND l.linkedFrom <= :date
                      AND (l.linkedTo IS NULL OR l.linkedTo > :date))
            """)
    BigDecimal sumEnvelopeFundsByTransactionDateLessThanEqual(@Param("date") LocalDate date);

    /**
     * Сумма живых движений ОДНОЙ копилки (ANO-156, найдено ревью PR #42).
     *
     * <p>Выбытие копилки обязано обнулить именно эту сумму: её складывает
     * {@link #sumEnvelopeFundsByTransactionDateLessThanEqual}, и с ANO-156 фильтра по
     * удалённости там нет — любой остаток виден в капитале навсегда, за каждую дату.
     *
     * <p><b>Поле {@code current_balance} для этого не годится.</b> Оно может разойтись с
     * движениями: {@code TargetFundService.update} при отвязке копилки от счёта переносит в
     * поле остаток СЧЁТА, не создавая движения. Компенсация по полю оставляла разницу —
     * замерено на живой базе: −460 000 вместо нуля.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.fund.id = :fundId
              AND t.deleted = false
            """)
    BigDecimal sumLiveByFundId(@Param("fundId") UUID fundId);
}
