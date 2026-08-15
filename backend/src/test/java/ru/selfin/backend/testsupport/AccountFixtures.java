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

    /**
     * Фиксированный id дефолтного счёта. {@link Account#equals} сравнивает по id — со
     * случайным UUID два независимых вызова {@link #defaultAccount()} были бы тихо не равны
     * друг другу, хотя по смыслу это один и тот же счёт-приёмник из миграции V20.
     */
    private static final UUID DEFAULT_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private AccountFixtures() {}

    /**
     * Предзаполненный билдер счёта нужной природы — доводится вызывающим под конкретный
     * тест (кредитка с планкой/без, вклад, конверт без слежения, не-дефолтный отслеживаемый
     * счёт и т.д., см. Task 2.1).
     */
    public static Account.AccountBuilder account(AccountKind kind, boolean trackBalance) {
        return Account.builder()
                .id(UUID.randomUUID())
                .name("тест " + kind)
                .kind(kind)
                .trackBalance(trackBalance);
    }

    /** Частный случай — дефолтный отслеживаемый DEBIT-счёт, соответствует «Основной карте» из V20. */
    public static Account defaultAccount() {
        return account(AccountKind.DEBIT, true)
                .id(DEFAULT_ACCOUNT_ID)
                .name("Основная карта")
                .defaultAccount(true)
                .build();
    }
}
