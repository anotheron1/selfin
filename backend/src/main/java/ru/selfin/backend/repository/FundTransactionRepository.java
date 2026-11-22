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

    /** Живые движения одной копилки — темп на «Целях» (Р9) ищет в них первый день пополнений. */
    List<FundTransaction> findByFundIdAndDeletedFalse(UUID fundId);

    /**
     * Суммарный баланс копилок-конвертов на дату {@code date} — сколько денег основной карты уже
     * отложено (Р10-А, ANO-212; {@code AccountBalanceService.envelopesAt}). Ядро вычитает его из
     * свободных строкой «Уже в копилках», прошлые месяцы «Стратегии» — из остатка на конец месяца.
     * До Р10-А его прибавлял к остатку капитал: перевод вычитался из карты, а копилка хранила те же
     * деньги.
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
     * — он снова сломает историю; если удалённая копилка вдруг снова начнёт вычитаться из
     * свободных, причину искать в отсутствующей компенсации, а не здесь.
     */
    /*
     * Срез 4, п. 6 (ANO-262): движение, сделанное в день, когда копилка лежала на счёте, — деньги, ушедшие с карты на
     * тот счёт. Отложенными на карте их нет ни тогда, ни после отвязки: иначе отвязанная копилка держала бы деньги,
     * которых на карте нет (ANO-163). Поэтому второе условие — по дню движения.
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
              AND NOT EXISTS (
                    SELECT 1 FROM FundAccountLink l
                    WHERE l.fundId = t.fund.id
                      AND l.linkedFrom <= t.transactionDate
                      AND (l.linkedTo IS NULL OR l.linkedTo > t.transactionDate))
            """)
    BigDecimal sumEnvelopeFundsByTransactionDateLessThanEqual(@Param("date") LocalDate date);

    /**
     * Сколько ОДНА копилка держит конвертом: живые движения, сделанные не на счёте (ANO-156, найдено ревью PR #42;
     * срез 4, п. 6).
     *
     * <p>Выбытие копилки обязано обнулить именно эту сумму: её складывает
     * {@link #sumEnvelopeFundsByTransactionDateLessThanEqual}, и с ANO-156 фильтра по
     * удалённости там нет — любой остаток виден в капитале навсегда, за каждую дату. Отвязка от счёта
     * возвращает копилке её же (ANO-158): переводы, сделанные на счёт, остались на счёте.
     *
     * <p><b>Поле {@code current_balance} для этого не годится.</b> Оно может разойтись с
     * движениями: {@code TargetFundService.update} при отвязке копилки от счёта переносил в
     * поле остаток СЧЁТА, не создавая движения. Компенсация по полю оставляла разницу —
     * замерено на живой базе: −460 000 вместо нуля.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.fund.id = :fundId
              AND t.deleted = false
              AND NOT EXISTS (
                    SELECT 1 FROM FundAccountLink l
                    WHERE l.fundId = t.fund.id
                      AND l.linkedFrom <= t.transactionDate
                      AND (l.linkedTo IS NULL OR l.linkedTo > t.transactionDate))
            """)
    BigDecimal sumEnvelopeMovesByFundId(@Param("fundId") UUID fundId);
}
