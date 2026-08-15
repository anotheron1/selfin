package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AccountBalanceService} — единственное место правила «остаток счёта на дату»
 * (спека 2026-08-12-accounts-skeleton-design.md §4.1–§4.4). Моки репозиториев, образец
 * подхода — {@link BalanceCheckpointServiceTest}.
 */
class AccountBalanceServiceTest {

    private AccountRepository accountRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private FinancialEventRepository eventRepository;
    private AccountBalanceService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        eventRepository = mock(FinancialEventRepository.class);
        service = new AccountBalanceService(accountRepository, checkpointRepository, eventRepository);
    }

    // ── хелперы ──────────────────────────────────────────────────────────────

    private static BalanceCheckpoint anchor(Account account, LocalDate date, long amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(BigDecimal.valueOf(amount))
                .account(account)
                .build();
    }

    private static FinancialEvent fact(LocalDate date, EventType type, long amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).date(date).type(type)
                .eventKind(EventKind.FACT).factAmount(BigDecimal.valueOf(amount))
                .status(EventStatus.EXECUTED).priority(Priority.MEDIUM).deleted(false)
                .build();
    }

    private static FinancialEvent wishlistFact(LocalDate date, long amount) {
        FinancialEvent e = fact(date, EventType.EXPENSE, amount);
        e.setWishlistStatus(WishlistStatus.FIXED);
        return e;
    }

    /** PLAN без факта — planned, но ещё не исполнен; не должен считаться в balanceAt. */
    private static FinancialEvent planWithoutFact(LocalDate date, long plannedAmount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).date(date).type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN).plannedAmount(BigDecimal.valueOf(plannedAmount))
                .status(EventStatus.PLANNED).priority(Priority.MEDIUM).deleted(false)
                .build();
    }

    // ── 1. balanceAt дефолтного счёта ───────────────────────────────────────

    @Test
    @DisplayName("balanceAt дефолтного счёта = якорь + знаковые факты строго после даты якоря, не позже t")
    void balanceAt_defaultAccount_addsSignedFactsStrictlyAfterAnchorUpToT() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate anchorDate = LocalDate.of(2026, 3, 8);
        LocalDate t = LocalDate.of(2026, 3, 10);
        BalanceCheckpoint cp = anchor(defaultAccount, anchorDate, 50_000);

        when(checkpointRepository.findLatestForAccountAt(defaultAccount.getId(), t))
                .thenReturn(Optional.of(cp));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(anchorDate, t))
                .thenReturn(List.of(
                        fact(LocalDate.of(2026, 3, 9), EventType.EXPENSE, 3_000),
                        fact(t, EventType.INCOME, 8_000)
                ));

        BigDecimal balance = service.balanceAt(defaultAccount, t);

        // 50 000 − 3 000 + 8 000 = 55 000
        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(55_000));
    }

    // ── 2. balanceAt не дефолтного счёта ────────────────────────────────────

    @Test
    @DisplayName("balanceAt НЕ дефолтного счёта = якорь без фактов — известное ограничение §6")
    void balanceAt_nonDefaultAccount_ignoresFacts() {
        Account other = AccountFixtures.account(AccountKind.DEBIT, true).build();
        LocalDate anchorDate = LocalDate.of(2026, 3, 8);
        LocalDate t = LocalDate.of(2026, 3, 20);
        BalanceCheckpoint cp = anchor(other, anchorDate, 20_000);

        when(checkpointRepository.findLatestForAccountAt(other.getId(), t))
                .thenReturn(Optional.of(cp));

        BigDecimal balance = service.balanceAt(other, t);

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(20_000));
        // Не-дефолтные счета факты вообще не запрашивают — деньги двигаются только чекпоинтами.
        verifyNoInteractions(eventRepository);
    }

    // ── 3. balanceAt счёта без чекпоинта ─────────────────────────────────────

    @Test
    @DisplayName("balanceAt счёта без чекпоинта = ноль")
    void balanceAt_noCheckpoint_returnsZero() {
        Account other = AccountFixtures.account(AccountKind.DEBIT, true).build();
        LocalDate t = LocalDate.of(2026, 3, 20);
        when(checkpointRepository.findLatestForAccountAt(other.getId(), t)).thenReturn(Optional.empty());

        BigDecimal balance = service.balanceAt(other, t);

        assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── правило отбора фактов (отдельно) ────────────────────────────────────

    @Test
    @DisplayName("Отбор фактов: якорный день исключён, хотелка исключена, событие без факта исключено")
    void balanceAt_defaultAccount_factSelectionRuleMatchesPocketEngine() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate anchorDate = LocalDate.of(2026, 3, 8);
        LocalDate t = LocalDate.of(2026, 3, 15);
        BalanceCheckpoint cp = anchor(defaultAccount, anchorDate, 10_000);

        when(checkpointRepository.findLatestForAccountAt(defaultAccount.getId(), t))
                .thenReturn(Optional.of(cp));
        // Все события ниже возвращает МОК findAllByDeletedFalseAndDateBetween(...) — этот метод
        // уже исключает deleted=true по имени запроса (см. FinancialEventRepository), поэтому
        // удалённые здесь отдельно не моделируем: их и не может там оказаться.
        when(eventRepository.findAllByDeletedFalseAndDateBetween(anchorDate, t))
                .thenReturn(List.of(
                        fact(anchorDate, EventType.EXPENSE, 999_999),      // день якоря — исключается
                        wishlistFact(LocalDate.of(2026, 3, 9), 777_777),   // хотелка — исключается
                        planWithoutFact(LocalDate.of(2026, 3, 10), 555_555), // без факта — исключается
                        fact(LocalDate.of(2026, 3, 11), EventType.EXPENSE, 1_000) // единственный, что считается
                ));

        BigDecimal balance = service.balanceAt(defaultAccount, t);

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(9_000)); // 10 000 − 1 000
    }

    // ── 4. otherFreeMoneyAt ──────────────────────────────────────────────────

    @Test
    @DisplayName("otherFreeMoneyAt не включает дефолтный, конверт без слежения, кредитку, вклад")
    void otherFreeMoneyAt_excludesDefaultEnvelopeCreditAndDeposit() {
        LocalDate t = LocalDate.of(2026, 3, 20);

        Account defaultAccount = AccountFixtures.defaultAccount(); // исключён явно сервисом
        Account otherTracked = AccountFixtures.account(AccountKind.DEBIT, true).build(); // считается
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false).build(); // не считается
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true).build(); // не считается
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build(); // не считается

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, otherTracked, envelope, credit, deposit));
        // ВАЖНО: у ВСЕХ пяти счетов — ненулевой якорь, включая четыре, которые тест исключает.
        // Без этого тест зеленеет и с вырезанными фильтрами countsAsFreeMoney/!isDefaultAccount:
        // незастабленный findLatestForAccountAt тихо отдаёт Optional.empty(), balanceAt — ноль,
        // и «исключённый» счёт вносит ноль вне зависимости от того, работает фильтр или нет
        // (найдено ревью мутацией, ANO-9 Task 2.1). Суммы разные и не равны 20 000 — любая
        // утечка любого из четырёх счетов в сумму сдвинет итог и провалит assertEquals ниже.
        when(checkpointRepository.findLatestForAccountAt(defaultAccount.getId(), t))
                .thenReturn(Optional.of(anchor(defaultAccount, LocalDate.of(2026, 3, 1), 500_000)));
        when(checkpointRepository.findLatestForAccountAt(otherTracked.getId(), t))
                .thenReturn(Optional.of(anchor(otherTracked, LocalDate.of(2026, 3, 1), 20_000)));
        when(checkpointRepository.findLatestForAccountAt(envelope.getId(), t))
                .thenReturn(Optional.of(anchor(envelope, LocalDate.of(2026, 3, 1), 250_000)));
        when(checkpointRepository.findLatestForAccountAt(credit.getId(), t))
                .thenReturn(Optional.of(anchor(credit, LocalDate.of(2026, 3, 1), 125_000)));
        when(checkpointRepository.findLatestForAccountAt(deposit.getId(), t))
                .thenReturn(Optional.of(anchor(deposit, LocalDate.of(2026, 3, 1), 62_500)));

        BigDecimal result = service.otherFreeMoneyAt(t);

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(20_000));
    }

    // ── 5. semiLiquidAt ──────────────────────────────────────────────────────

    @Test
    @DisplayName("semiLiquidAt = сумма вкладов")
    void semiLiquidAt_sumsDepositBalances() {
        LocalDate t = LocalDate.of(2026, 3, 20);

        Account deposit1 = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        Account deposit2 = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        Account debit = AccountFixtures.account(AccountKind.DEBIT, true).build(); // не считается

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(deposit1, deposit2, debit));
        when(checkpointRepository.findLatestForAccountAt(deposit1.getId(), t))
                .thenReturn(Optional.of(anchor(deposit1, LocalDate.of(2026, 1, 1), 100_000)));
        when(checkpointRepository.findLatestForAccountAt(deposit2.getId(), t))
                .thenReturn(Optional.of(anchor(deposit2, LocalDate.of(2026, 1, 1), 50_000)));
        // Ненулевой якорь и у debit (не считается): та же причина, что в тесте выше — иначе
        // сломанный isSemiLiquid-фильтр остался бы незамеченным, потому что незастабленный
        // счёт и так даёт ноль (ANO-9 Task 2.1, мутационная проверка).
        when(checkpointRepository.findLatestForAccountAt(debit.getId(), t))
                .thenReturn(Optional.of(anchor(debit, LocalDate.of(2026, 1, 1), 777_000)));

        BigDecimal result = service.semiLiquidAt(t);

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(150_000));
    }

    // ── 6. creditRestoreReserveAt ────────────────────────────────────────────

    @Test
    @DisplayName("creditRestoreReserveAt = сумма max(0, планка − доступно) по кредиткам с планкой; счета не-CREDIT не участвуют")
    void creditRestoreReserveAt_sumsMaxZeroFloorMinusAvailable() {
        LocalDate t = LocalDate.of(2026, 3, 20);
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .availableFloor(BigDecimal.valueOf(150_000)).creditLimit(BigDecimal.valueOf(200_000)).build();
        // Не-CREDIT счёт в том же списке — С НЕНУЛЕВЫМ availableFloor и ненулевым якорем,
        // иначе `if (target == null) continue;` вывел бы его из суммы независимо от того,
        // работает ли фильтр `kind == CREDIT`, и мутация этого фильтра осталась бы незамеченной
        // (та же ловушка, что в тестах otherFreeMoneyAt/semiLiquidAt — see ANO-9 Task 2.1).
        // Реальный DEBIT-счёт так не заполняется (валидация в Task 3.1), это моделирует именно
        // «что если бы фильтр по kind пропустил этот объект».
        Account debit = AccountFixtures.account(AccountKind.DEBIT, true)
                .availableFloor(BigDecimal.valueOf(500_000)).build();

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(credit, debit));
        when(checkpointRepository.findLatestForAccountAt(credit.getId(), t))
                .thenReturn(Optional.of(anchor(credit, LocalDate.of(2026, 3, 1), 100_000)));
        when(checkpointRepository.findLatestForAccountAt(debit.getId(), t))
                .thenReturn(Optional.of(anchor(debit, LocalDate.of(2026, 3, 1), 100_000)));

        BigDecimal reserve = service.creditRestoreReserveAt(t);

        // max(0, 150 000 − 100 000) = 50 000; debit (даже с планкой 500 000 − 100 000 = 400 000
        // потенциального «резерва») не участвует вообще, потому что не CREDIT
        assertThat(reserve).isEqualByComparingTo(BigDecimal.valueOf(50_000));
    }

    @Test
    @DisplayName("creditRestoreReserveAt: кредитка без планки в резерв не входит (даёт ноль)")
    void creditRestoreReserveAt_creditWithoutFloor_givesZero() {
        LocalDate t = LocalDate.of(2026, 3, 20);
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(BigDecimal.valueOf(200_000)).build(); // availableFloor не задан

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(credit));

        BigDecimal reserve = service.creditRestoreReserveAt(t);

        assertThat(reserve).isEqualByComparingTo(BigDecimal.ZERO);
        // Без планки счёт даже не пытается узнать остаток — сравнивать не с чем.
        verifyNoInteractions(checkpointRepository);
    }

    @Test
    @DisplayName("creditRestoreReserveAt: доступно выше планки даёт ноль резерва")
    void creditRestoreReserveAt_availableAboveFloor_givesZero() {
        LocalDate t = LocalDate.of(2026, 3, 20);
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .availableFloor(BigDecimal.valueOf(100_000)).creditLimit(BigDecimal.valueOf(200_000)).build();

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(credit));
        when(checkpointRepository.findLatestForAccountAt(credit.getId(), t))
                .thenReturn(Optional.of(anchor(credit, LocalDate.of(2026, 3, 1), 160_000)));

        BigDecimal reserve = service.creditRestoreReserveAt(t);

        // max(0, 100 000 − 160 000) = 0
        assertThat(reserve).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── 7. creditDebtAt ──────────────────────────────────────────────────────

    @Test
    @DisplayName("creditDebtAt = сумма max(0, лимит − доступно); счета не-CREDIT не участвуют")
    void creditDebtAt_sumsMaxZeroLimitMinusAvailable() {
        LocalDate t = LocalDate.of(2026, 3, 20);
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(BigDecimal.valueOf(200_000)).build();
        // Тот же приём, что в creditRestoreReserveAt: не-CREDIT счёт с ненулевым creditLimit
        // и ненулевым якорем — ловит мутацию фильтра `kind == CREDIT` независимо от
        // null-guard'а на level.apply(a).
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true)
                .creditLimit(BigDecimal.valueOf(300_000)).build();

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(credit, deposit));
        when(checkpointRepository.findLatestForAccountAt(credit.getId(), t))
                .thenReturn(Optional.of(anchor(credit, LocalDate.of(2026, 3, 1), 62_000)));
        when(checkpointRepository.findLatestForAccountAt(deposit.getId(), t))
                .thenReturn(Optional.of(anchor(deposit, LocalDate.of(2026, 3, 1), 50_000)));

        BigDecimal debt = service.creditDebtAt(t);

        // max(0, 200 000 − 62 000) = 138 000; deposit не участвует вообще
        assertThat(debt).isEqualByComparingTo(BigDecimal.valueOf(138_000));
    }

    @Test
    @DisplayName("creditDebtAt: кредитка без чекпоинта молчит, а не считает ноль долгом")
    void creditDebtAt_noCheckpoint_isSkippedNotZero() {
        LocalDate t = LocalDate.of(2026, 3, 20);
        Account creditWithoutCheckpoint = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(BigDecimal.valueOf(200_000)).build();
        Account creditWithCheckpoint = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(BigDecimal.valueOf(100_000)).build();

        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(creditWithoutCheckpoint, creditWithCheckpoint));
        when(checkpointRepository.findLatestForAccountAt(creditWithoutCheckpoint.getId(), t))
                .thenReturn(Optional.empty());
        when(checkpointRepository.findLatestForAccountAt(creditWithCheckpoint.getId(), t))
                .thenReturn(Optional.of(anchor(creditWithCheckpoint, LocalDate.of(2026, 3, 1), 40_000)));

        BigDecimal debt = service.creditDebtAt(t);

        // Молчащая кредитка не добавляет 200 000 (лимит) как долг — только вторая: 100 000 − 40 000 = 60 000
        assertThat(debt).isEqualByComparingTo(BigDecimal.valueOf(60_000));
    }
}
