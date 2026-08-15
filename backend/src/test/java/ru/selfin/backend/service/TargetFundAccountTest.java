package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Копилка, лежащая на реальном счёте (спека §3.3, план Task 3.4).
 *
 * <p>Два режима копилки. Без {@code accountId} — виртуальный конверт, как раньше: своё поле
 * {@code currentBalance}, пополняется переводом. С {@code accountId} — цель и дата поверх
 * чужого остатка: собственный баланс перестаёт быть источником правды, а перевод запрещён.
 * Иначе два числа за одни деньги неизбежно разъедутся.
 */
@ExtendWith(MockitoExtension.class)
class TargetFundAccountTest {

    @Mock TargetFundRepository fundRepo;
    @Mock FundTransactionRepository txRepo;
    @Mock FinancialEventRepository eventRepo;
    @Mock CategoryRepository categoryRepo;
    @Mock AccountRepository accountRepo;
    @Mock BalanceCheckpointRepository checkpointRepo;

    private TargetFundService service;

    @BeforeEach
    void setUp() {
        AccountBalanceService balanceService =
                new AccountBalanceService(accountRepo, checkpointRepo, eventRepo);
        service = new TargetFundService(fundRepo, txRepo, eventRepo, categoryRepo,
                accountRepo, balanceService);
    }

    private static TargetFund fund(UUID accountId, String storedBalance) {
        return TargetFund.builder()
                .id(UUID.randomUUID()).name("На квартиру")
                .targetAmount(new BigDecimal("1000000"))
                .currentBalance(new BigDecimal(storedBalance))
                .status(FundStatus.FUNDING).priority(100)
                .accountId(accountId).deleted(false)
                .build();
    }

    private static BalanceCheckpoint checkpoint(Account a, LocalDate date, String amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(new BigDecimal(amount)).account(a)
                .build();
    }

    @Test
    @DisplayName("§3.3: у копилки со счётом баланс равен остатку СЧЁТА, а не сохранённому полю")
    void fundOnAccount_balanceComesFromAccount_notStoredField() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        LocalDate today = LocalDate.now();
        // Сохранённое поле намеренно ненулевое и ОТЛИЧАЕТСЯ от остатка счёта: если правило
        // не сработает, тест увидит 7 000, а не 300 000.
        TargetFund f = fund(deposit.getId(), "7000");

        when(fundRepo.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(f));
        when(accountRepo.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(checkpointRepo.findLatestForAccountAt(deposit.getId(), today))
                .thenReturn(Optional.of(checkpoint(deposit, today.minusDays(4), "300000")));

        TargetFundDto out = service.getOverview().funds().get(0);

        assertThat(out.currentBalance()).isEqualByComparingTo("300000");
        assertThat(out.accountId()).isEqualTo(deposit.getId());
    }

    @Test
    @DisplayName("§8: копилка ссылается на счёт без чекпоинта — баланс ноль, а не сохранённое поле")
    void fundOnAccountWithoutCheckpoint_balanceIsZero() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        TargetFund f = fund(deposit.getId(), "7000");

        when(fundRepo.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(f));
        when(accountRepo.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        assertThat(service.getOverview().funds().get(0).currentBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Виртуальный конверт (без accountId) по-прежнему держит собственный баланс")
    void envelopeFund_keepsItsOwnBalance() {
        TargetFund f = fund(null, "7000");
        when(fundRepo.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(f));

        assertThat(service.getOverview().funds().get(0).currentBalance()).isEqualByComparingTo("7000");
        // счёт для конверта не запрашивается вовсе
        verify(accountRepo, never()).findById(any());
    }

    @Test
    @DisplayName("§3.3: перевод в копилку на счёте — 400: деньги двигаются на самом счёте")
    void transferToAccountBackedFund_rejected() {
        TargetFund f = fund(UUID.randomUUID(), "0");
        UUID key = UUID.randomUUID();
        when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(fundRepo.findById(f.getId())).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> service.transferToPocket(f.getId(), key, new BigDecimal("5000")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(fundRepo, never()).save(any());
        verify(txRepo, never()).save(any());
    }

    @Test
    @DisplayName("Перевод в виртуальный конверт по-прежнему проходит")
    void transferToEnvelopeFund_stillWorks() {
        TargetFund f = fund(null, "1000");
        UUID key = UUID.randomUUID();
        when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(fundRepo.findById(f.getId())).thenReturn(Optional.of(f));
        when(categoryRepo.findByNameAndDeletedFalse("Переводы в копилки"))
                .thenReturn(Optional.of(ru.selfin.backend.model.Category.builder()
                        .id(UUID.randomUUID()).name("Переводы в копилки").build()));

        service.transferToPocket(f.getId(), key, new BigDecimal("5000"));

        assertThat(f.getCurrentBalance()).isEqualByComparingTo("6000");
        verify(fundRepo).save(f);
    }

    @Test
    @DisplayName("§3.3: две цели на одном счёте — 409, делить остаток между ними нечем")
    void secondFundOnSameAccount_rejectedWithConflict() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        TargetFund existing = fund(deposit.getId(), "0");
        when(accountRepo.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(fundRepo.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(new TargetFundCreateDto("Вторая цель",
                new BigDecimal("100000"), null, null, null, null, null, deposit.getId())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(fundRepo, never()).save(any());
    }

    @Test
    @DisplayName("Копилка на несуществующем счёте — 404")
    void fundOnUnknownAccount_rejected() {
        UUID unknown = UUID.randomUUID();
        when(accountRepo.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new TargetFundCreateDto("Цель",
                new BigDecimal("100000"), null, null, null, null, null, unknown)))
                .isInstanceOf(ru.selfin.backend.exception.ResourceNotFoundException.class);

        verify(fundRepo, never()).save(any());
    }
}
