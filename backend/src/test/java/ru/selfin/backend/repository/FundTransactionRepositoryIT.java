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
import ru.selfin.backend.model.FundAccountLink;
import ru.selfin.backend.model.FundTransaction;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-9 Task 2.3: {@code sumEnvelopeFundsByTransactionDateLessThanEqual} используется в
 * {@code CapitalService.liquidAt} (спека §4.4) и обязан суммировать ТОЛЬКО копилки-конверты —
 * копилка со счётом уже учтена внутри баланса своего счёта
 * ({@code AccountBalanceService.freeMoneyAt}/{@code semiLiquidAt}), повторное сложение
 * задвоило бы деньги. {@code CapitalServiceLiquidTest} проверяет это на моке репозитория
 * (что {@code CapitalService} не досчитывает сумму сам), а этот тест — единственное место,
 * которое реально исполняет JPQL-условие на настоящей БД.
 *
 * <p><b>ANO-163:</b> «конверт» — это состояние копилки В ТОТ ДЕНЬ, а не сегодня. Условие
 * читает историю привязок ({@link FundAccountLink}), поэтому тесты кладут её строки сами —
 * так, как их пишет {@code TargetFundService}. Спека:
 * {@code docs/superpowers/specs/2026-09-24-fund-link-history-design.md}.
 */
@SpringBootTest
@Testcontainers
class FundTransactionRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired FundTransactionRepository fundTxRepo;
    @Autowired FundAccountLinkRepository linkRepo;
    @Autowired TargetFundRepository fundRepo;
    @Autowired AccountRepository accountRepo;

    @AfterEach
    void cleanDb() {
        fundTxRepo.deleteAll();
        linkRepo.deleteAll();
        fundRepo.deleteAll();
    }

    @Test
    @DisplayName("Копилка, которая в этот день живёт на счёте, в сумму конвертов не входит")
    void sumEnvelopeFunds_excludesFundOnAnAccountThatDay() {
        TargetFund envelope = fundRepo.save(TargetFund.builder().name("Копилка без счёта").build());
        Account account = saveAccount();
        TargetFund linked = fundRepo.save(TargetFund.builder()
                .name("Копилка на счёте").accountId(account.getId()).build());
        linkRepo.save(link(linked, account, LocalDate.now(), null));

        fundTxRepo.save(tx(envelope, "12000", LocalDate.now()));
        fundTxRepo.save(tx(linked, "20000", LocalDate.now()));

        assertThat(sum(LocalDate.now()))
                .as("только конверт: 20 000 копилки на счёте уже внутри остатка её счёта")
                .isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("ANO-163: привязка сегодня не меняет сумму конвертов за прошлый месяц")
    void sumEnvelopeFunds_linkedToday_keepsPast() {
        Account account = saveAccount();
        TargetFund fund = fundRepo.save(TargetFund.builder()
                .name("Отпуск").accountId(account.getId()).build());
        LocalDate monthAgo = LocalDate.now().minusMonths(1);
        fundTxRepo.save(tx(fund, "20000", monthAgo));
        linkRepo.save(link(fund, account, LocalDate.now(), null));

        assertThat(sum(monthAgo))
                .as("месяц назад копилка была конвертом, и сегодняшняя привязка этого не отменяет")
                .isEqualByComparingTo("20000");
        assertThat(sum(LocalDate.now()))
                .as("сегодня её деньги в остатке счёта — сложить их ещё раз значило бы задвоить")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-163: после отвязки период на счёте остаётся прошлым; день привязки — на счёте, "
            + "день отвязки — конверт")
    void sumEnvelopeFunds_unlinked_keepsPeriodOnAccount() {
        Account account = saveAccount();
        TargetFund fund = fundRepo.save(TargetFund.builder().name("Отпуск").build());
        LocalDate twoMonthsAgo = LocalDate.now().minusMonths(2);
        LocalDate monthAgo = LocalDate.now().minusMonths(1);
        fundTxRepo.save(tx(fund, "20000", twoMonthsAgo));
        linkRepo.save(link(fund, account, monthAgo, LocalDate.now()));

        assertThat(sum(twoMonthsAgo))
                .as("до привязки копилка — конверт").isEqualByComparingTo("20000");
        assertThat(sum(monthAgo))
                .as("день привязки — уже день на счёте").isEqualByComparingTo("0");
        assertThat(sum(LocalDate.now()))
                .as("день отвязки — снова конверт").isEqualByComparingTo("20000");
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

    private BigDecimal sum(LocalDate date) {
        return fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(date);
    }

    private Account saveAccount() {
        return accountRepo.save(Account.builder()
                .name("Тестовый счёт ANO-163 IT").kind(AccountKind.DEBIT).trackBalance(true).build());
    }

    private static FundTransaction tx(TargetFund fund, String amount, LocalDate date) {
        return FundTransaction.builder()
                .fund(fund).amount(new BigDecimal(amount)).transactionDate(date).build();
    }

    /** Период на счёте; {@code to == null} — копилка на счёте и сейчас. */
    private static FundAccountLink link(TargetFund fund, Account account, LocalDate from, LocalDate to) {
        return FundAccountLink.builder()
                .fundId(fund.getId()).accountId(account.getId())
                .linkedFrom(from).linkedTo(to).build();
    }
}
