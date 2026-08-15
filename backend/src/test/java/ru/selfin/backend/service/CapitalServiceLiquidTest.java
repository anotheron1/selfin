package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.dto.capital.CapitalSummaryDto;
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
import ru.selfin.backend.repository.CapitalItemRepository;
import ru.selfin.backend.repository.CapitalRevaluationRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты на формулу {@code liquidAt(t)} и обязательства через публичный путь
 * {@code summary()} (спека 2026-08-12-accounts-skeleton-design.md §4.4, ANO-9 Task 2.3).
 *
 * <p>Использует НАСТОЯЩИЙ {@link AccountBalanceService} поверх замоканных репозиториев
 * (accountRepo/checkpointRepo/eventRepo) — тот же приём, что в
 * {@link PocketInputAssemblerOwnerScenarioTest}: если замокать {@code AccountBalanceService}
 * целиком, сама формула (вклады в ликвиде, задвоение копилки, долг от лимита) не проверяется,
 * только вызов метода.
 */
@ExtendWith(MockitoExtension.class)
class CapitalServiceLiquidTest {

    @Mock CapitalItemRepository itemRepo;
    @Mock CapitalRevaluationRepository revRepo;
    @Mock BalanceCheckpointRepository checkpointRepo;
    @Mock FundTransactionRepository fundTxRepo;
    @Mock AccountRepository accountRepo;
    @Mock FinancialEventRepository eventRepo;

    private CapitalService service;

    @BeforeEach
    void setUp() {
        AccountBalanceService accountBalanceService =
                new AccountBalanceService(accountRepo, checkpointRepo, eventRepo);
        service = new CapitalService(itemRepo, revRepo, checkpointRepo, fundTxRepo, accountBalanceService);
    }

    private static BalanceCheckpoint checkpoint(Account account, LocalDate date, String amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(new BigDecimal(amount)).account(account)
                .build();
    }

