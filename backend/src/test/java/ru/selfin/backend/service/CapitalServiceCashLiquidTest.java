package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
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
 * ANO-46: вклад считается ТОЛЬКО на экране Капитала (решение пользователя 2026-08-15,
 * вариант 3 из трёх — см. план, «Решения перед стартом чанка 3»).
 *
 * <p>Формулировка решения: «Вклад, конечно, может помочь пережить месяц, но это должно быть
 * осознанным решением пользователя, а не предполагаемое действие». Отсюда два разных числа:
 * <pre>
 *   liquidAt(t)     = cashLiquidAt(t) + semiLiquidAt(t)   → экран Капитала (§4.4)
 *   cashLiquidAt(t) = freeMoneyAt + noAnchorFallbackAt + копилки-конверты
 *                                                        → кассовый график стратегии,
 *                                                          зоны риска и потолок кредита в хотелках
 * </pre>
 *
 * <p>До этой правки кассовый график сидел на {@code liquidAt} и стартовал с суммы, включающей
 * вклад: человек, проедающий 10 000 в месяц при вкладе 1 500 000, не получал предупреждения о
 * разрыве ближайшие 12 лет, хотя кармашек (§4.3) вклад в основное число сознательно не берёт.
 * Не горело до чанка 3 только потому, что DEPOSIT-счёт нечем было создать.
 *
 * <p>Использует НАСТОЯЩИЙ {@link AccountBalanceService} поверх замоканных репозиториев — тот же
 * приём, что в {@link CapitalServiceLiquidTest}: замокав сервис целиком, проверишь только факт
 * вызова, а не саму формулу.
 */
@ExtendWith(MockitoExtension.class)
class CapitalServiceCashLiquidTest {

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
    @DisplayName("Вклад входит в liquidAt (Капитал), но НЕ входит в cashLiquidAt (кассовый график). "
            + "Разница между числами равна ровно сумме вкладов")
    void cashLiquidAt_excludesDeposits_whileLiquidAtIncludesThem() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, deposit));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, today.minusDays(1), "100000")));
        // Якорь вклада ОБЯЗАН быть застабан: без него balanceAt(deposit) вернул бы ноль, и тест
        // остался бы зелёным даже с вкладом внутри cashLiquidAt (ловушка ревью Task 2.1).
        when(checkpointRepo.findLatestForAccountAt(deposit.getId(), today))
                .thenReturn(Optional.of(checkpoint(deposit, today.minusDays(1), "80000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(today))
                .thenReturn(new BigDecimal("12000"));

        BigDecimal cash = service.cashLiquidAt(today);
        BigDecimal liquid = service.liquidAt(today);

        // 100 000 (карта) + 12 000 (копилка-конверт), БЕЗ 80 000 вклада
        assertThat(cash).isEqualByComparingTo("112000");
        // 112 000 + 80 000 (вклад) — экран Капитала вклад по-прежнему видит
        assertThat(liquid).isEqualByComparingTo("192000");
        assertThat(liquid.subtract(cash)).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("Конверт без слежения не попадает ни в одно из двух чисел")
    void cashLiquidAt_ignoresUntrackedEnvelopeAccount() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false).build();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, envelope));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today))
                .thenReturn(Optional.of(checkpoint(defaultAccount, today.minusDays(1), "40000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(today)).thenReturn(BigDecimal.ZERO);

        // Якорь конверта намеренно НЕ стабится: countsAsFreeMoney() отсекает его до balanceAt,
        // поэтому лишнего обращения к репозиторию быть не должно (strict stubs это подтвердят).
        assertThat(service.cashLiquidAt(today)).isEqualByComparingTo("40000");
        assertThat(service.liquidAt(today)).isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("cashLiquidAt сохраняет фолбэк ANO-28: у пользователя без единого чекпоинта "
            + "это сумма фактов на нулевой базе, а не ноль")
    void cashLiquidAt_keepsNoAnchorFallback() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate today = LocalDate.now();

        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        when(accountRepo.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(defaultAccount));
        when(checkpointRepo.findLatestForAccountAt(defaultAccount.getId(), today)).thenReturn(Optional.empty());
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(fact(today.minusDays(5), EventType.INCOME, "90000")));
        when(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(today)).thenReturn(BigDecimal.ZERO);

        assertThat(service.cashLiquidAt(today)).isEqualByComparingTo("90000");
    }
}
