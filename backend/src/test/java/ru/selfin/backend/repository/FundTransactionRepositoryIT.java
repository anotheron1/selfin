package ru.selfin.backend.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.FundTransaction;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-9 Task 2.3: {@code sumEnvelopeFundsByTransactionDateLessThanEqual} используется в
 * {@code CapitalService.liquidAt} (спека §4.4) и обязан суммировать ТОЛЬКО копилки БЕЗ
 * {@code account_id} — копилка со счётом уже учтена внутри баланса своего счёта
 * ({@code AccountBalanceService.freeMoneyAt}/{@code semiLiquidAt}), повторное сложение
 * задвоило бы деньги. {@code CapitalServiceLiquidTest} проверяет это на моке репозитория
 * (что {@code CapitalService} не досчитывает сумму сам), а этот тест — единственное место,
 * которое реально исполняет JPQL-фильтр {@code t.fund.accountId IS NULL} на настоящей БД.
 */
@SpringBootTest
@Testcontainers
class FundTransactionRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired FundTransactionRepository fundTxRepo;
    @Autowired TargetFundRepository fundRepo;
    @Autowired AccountRepository accountRepo;

    @AfterEach
    void cleanDb() {
        fundTxRepo.deleteAll();
        fundRepo.deleteAll();
    }

    @Test
    void sumEnvelopeFundsByTransactionDateLessThanEqual_excludesFundsLinkedToAnAccount() {
        TargetFund envelope = fundRepo.save(TargetFund.builder().name("Копилка без счёта").build());
        Account linkedAccount = accountRepo.save(Account.builder()
                .name("Тестовый счёт ANO-9 IT").kind(AccountKind.DEBIT).trackBalance(true).build());
        TargetFund linked = fundRepo.save(TargetFund.builder()
                .name("Копилка на счёте").accountId(linkedAccount.getId()).build());

        fundTxRepo.save(FundTransaction.builder()
                .fund(envelope).amount(new BigDecimal("12000")).transactionDate(LocalDate.now()).build());
        fundTxRepo.save(FundTransaction.builder()
                .fund(linked).amount(new BigDecimal("20000")).transactionDate(LocalDate.now()).build());

        BigDecimal sum = fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now());

        // Только конверт БЕЗ accountId — 20 000 линкованной копилки уже внутри баланса её счёта.
        assertThat(sum).isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("ANO-156: движения удалённой копилки считаются в прошлом и обнуляются с даты выбытия")
    void sumEnvelopeFunds_deletedFund_keepsPastAndClearsFuture() {
        TargetFund envelope = fundRepo.save(TargetFund.builder().name("Отпуск").build());
        LocalDate past = LocalDate.now().minusMonths(1);
        fundTxRepo.save(FundTransaction.builder()
                .fund(envelope).amount(new BigDecimal("20000")).transactionDate(past).build());
        // Выбытие: копилка помечена удалённой И списана движением — как делает сервис.
        fundTxRepo.save(FundTransaction.builder()
                .fund(envelope).amount(new BigDecimal("-20000")).transactionDate(LocalDate.now()).build());
        envelope.setDeleted(true);
        fundRepo.save(envelope);

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(past))
                .as("месяц назад деньги в копилке были, и флаг «удалена сейчас» этого не отменяет")
                .isEqualByComparingTo("20000");
        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("с даты выбытия их нет — ANO-86 держится компенсацией, а не фильтром")
                .isEqualByComparingTo("0");
    }

    @Test
    void sumEnvelopeFundsByTransactionDateLessThanEqual_emptyDb_returnsZero() {
        BigDecimal sum = fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now());

        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
