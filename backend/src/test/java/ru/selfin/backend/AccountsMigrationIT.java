package ru.selfin.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Регрессия миграции V20 (ANO-9 §7.1, §9.1): ровно один счёт, дефолтный и
 * отслеживаемый, все существующие чекпоинты получают его адрес, второй
 * дефолтный счёт запрещён частичным уникальным индексом.
 */
@SpringBootTest
@Testcontainers
class AccountsMigrationIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired AccountRepository accountRepository;
    @Autowired BalanceCheckpointRepository checkpointRepository;

    @Test
    void migration_createsExactlyOneDefaultTrackedDebitAccount() {
        // Утверждаем ровно то, что гарантирует миграция: одна строка с is_default,
        // не общее число счетов — иначе тест зависит от того, что делают другие
        // методы этого класса (контейнер общий на класс, не на метод).
        List<Account> defaults = accountRepository.findAll().stream()
                .filter(Account::isDefaultAccount)
                .toList();

        assertThat(defaults).hasSize(1);
        Account account = defaults.get(0);
        assertThat(account.isTrackBalance()).isTrue();
        assertThat(account.getKind()).isEqualTo(AccountKind.DEBIT);
    }

    @Test
    void checkpointSavedViaRepository_hasNonNullAccount() {
        Account defaultAccount = accountRepository.findByDefaultAccountTrueAndDeletedFalse().orElseThrow();

        BalanceCheckpoint saved = checkpointRepository.save(BalanceCheckpoint.builder()
                .date(LocalDate.now())
                .amount(BigDecimal.valueOf(1000))
                .account(defaultAccount)
                .build());
        try {
            BalanceCheckpoint reloaded = checkpointRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getAccount()).isNotNull();
            assertThat(reloaded.getAccount().getId()).isEqualTo(defaultAccount.getId());
        } finally {
            // Контейнер общий на класс, не на метод (см. javadoc выше) — не оставляем
            // чекпоинт висеть, чтобы он не просочился в другой тест этого класса.
            checkpointRepository.deleteById(saved.getId());
        }
    }

    @Test
    void secondDefaultAccount_violatesPartialUniqueIndex() {
        assertThatThrownBy(() -> accountRepository.saveAndFlush(Account.builder()
                .name("Вторая карта")
                .kind(AccountKind.DEBIT)
                .trackBalance(true)
                .defaultAccount(true)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_accounts_single_default");
    }
}
