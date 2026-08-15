package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сценарий владельца из спеки §6 (основная карта + конверты без слежения + вклад) — ОСНОВНАЯ
 * конфигурация, не экзотика. Ревью на реальных данных нашло здесь четыре способа испортить
 * currentBalance, когда якорь брался «глобально последний чекпоинт по всей таблице»
 * ({@code checkpointRepository.findTopByOrderByDateDesc()}) вместо чекпоинта дефолтного счёта:
 * победивший чекпоинт мог принадлежать вкладу, кредитке, конверту без слежения или второй
 * дебетовой карте — и его остаток либо давал лишние деньги, либо факты считались дважды.
 *
 * <p>Использует НАСТОЯЩИЙ {@link AccountBalanceService} (не мок) поверх замоканных
 * репозиториев — иначе сама подмена якоря в {@link PocketInputAssembler} не проверяется,
 * только её обвязка (см. {@link PocketInputAssemblerTest}, где accountBalanceService замокан
 * целиком).
 */
class PocketInputAssemblerOwnerScenarioTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final PocketScope MONTHS_1 = new PocketScope(PocketScope.Type.MONTHS, 1, null);

    private AccountRepository accountRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private FinancialEventRepository eventRepository;
    private PocketInputAssembler assembler;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        eventRepository = mock(FinancialEventRepository.class);
        AccountBalanceService accountBalanceService =
                new AccountBalanceService(accountRepository, checkpointRepository, eventRepository);

        TargetFundRepository fundRepository = mock(TargetFundRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        PredictionService predictionService = mock(PredictionService.class);
        RecurringRuleService recurringRuleService = mock(RecurringRuleService.class);

        when(eventRepository.findOverdueMandatoryExpenses(any(), any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findPlannedIncomeDates(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        when(fundRepository.findByWishlistStatusAndDeletedFalse(WishlistStatus.FIXED)).thenReturn(List.of());

        assembler = new PocketInputAssembler(eventRepository,
                settingsService, predictionService, recurringRuleService, fundRepository, categoryRepository,
                accountBalanceService);
    }

    private static BalanceCheckpoint checkpoint(Account a, LocalDate date, long amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(BigDecimal.valueOf(amount)).account(a).build();
    }

    private static FinancialEvent addresslessExpenseFact(LocalDate date, long amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).date(date).type(EventType.EXPENSE)
                .eventKind(EventKind.FACT).factAmount(BigDecimal.valueOf(amount))
                .status(EventStatus.EXECUTED).priority(Priority.MEDIUM).deleted(false)
                .build();
    }

    private void anchoredAt(Account a, LocalDate date, long amount) {
        when(checkpointRepository.findLatestForAccountAt(a.getId(), TODAY))
                .thenReturn(Optional.of(checkpoint(a, date, amount)));
    }

    private BigDecimal currentBalance() {
        return PocketEngine.calculate(assembler.build(MONTHS_1, TODAY).input()).currentBalance();
    }

    @Test
    @DisplayName("Вклад, переякоренный ПОЗЖЕ основной карты, не становится currentBalance "
            + "(отчёт ревью: было 338 000, по спеке 38 000)")
    void depositReanchoredAfterCard_doesNotBecomeCurrentBalance() {
        Account card = AccountFixtures.defaultAccount();
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(card));
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(card, deposit));

        anchoredAt(card, LocalDate.of(2026, 3, 8), 50_000);
        anchoredAt(deposit, LocalDate.of(2026, 3, 12), 300_000); // переякорен ПОЗЖЕ карты
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(addresslessExpenseFact(LocalDate.of(2026, 3, 10), 12_000)));

        // 50 000 (якорь карты) − 12 000 (факт после 08.03, ≤ 15.03) + 0 (вклад — не свободные деньги) = 38 000
        assertThat(currentBalance()).isEqualByComparingTo(BigDecimal.valueOf(38_000));
    }

    @Test
    @DisplayName("Кредитка с более свежим чекпоинтом не даёт свой доступный лимит в currentBalance "
            + "(отчёт ревью: было завышение на доступный остаток)")
    void creditCardWithFresherCheckpoint_doesNotInflateCurrentBalance() {
        Account card = AccountFixtures.defaultAccount();
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true).build();
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(card));
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(card, credit));

        anchoredAt(card, LocalDate.of(2026, 3, 8), 50_000);
        anchoredAt(credit, LocalDate.of(2026, 3, 12), 100_000); // доступный остаток, переякорен позже карты
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        // Кредитка не участвует в свободных деньгах ни как якорь, ни как "прочий счёт".
        assertThat(currentBalance()).isEqualByComparingTo(BigDecimal.valueOf(50_000));
    }

    @Test
    @DisplayName("Конверт без слежения с чекпоинтом не даёт свой остаток в currentBalance "
            + "(отчёт ревью: было завышение на остаток конверта)")
    void envelopeWithoutTracking_doesNotInflateCurrentBalance() {
        Account card = AccountFixtures.defaultAccount();
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false).build(); // trackBalance=false
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(card));
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(card, envelope));

        anchoredAt(card, LocalDate.of(2026, 3, 8), 50_000);
        anchoredAt(envelope, LocalDate.of(2026, 3, 12), 9_000); // старый/случайный чекпоинт, позже карты
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        // trackBalance=false — не участвует в свободных деньгах, даже если у него есть чекпоинт.
        assertThat(currentBalance()).isEqualByComparingTo(BigDecimal.valueOf(50_000));
    }

    @Test
    @DisplayName("Две дебетовые карты: факт не считается дважды через otherAccountsBalance "
            + "(отчёт ревью: было занижение на 3 000)")
    void twoDebitCards_factNotDoubleCounted() {
        Account cardA = AccountFixtures.defaultAccount();
        Account cardB = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(cardA));
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(cardA, cardB));

        anchoredAt(cardA, LocalDate.of(2026, 3, 8), 40_000);
        anchoredAt(cardB, LocalDate.of(2026, 3, 12), 10_000); // переякорена ПОЗЖЕ дефолтной
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(addresslessExpenseFact(LocalDate.of(2026, 3, 14), 3_000)));

        // A: 40 000 − 3 000 (факт после 08.03) = 37 000; B: 10 000 (не-дефолтная — без фактов).
        // Факт не должен вычитаться и из B, и из "глобального" чекпоинта одновременно.
        assertThat(currentBalance()).isEqualByComparingTo(BigDecimal.valueOf(47_000));
    }
}