    private static FinancialEvent fact(LocalDate date, EventType type, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).date(date).type(type)
                .eventKind(EventKind.FACT).factAmount(new BigDecimal(amount))
                .status(EventStatus.EXECUTED).priority(Priority.MEDIUM).deleted(false)
                .build();
    }

    @Test
    void summary_emptyDb_liquidIsZero() {
        when(itemRepo.findAllActive(null)).thenReturn(List.of());
        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc()).thenReturn(List.of());
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        assertThat(s.liquid()).isEqualByComparingTo("0");
        assertThat(s.total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Регрессия: чекпоинт дефолтного счёта + факты INCOME/EXPENSE/FUND_TRANSFER + "
            + "копилки без accountId — то же число, что считала старая (до ANO-9) формула")
    void summary_withCheckpointAndEventsAndPockets_computesLiquidCorrectly() {
        // checkpoint=200к на дефолтном счёте, после него INCOME=50к, EXPENSE=10к,
        // FUND_TRANSFER=30к (все они — обычные, не-wishlist факты). Копилки без accountId: 30к.
        // freeMoneyAt   = 200 + 50 − 10 − 30 = 210
        // semiLiquidAt  = 0 (вкладов нет)
        // pocketBalance = 30
        // liquid        = 240
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate anchorDate = LocalDate.now().minusDays(30);
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, anchorDate, "200000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(anchorDate, today)).thenReturn(List.of(
                fact(anchorDate.plusDays(5), EventType.INCOME, "50000"),
                fact(anchorDate.plusDays(6), EventType.EXPENSE, "10000"),
                fact(anchorDate.plusDays(7), EventType.FUND_TRANSFER, "30000")
        ));
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(new BigDecimal("30000"));
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        assertThat(s.liquid()).isEqualByComparingTo("240000");
        assertThat(s.total()).isEqualByComparingTo("240000");
    }

    @Test
    @DisplayName("Ликвид включает вклады (DEPOSIT, spec §4.4 — отличие №1 от старой формулы)")
    void liquidAt_includesDeposits() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, deposit));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, today.minusDays(1), "100000")));
        when(checkpointRepo.findLatestForAccountAt(deposit.getId(), today))
                .thenReturn(Optional.of(checkpoint(deposit, today.minusDays(1), "80000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // 100 000 (карта) + 80 000 (вклад) = 180 000
        assertThat(s.liquid()).isEqualByComparingTo("180000");
    }

    @Test
    @DisplayName("Ликвид не задваивает копилку с accountId — её деньги уже в балансе счёта; "
            + "копилка без accountId по-прежнему прибавляется отдельно")
    void liquidAt_doesNotDoubleCountFundWithAccountId() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, today.minusDays(1), "100000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        // Репозиторий сам фильтрует fund.accountId IS NULL (FundTransactionRepository) —
        // здесь мокаем УЖЕ отфильтрованный результат: только сумма копилки БЕЗ accountId (12 000).
        // Копилка С accountId (условно 20 000) в этой сумме отсутствует — её деньги внутри
        // остатка счёта, на который она ссылается, и туда они уже вошли бы через freeMoneyAt/
        // semiLiquidAt, если бы такой счёт был среди active(). JPQL-фильтр самого запроса
        // отдельно проверен FundTransactionRepositoryIT.
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(new BigDecimal("12000"));
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // 100 000 (карта) + 12 000 (копилка без accountId) = 112 000, НЕ 132 000
        assertThat(s.liquid()).isEqualByComparingTo("112000");
    }

    @Test
    @DisplayName("Обязательства включают долг по кредитному счёту, посчитанный от лимита и доступного")
    void liabilities_includeCreditAccountDebt() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000")).build();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, credit));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, today.minusDays(1), "50000")));
        when(checkpointRepo.findLatestForAccountAt(credit.getId(), today))
                .thenReturn(Optional.of(checkpoint(credit, today.minusDays(1), "62000"))); // доступно
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // liquid = 50 000 (кредитка не входит в ликвид — она не DEBIT/CASH/DEPOSIT)
        assertThat(s.liquid()).isEqualByComparingTo("50000");
        // долг = 200 000 (лимит) − 62 000 (доступно) = 138 000
        assertThat(s.liabilitiesTotal()).isEqualByComparingTo("138000");
        // total = 50 000 − 138 000 = −88 000
        assertThat(s.total()).isEqualByComparingTo("-88000");
    }

    @Test
    @DisplayName("Кредитка без чекпоинта не даёт фиктивного долга — счёт молчит, а не считает ноль")
    void liabilities_creditAccountWithoutCheckpoint_addsNoDebt() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        Account creditNoCheckpoint = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("300000")).build();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, creditNoCheckpoint));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, today.minusDays(1), "50000")));
        when(checkpointRepo.findLatestForAccountAt(creditNoCheckpoint.getId(), today))
                .thenReturn(Optional.empty());
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // Кредитка без чекпоинта не добавляет ни лимит (300 000), ни ноль — молчит.
        assertThat(s.liabilitiesTotal()).isEqualByComparingTo("0");
        assertThat(s.total()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("КРИТИЧНО (ревью после коммита 1c893c2): у пользователя БЕЗ ЕДИНОГО чекпоинта "
            + "ликвид капитала = сумма фактов на нулевой базе (ANO-28), а не ноль. balanceAt без "
            + "якоря молча даёт 0 — это верно для ПРОЧИХ счетов (§6 спеки), но не для дефолтного, "
            + "чью историю фактов кармашек (PocketEngine) сознательно не обнуляет. Замер ревью: "
            + "факт дохода 90 000 без чекпоинта — было 0, стало 90 000.")
    void liquidAt_noCheckpointAtAll_sumsFactsFromEpoch_matchesPocketAno28Behaviour() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate today = LocalDate.now();
        LocalDate incomeDate = today.minusDays(5);

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        // Дефолтный счёт вообще без чекпоинта — noAnchorFallbackAt(t) обязан включиться.
        when(accountRepo.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(defaultAccount));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today)).thenReturn(Optional.empty());
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(fact(incomeDate, EventType.INCOME, "90000")));
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // 90 000 (факт дохода на нулевой базе), НЕ ноль.
        assertThat(s.liquid()).isEqualByComparingTo("90000");
        assertThat(s.total()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("ANO-9 Task 2.3, ловушка ревью: факт по хотелке (wishlistStatus != null) БОЛЬШЕ "
            + "не уменьшает/увеличивает ликвид капитала — согласовано с кармашком "
            + "(было 85 000 на неизменённом коде — см. CapitalWishlistFactIT; стало 100 000)")
    void liquidAt_wishlistFact_isExcludedFromLiquid() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate anchorDate = LocalDate.now().minusDays(10);
        LocalDate today = LocalDate.now();

        FinancialEvent wishlistExpenseFact = fact(anchorDate.plusDays(3), EventType.EXPENSE, "15000");
        wishlistExpenseFact.setWishlistStatus(WishlistStatus.FIXED);

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, anchorDate, "100000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(anchorDate, today))
                .thenReturn(List.of(wishlistExpenseFact));
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // 100 000 без изменений: факт по хотелке отфильтрован AccountBalanceService.factsDelta,
        // как и в PocketEngine.calculate. ДО правки старая формула (sumFactByTypeBetween,
        // без фильтра wishlistStatus) вычла бы эти 15 000 → 85 000 (зафиксировано и
        // подтверждено на реальной БД в CapitalWishlistFactIT перед этой правкой).
        assertThat(s.liquid()).isEqualByComparingTo("100000");
    }
}
