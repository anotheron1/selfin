package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.BalanceCheckpointDto;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Дрейф цепочки чекпоинтов (ANO-15 §4): computedBalance = prev.amount + факты (prev.date, cur.date],
 * drift = amount − computedBalance. Правило фактов — как в PocketEngine.currentBalance.
 */
class BalanceCheckpointServiceTest {

    private BalanceCheckpointRepository repository;
    private FinancialEventRepository eventRepository;
    private AccountRepository accountRepository;
    private BalanceCheckpointService service;

    @BeforeEach
    void setUp() {
        repository = mock(BalanceCheckpointRepository.class);
        eventRepository = mock(FinancialEventRepository.class);
        accountRepository = mock(AccountRepository.class);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        service = new BalanceCheckpointService(repository, eventRepository, accountRepository);
    }

    private static BalanceCheckpoint cp(LocalDate date, long amount, LocalDateTime createdAt) {
        return cp(date, amount, createdAt, AccountFixtures.defaultAccount());
    }

    private static BalanceCheckpoint cp(LocalDate date, long amount, LocalDateTime createdAt, Account account) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(BigDecimal.valueOf(amount))
                .account(account)
                .createdAt(createdAt).updatedAt(createdAt)
                .build();
    }

    private static BalanceCheckpointDto find(List<BalanceCheckpointDto> dtos, UUID id) {
        return dtos.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
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

    @Test
    @DisplayName("Цепочка из 3: дрейф каждого интервала; день prev исключён, день cur включён, wishlist игнор")
    void driftChain() {
        LocalDateTime t = LocalDateTime.of(2026, 4, 1, 12, 0);
        BalanceCheckpoint c1 = cp(LocalDate.of(2026, 3, 1), 10_000, t.minusDays(40));
        BalanceCheckpoint c2 = cp(LocalDate.of(2026, 3, 15), 12_000, t.minusDays(17));
        BalanceCheckpoint c3 = cp(LocalDate.of(2026, 4, 1), 9_000, t);
        when(repository.findAllByOrderByDateDesc()).thenReturn(List.of(c3, c2, c1));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of(
                        fact(LocalDate.of(2026, 3, 1), EventType.INCOME, 999),    // день c1 — исключён
                        fact(LocalDate.of(2026, 3, 5), EventType.INCOME, 5_000),  // интервал c2
                        fact(LocalDate.of(2026, 3, 15), EventType.EXPENSE, 1_000),// день c2 — включён в c2
                        wishlistFact(LocalDate.of(2026, 3, 10), 7_777),           // игнор
                        fact(LocalDate.of(2026, 3, 20), EventType.EXPENSE, 2_000) // интервал c3
                ));

        List<BalanceCheckpointDto> dtos = service.findAll();

        assertThat(dtos).hasSize(3);
        BalanceCheckpointDto d3 = dtos.get(0); // 01.04
        BalanceCheckpointDto d2 = dtos.get(1); // 15.03
        BalanceCheckpointDto d1 = dtos.get(2); // 01.03 — самый ранний

        assertThat(d2.computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(14_000)); // 10k +5k −1k
        assertThat(d2.drift()).isEqualByComparingTo(BigDecimal.valueOf(-2_000));
        assertThat(d3.computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000)); // 12k −2k
        assertThat(d3.drift()).isEqualByComparingTo(BigDecimal.valueOf(-1_000));
        assertThat(d1.computedBalance()).isNull();
        assertThat(d1.drift()).isNull();
    }

    @Test
    @DisplayName("ANO-9 Task 2.4: дрейф считается внутри счёта, а не по глобальной цепочке чекпоинтов")
    void driftIsScopedPerAccount_notGlobalChain() {
        LocalDateTime t = LocalDateTime.of(2026, 4, 1, 12, 0);
        Account accountA = AccountFixtures.defaultAccount();
        Account accountB = AccountFixtures.account(AccountKind.DEPOSIT, true).name("Вклад").build();

        // Даты чередуются между счетами: A, B, A, B
        BalanceCheckpoint a1 = cp(LocalDate.of(2026, 3, 1), 10_000, t.minusDays(30), accountA);
        BalanceCheckpoint b1 = cp(LocalDate.of(2026, 3, 5), 5_000, t.minusDays(26), accountB);
        BalanceCheckpoint a2 = cp(LocalDate.of(2026, 3, 15), 12_000, t.minusDays(16), accountA);
        BalanceCheckpoint b2 = cp(LocalDate.of(2026, 3, 20), 5_500, t.minusDays(11), accountB);
        // Порядок репозитория: date DESC — единая цепочка, счета вперемешку
        when(repository.findAllByOrderByDateDesc()).thenReturn(List.of(b2, a2, b1, a1));

        List<BalanceCheckpointDto> dtos = service.findAll();

        // A2 — второй чекпоинт счёта A: дрейф обязан считаться от A1 (10 000),
        // а НЕ от B1 (5 000) — того, что просто ближе по дате в общей цепочке.
        BalanceCheckpointDto dA2 = find(dtos, a2.getId());
        assertThat(dA2.computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(dA2.drift()).isEqualByComparingTo(BigDecimal.valueOf(2_000)); // 12 000 − 10 000

        // A1 — самый ранний чекпоинт счёта A.
        BalanceCheckpointDto dA1 = find(dtos, a1.getId());
        assertThat(dA1.computedBalance()).isNull();
        assertThat(dA1.drift()).isNull();

        // Счёт B не дефолтный — журнал к нему не привязан, дрейф для него не диагностика.
        BalanceCheckpointDto dB1 = find(dtos, b1.getId());
        BalanceCheckpointDto dB2 = find(dtos, b2.getId());
        assertThat(dB1.computedBalance()).isNull();
        assertThat(dB1.drift()).isNull();
        assertThat(dB2.computedBalance()).isNull();
        assertThat(dB2.drift()).isNull();
    }

    @Test
    @DisplayName("Дубль дня (исправление опечатки): пустой интервал, дрейф = разница правок")
    void sameDayDuplicate_driftIsCorrectionDiff() {
        LocalDateTime t = LocalDateTime.of(2026, 4, 1, 12, 0);
        BalanceCheckpoint first = cp(LocalDate.of(2026, 4, 1), 12_000, t);
        BalanceCheckpoint fixed = cp(LocalDate.of(2026, 4, 1), 12_500, t.plusMinutes(5));
        // Порядок репозитория: date DESC, createdAt DESC — поздняя правка первой
        when(repository.findAllByOrderByDateDesc()).thenReturn(List.of(fixed, first));

        List<BalanceCheckpointDto> dtos = service.findAll();

        assertThat(dtos.get(0).computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(12_000));
        assertThat(dtos.get(0).drift()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(dtos.get(1).drift()).isNull();
    }

    @Test
    @DisplayName("Один чекпоинт: полей дрейфа нет, события не запрашиваются")
    void singleCheckpoint_noDriftNoEventQuery() {
        when(repository.findAllByOrderByDateDesc())
                .thenReturn(List.of(cp(LocalDate.of(2026, 4, 1), 12_000, LocalDateTime.now())));

        List<BalanceCheckpointDto> dtos = service.findAll();

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).computedBalance()).isNull();
        assertThat(dtos.get(0).drift()).isNull();
        verifyNoInteractions(eventRepository);
    }

    @Test
    @DisplayName("ANO-9: create() без выбора счёта проставляет дефолтный счёт из AccountRepository")
    void create_setsDefaultAccount() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse())
                .thenReturn(Optional.of(defaultAccount));
        when(repository.save(any(BalanceCheckpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new BalanceCheckpointCreateDto(LocalDate.of(2026, 8, 15), BigDecimal.valueOf(50_000)));

        ArgumentCaptor<BalanceCheckpoint> captor = ArgumentCaptor.forClass(BalanceCheckpoint.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAccount()).isEqualTo(defaultAccount);
    }

    @Test
    @DisplayName("ANO-9: create() без дефолтного счёта после миграции — явная ошибка, не NPE")
    void create_noDefaultAccount_throwsIllegalState() {
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.create(new BalanceCheckpointCreateDto(LocalDate.of(2026, 8, 15), BigDecimal.valueOf(50_000))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Task 3.3: явный accountId кладёт якорь на указанный счёт, а не на дефолтный")
    void create_withExplicitAccountId_usesThatAccount() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        when(accountRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(repository.save(any(BalanceCheckpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        BalanceCheckpointDto out = service.create(new BalanceCheckpointCreateDto(
                LocalDate.of(2026, 8, 15), BigDecimal.valueOf(300_000), deposit.getId()));

        ArgumentCaptor<BalanceCheckpoint> captor = ArgumentCaptor.forClass(BalanceCheckpoint.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAccount()).isEqualTo(deposit);
        assertThat(out.accountId()).isEqualTo(deposit.getId());
        assertThat(out.accountName()).isEqualTo(deposit.getName());
        // дефолтный счёт при явном выборе вообще не запрашивается
        verify(accountRepository, never()).findByDefaultAccountTrueAndDeletedFalse();
    }

    @Test
    @DisplayName("Task 3.3: якорь на несуществующий счёт — 404, а не тихая посадка на дефолтный")
    void create_withUnknownAccountId_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(accountRepository.findById(unknown)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                        new BalanceCheckpointCreateDto(LocalDate.of(2026, 8, 15), BigDecimal.valueOf(1000), unknown)))
                .isInstanceOf(ru.selfin.backend.exception.ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Task 3.3: якорь на удалённый счёт — 404")
    void create_withDeletedAccountId_throwsNotFound() {
        Account deleted = AccountFixtures.account(AccountKind.DEBIT, true).build();
        deleted.setDeleted(true);
        when(accountRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                        new BalanceCheckpointCreateDto(LocalDate.of(2026, 8, 15), BigDecimal.valueOf(1000), deleted.getId())))
                .isInstanceOf(ru.selfin.backend.exception.ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Task 3.3: правка суммы без accountId не переносит чужой якорь на счёт-приёмник")
    void update_withoutAccountId_keepsOriginalAccount() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        BalanceCheckpoint existing = cp(LocalDate.of(2026, 8, 1), 300_000,
                LocalDateTime.of(2026, 8, 1, 12, 0), deposit);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(BalanceCheckpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        BalanceCheckpointDto out = service.update(existing.getId(),
                new BalanceCheckpointCreateDto(LocalDate.of(2026, 8, 1), BigDecimal.valueOf(310_000)));

        assertThat(existing.getAccount()).isEqualTo(deposit);
        assertThat(out.accountId()).isEqualTo(deposit.getId());
        assertThat(out.amount()).isEqualByComparingTo("310000");
        verify(accountRepository, never()).findByDefaultAccountTrueAndDeletedFalse();
    }

    @Test
    @DisplayName("Task 3.3: явный accountId в PUT переносит якорь на другой счёт (правка ошибки ввода)")
    void update_withAccountId_movesCheckpoint() {
        Account from = AccountFixtures.defaultAccount();
        Account to = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        BalanceCheckpoint existing = cp(LocalDate.of(2026, 8, 1), 300_000,
                LocalDateTime.of(2026, 8, 1, 12, 0), from);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));
        when(repository.save(any(BalanceCheckpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        BalanceCheckpointDto out = service.update(existing.getId(), new BalanceCheckpointCreateDto(
                LocalDate.of(2026, 8, 1), BigDecimal.valueOf(300_000), to.getId()));

        assertThat(existing.getAccount()).isEqualTo(to);
        assertThat(out.accountId()).isEqualTo(to.getId());
    }
}
