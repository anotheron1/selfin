package ru.selfin.backend.testsupport;

import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.enums.AccountKind;

import java.util.UUID;

/**
 * Общая тестовая фикстура счёта (ANO-9). {@link ru.selfin.backend.model.BalanceCheckpoint#account}
 * теперь {@code optional = false}, поэтому любой тест, собирающий чекпоинт через builder,
 * обязан подставить счёт — даже юнит-тесты с замоканными репозиториями, которым сама
 * природа счёта безразлична.
 */
public final class AccountFixtures {

    private AccountFixtures() {}

    /** Дефолтный отслеживаемый DEBIT-счёт — соответствует «Основной карте» из V20. */
    public static Account defaultAccount() {
        return Account.builder()
                .id(UUID.randomUUID())
                .name("Основная карта")
                .kind(AccountKind.DEBIT)
                .trackBalance(true)
                .defaultAccount(true)
                .build();
    }
}
