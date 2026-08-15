package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Единственное место, где живёт правило «остаток счёта на дату» (спека §4.1).
 *
 * <p>Безадресные факты применяются ТОЛЬКО к дефолтному счёту: транзакции адреса не
 * имеют осознанно (§5.1), разнести их по счетам нечем. Остальные счета двигаются
 * только чекпоинтами — известное ограничение первой версии (§6). Фиктивных операций,
 * чтобы это обойти, не выдумываем: замерший остаток честнее мусора в аналитике.
 *
 * <p>NB: правило отбора фактов продублировано в {@link PocketEngine} — движок чистый и
 * суммирует факты сам. Схождение этих двух мест — предмет ANO-23, здесь не решается,
 * но при правке одного обязательно править второе.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBalanceService {

    /** «С начала времён» — та же граница, что {@code PocketInputAssembler.EPOCH}, для случая
     *  «чекпоинта нет вовсе» (ANO-28, см. {@link #noAnchorFallbackAt}). Значения самого по себе
     *  не имеет смысла сравнивать — важно только что оно раньше любых реальных данных. */
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private final AccountRepository accountRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final FinancialEventRepository eventRepository;

    public Optional<Account> defaultAccount() {
        return accountRepository.findByDefaultAccountTrueAndDeletedFalse();
    }

    public List<Account> active() {
        return accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc();
    }

    public Optional<BalanceCheckpoint> anchorAt(Account a, LocalDate t) {
        return checkpointRepository.findLatestForAccountAt(a.getId(), t);
    }

    /**
     * Остаток счёта на дату. Для CREDIT — доступный остаток.
     *
     * <p>Правило отбора фактов (см. {@link #factsDelta}) совпадает с шагом 1
     * {@link PocketEngine#calculate} ТОЛЬКО когда у счёта есть якорь. Без якоря поведение
     * СОЗНАТЕЛЬНО расходится: движок в этом случае суммирует ВСЕ факты на нулевую базу
     * (у него для этого своя строка breakdown — «по событиям, чекпоинта нет»), а этот метод
     * факты вообще не запрашивает и сразу возвращает ноль (план Task 2.1, §6 спеки — счёт без
     * чекпоинта «молчит»). Формулы одинаковые только при наличии чекпоинта — не «правило
     * совпадает» без оговорок.
     *
     * <p><b>{@code trackBalance} не проверяется.</b> Метод вернёт число по чекпоинту, даже
     * если {@code a.isTrackBalance() == false} (конверт без слежения, §5.2 — там пополнение
     * считается потраченным, а не остатком на счёте). Класс — единственное место правила
     * «остаток на дату», но фильтр по трекингу — ответственность вызывающего (см.
     * {@link #freeMoneyAt}, {@link #semiLiquidAt}: оба фильтруют счета ДО вызова этого
     * метода).
     *
     * <p><b>Решено в Task 3.1: так и остаётся контрактом вызывающего, guard'а внутри не будет.</b>
     * У метода появился законный потребитель, которому нужен остаток счёта, НЕ дающего свободных
     * денег: карточка кредитки на экране счетов показывает доступный остаток, а кредитка по
     * определению не {@code countsAsFreeMoney}. Guard внутри метода запретил бы именно этот
     * сценарий. Фильтр по трекингу остаётся там, где живёт смысл фильтра — у вызывающего.
     */
    public BigDecimal balanceAt(Account a, LocalDate t) {
        Optional<BalanceCheckpoint> anchor = anchorAt(a, t);
        if (anchor.isEmpty()) return BigDecimal.ZERO;
        BigDecimal base = anchor.get().getAmount();
        if (!a.isDefaultAccount()) return base;
        return base.add(factsDelta(anchor.get().getDate(), t));
    }

    /**
     * Полная сумма свободных денег по ВСЕМ участвующим счетам, ВКЛЮЧАЯ дефолтный (§4.1, §4.4).
     * Нужна капиталу ({@link CapitalService#liquidAt}), которому — в отличие от движка
     * кармашка — никто не даёт остаток дефолтного счёта отдельно как {@code currentBalance}
     * (Task 2.1 «Поправки после ревью», добавлено в Task 2.3).
     */
    public BigDecimal freeMoneyAt(LocalDate t) {
        return active().stream()
                .filter(Account::countsAsFreeMoney)
                .map(a -> balanceAt(a, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Сколько на копилке на самом деле (§3.3). У копилки С {@code accountId} это остаток
     * СЧЁТА, а не собственное поле {@code currentBalance}: держать два числа за одни деньги
     * значит гарантированно их разъехать. Копилка со счётом становится целью и датой поверх
     * чужого остатка, а не отдельным кошельком.
     *
     * <p>Счёт без чекпоинта даёт ноль (§8: «Копилка ссылается на счёт, у которого нет
     * чекпоинта → баланс копилки = 0, прогресс не показывается»). Удалённый счёт — тоже ноль:
     * денег на нём для нас больше нет.
     *
     * <p>Живёт здесь, а не в {@code TargetFundService}, потому что потребителей правила трое
     * ({@code TargetFundService}, {@code PocketInputAssembler}, {@code PocketSandboxService}),
     * и разъехавшись, они дали бы три разных ответа на «сколько уже накоплено».
     */
    public BigDecimal fundBalanceAt(TargetFund f, LocalDate t) {
        if (f.getAccountId() == null) {
            return f.getCurrentBalance() != null ? f.getCurrentBalance() : BigDecimal.ZERO;
        }
        return accountRepository.findById(f.getAccountId())
                .filter(a -> !a.isDeleted())
                .map(a -> balanceAt(a, t))
                .orElse(BigDecimal.ZERO);
    }

    /** Полу-ликвид: вклады (§4.3). */
    public BigDecimal semiLiquidAt(LocalDate t) {
        return active().stream()
                .filter(Account::isSemiLiquid)
                .map(a -> balanceAt(a, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Долг по кредиткам для капитала (§4.4). Считается от лимита, а не от планки. */
    public BigDecimal creditDebtAt(LocalDate t) {
        return creditGap(t, Account::getCreditLimit);
    }

    /**
     * Долг ОДНОГО кредитного счёта на дату — для карточки счёта (§5.3). Ноль означает и
     * «долга нет», и «считать нечем» (нет лимита либо нет чекпоинта): различить эти случаи
     * может только вызывающий, у которого якорь уже на руках — см. {@code AccountService.toDto},
     * он отдаёт наружу {@code null}, а не ноль (§8, «счёт молчит»).
     */
    public BigDecimal creditDebtAt(Account a, LocalDate t) {
        return creditGapFor(a, t, Account::getCreditLimit);
    }

    /**
     * ANO-28 фолбэк для ДЕФОЛТНОГО счёта БЕЗ чекпоинта на дату {@code t}: сумма ВСЕХ знаковых
     * фактов на нулевой базе, без нижней границы по дате якоря — то же самое, что делает
     * {@link PocketEngine#calculate}, когда чекпоинта нет вовсе (пустой {@code checkpointAmount}
     * + инлайн-цикл по всем событиям без {@code checkpointDate}-отсечки). У кармашка это
     * поведение сознательно сохранено (пользователь без единого введённого остатка не должен
     * потерять всю историю фактов из числа) и покрыто регрессией.
     *
     * <p>{@link #balanceAt} для счёта без чекпоинта возвращает ноль — и для его прямого
     * назначения (свободные деньги ДРУГИХ счетов, §4.1) это верно: у не-дефолтных счетов
     * действительно нет фактов, замерший ноль честен (§6 спеки). Но для ДЕФОЛТНОГО счёта эта
     * формула не годится потребителям, у которых, в отличие от {@link PocketInputAssembler}, нет
     * готового {@code currentBalance} движка кармашка — единственный такой потребитель сейчас
     * {@link CapitalService#liquidAt}. Без этого метода ликвид капитала молча обнулялся бы у
     * пользователя без единого введённого остатка (найдено ревью ANO-9 Task 2.3 на реальных
     * данных: факт дохода 90 000 без чекпоинта давал {@code liquid = 0} вместо {@code 90 000} —
     * капитал и кармашек снова разошлись, ровно там, где пользователь новый).
     *
     * <p><b>Не подмешан в {@link #balanceAt}/{@link #freeMoneyAt}/{@link #snapshot} сознательно.</b>
     * В {@link #snapshot} дефолтный счёт либо исключён явно через {@code excludedAccountId}, либо
     * (вырожденный случай «чекпоинта нет вовсе», исключать нечего — id пустой) и так дал бы ноль
     * через {@code balanceAt}, а PocketEngine ПАРАЛЛЕЛЬНО уже сам просуммировал эти же факты своим
     * инлайн-циклом. Если бы это правило жило внутри {@code balanceAt}, снимок отдал бы ту же
     * сумму фактов ЕЩЁ РАЗ через {@code otherAccountsBalance} — кармашек задвоил бы факты ровно в
     * сценарии «дефолтного счёта без чекпоинта», том самом крае, который ANO-28 защищает.
     *
     * @return ноль, если у дефолтного счёта ЕСТЬ чекпоинт на дату {@code t} (тогда всё уже верно
     *         посчитано через {@link #freeMoneyAt}) или дефолтного счёта нет вовсе.
     */
    public BigDecimal noAnchorFallbackAt(LocalDate t) {
        Optional<Account> defaultAcc = defaultAccount();
        if (defaultAcc.isEmpty() || anchorAt(defaultAcc.get(), t).isPresent()) return BigDecimal.ZERO;
        return factsDelta(EPOCH, t);
    }

    /**
     * Три производных суммы (§4.1–§4.3) за ОДИН проход по {@link #active()} — вызывается
     * один раз на запрос из {@link PocketInputAssembler} вместо трёх отдельных обходов
     * (Task 2.1 «Поправки после ревью», п.3). Счетов в системе единицы, пакетная выборка
     * якорей не нужна — важна только стабильность точки вызова.
     *
     * @param excludedAccountId счёт, чей остаток уже учтён отдельно как {@code currentBalance}
     *        движка, и его нельзя засчитать ещё раз внутри {@code otherAccountsBalance}.
     *        В текущем и единственном вызывающем ({@link PocketInputAssembler}) это всегда id
     *        дефолтного счёта — currentBalance движка с этой правки (ANO-9, ревью Task 2.2)
     *        считается строго от {@code accountBalanceService.anchorAt(defaultAccount, t)}, а
     *        не от глобально последнего чекпоинта по всей таблице, как было раньше. Параметр
     *        остаётся явным id, а не жёсткой проверкой на {@code isDefaultAccount()} внутри
     *        метода: какой именно счёт уже учтён отдельно — знание вызывающего, а не этого
     *        сервиса. {@code null} — не исключать никого; используется, когда якоря вообще нет
     *        (тогда у каждого счёта {@link #balanceAt} и так вернёт ноль).
     */
    public Snapshot snapshot(LocalDate t, UUID excludedAccountId) {
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal semiLiquid = BigDecimal.ZERO;
        BigDecimal creditReserve = BigDecimal.ZERO;
        for (Account a : active()) {
            if (a.countsAsFreeMoney() && !Objects.equals(a.getId(), excludedAccountId)) {
                other = other.add(balanceAt(a, t));
            }
            if (a.isSemiLiquid()) {
                semiLiquid = semiLiquid.add(balanceAt(a, t));
            }
            creditReserve = creditReserve.add(creditGapFor(a, t, Account::getAvailableFloor));
        }
        return new Snapshot(other, creditReserve, semiLiquid);
    }

    /** Результат {@link #snapshot}: свободные деньги прочих счетов, резерв возврата, полу-ликвид. */
    public record Snapshot(BigDecimal otherAccountsBalance, BigDecimal creditRestoreReserve,
                            BigDecimal semiLiquidBalance) {}

    private BigDecimal creditGap(LocalDate t, java.util.function.Function<Account, BigDecimal> level) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Account a : active()) {
            sum = sum.add(creditGapFor(a, t, level));
        }
        return sum;
    }

    /**
     * Разрыв «цель − доступно» для ОДНОГО кредитного счёта (0, если счёт не CREDIT, цель
     * не задана или чекпоинта нет). Общая точка для {@link #creditGap} (используется
     * {@link #creditDebtAt}) и {@link #snapshot} (резерв возврата к планке §4.2, инлайн) —
     * раньше {@code snapshot} держал вторую копию этого правила инлайн, и они были обязаны
     * меняться синхронно, что легко упустить при следующей правке.
     */
    private BigDecimal creditGapFor(Account a, LocalDate t, java.util.function.Function<Account, BigDecimal> level) {
        if (a.getKind() != AccountKind.CREDIT) return BigDecimal.ZERO;
        BigDecimal target = level.apply(a);
        if (target == null) return BigDecimal.ZERO;
        Optional<BalanceCheckpoint> anchor = anchorAt(a, t);
        if (anchor.isEmpty()) return BigDecimal.ZERO; // нет остатка — счёт молчит, а не считает ноль
        BigDecimal gap = target.subtract(anchor.get().getAmount());
        return gap.signum() > 0 ? gap : BigDecimal.ZERO;
    }

    /**
     * Знаковая сумма фактов в {@code (from, to]}. Вызывается только когда якорь уже найден —
     * см. {@link #balanceAt}, там же оговорено единственное расхождение с движком (поведение
     * без якоря). При наличии якоря правило отбора совпадает с шагом 1
     * {@link PocketEngine#calculate}: только факты, не-хотелки, дата не позже {@code to}
     * и строго после даты якоря — операции дня чекпоинта уже внутри его суммы (ANO-15 §5).
     */
    private BigDecimal factsDelta(LocalDate from, LocalDate to) {
        return eventRepository.findAllByDeletedFalseAndDateBetween(from, to).stream()
                .filter(e -> e.getFactAmount() != null)
                .filter(e -> e.getWishlistStatus() == null)
                .filter(e -> e.getDate() != null && e.getDate().isAfter(from))
                .map(e -> e.getType() == EventType.INCOME
                        ? e.getFactAmount() : e.getFactAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
