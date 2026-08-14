# Скелет: счета и поведенческие модели — план реализации (ANO-9)

> **Для агентов:** используйте superpowers:subagent-driven-development или superpowers:executing-plans. Шаги отмечаются чекбоксами.

**Цель:** ввести сущность «Счёт» так, чтобы свободные деньги считались как сумма ликвидных отслеживаемых счетов, кредитка мерялась планкой доступного, а форма ввода транзакции не изменилась ни на одно поле.

**Архитектура:** счёт становится адресом для существующего механизма чекпоинтов, а не новым параллельным миром. Транзакции остаются безадресными и относятся к одному счёту-приёмнику. Новый `AccountBalanceService` — единственное место, где живёт правило «остаток счёта на дату»; `PocketEngine` остаётся чистым и получает готовые суммы во входе.

**Стек:** Java 21, Spring Boot 4.0.3, PostgreSQL, Flyway, Lombok, JUnit 5, Mockito, Testcontainers; фронт React 18 + TypeScript + Vite + Tailwind + shadcn.

**Спека:** `docs/superpowers/specs/2026-08-12-accounts-skeleton-design.md`. Ссылки вида «спека §4.1» ведут туда.

---

## Как запускать тесты

Из каталога `backend`:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
```

Один класс:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketEngineAccountsTest
```

Юниты плюс IT (нужен запущенный Docker — Testcontainers поднимает PostgreSQL):

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Пропустить IT: добавить `-DskipITs`. Failsafe подключён в `backend/pom.xml`, классы `*IT` идут через него.

Фронт из каталога `frontend`: `npm run build` для проверки типов.

**Про даты в тестах.** `Clock` ещё не внедрён (ANO-39), `LocalDate.now()` зовётся напрямую в 31 месте. Тесты этой задачи писать так, чтобы граничный день существовал в любом месяце: брать `today` или `today.minusDays(n)`, никогда `today.plusDays(1)` в роли «будущее внутри текущего месяца» — 31-го числа оно уезжает в следующий месяц и тест падает. Прецедент: PR #28.

---

## Карта файлов

**Создаются (бэкенд):**

| Файл | Ответственность |
|---|---|
| `backend/src/main/java/ru/selfin/backend/model/enums/AccountKind.java` | Перечисление природы счёта |
| `backend/src/main/java/ru/selfin/backend/model/Account.java` | Сущность счёта |
| `backend/src/main/java/ru/selfin/backend/repository/AccountRepository.java` | Доступ к счетам |
| `backend/src/main/java/ru/selfin/backend/service/AccountBalanceService.java` | Единственное место правила «остаток счёта на дату» + производные суммы |
| `backend/src/main/java/ru/selfin/backend/service/AccountService.java` | CRUD и инварианты счёта |
| `backend/src/main/java/ru/selfin/backend/controller/AccountController.java` | REST `/api/v1/accounts` |
| `backend/src/main/java/ru/selfin/backend/dto/account/*.java` | DTO запросов и ответов |
| `backend/src/main/resources/db/migration/V20__add_accounts.sql` | Таблица, сид, адресация чекпоинтов и копилок |

**Меняются (бэкенд):**

| Файл | Что |
|---|---|
| `model/BalanceCheckpoint.java` | Поле `account` |
| `model/TargetFund.java` | Поле `accountId` |
| `repository/BalanceCheckpointRepository.java` | Запросы в разрезе счёта |
| `dto/pocket/PocketInput.java` | Три новых поля со значением по умолчанию |
| `dto/pocket/PocketResultDto.java` | Второе и третье числа |
| `dto/pocket/BreakdownType.java` | `CREDIT_RESTORE` |
| `service/PocketEngine.java` | Шаг 1 расчёта, второе и третье числа, строка breakdown |
| `service/PocketInputAssembler.java` | Заполнение новых полей входа |
| `service/CapitalService.java` | `liquidAt` и обязательства через счета |
| `service/BalanceCheckpointService.java` | Дрейф внутри счёта |
| `service/TargetFundService.java` | Баланс копилки со ссылкой на счёт |

**Меняются (фронт):**

| Файл | Что |
|---|---|
| `frontend/src/types/api.ts` | Типы счёта, поля ответа кармашка |
| `frontend/src/api/index.ts` | Вызовы `/accounts` |
| `frontend/src/pages/Settings.tsx` | Секция «Счета» |
| `frontend/src/components/accounts/*` | Список, карточка, форма |
| `frontend/src/components/pocket/ReanchorSheet.tsx` | Выбор счёта |
| Экран кармашка | Второе и третье числа |

---

## Chunk 1: Фундамент — сущность и миграция без изменения поведения

**Задача чанка:** после него приложение работает ровно как раньше, но у денег появился адрес. Ни один расчёт не меняется. Это позволяет мерджить отдельно и спокойно.

### Task 1.1: Перечисление и сущность

**Файлы:**
- Создать: `backend/src/main/java/ru/selfin/backend/model/enums/AccountKind.java`
- Создать: `backend/src/main/java/ru/selfin/backend/model/Account.java`

- [ ] **Шаг 1: Написать падающий тест на поведенческое правило**

Создать `backend/src/test/java/ru/selfin/backend/model/AccountTest.java`:

```java
package ru.selfin.backend.model;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.enums.AccountKind;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    private static Account of(AccountKind kind, boolean track) {
        return Account.builder().name("тест").kind(kind).trackBalance(track).build();
    }

    @Test
    void debitWithTrackingCountsAsFreeMoney() {
        assertTrue(of(AccountKind.DEBIT, true).countsAsFreeMoney());
    }

    @Test
    void envelopeWithoutTrackingDoesNotCount() {
        // Конверт: за остатком не следим, значит посчитать его нечем (спека §4.1)
        assertFalse(of(AccountKind.DEBIT, false).countsAsFreeMoney());
    }

    @Test
    void creditNeverCountsAsFreeMoney() {
        assertFalse(of(AccountKind.CREDIT, true).countsAsFreeMoney());
    }

    @Test
    void depositIsSemiLiquidNotFreeMoney() {
        assertFalse(of(AccountKind.DEPOSIT, true).countsAsFreeMoney());
    }

    @Test
    void deletedAccountDropsOut() {
        Account a = of(AccountKind.DEBIT, true);
        a.setDeleted(true);
        assertFalse(a.countsAsFreeMoney());
    }
}
```

- [ ] **Шаг 2: Запустить, убедиться что не компилируется**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=AccountTest
```

Ожидаемо: ошибка компиляции, `Account` и `AccountKind` не существуют.

- [ ] **Шаг 3: Создать перечисление**

```java
package ru.selfin.backend.model.enums;

/**
 * Природа счёта (спека §3.1). Решает, куда попадают деньги:
 * DEBIT и CASH — в свободные, DEPOSIT — в капитал минуя свободные,
 * CREDIT — в обязательства.
 */
public enum AccountKind {
    DEBIT, CREDIT, DEPOSIT, CASH
}
```

- [ ] **Шаг 4: Создать сущность**

```java
package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Счёт — место, где лежат деньги, с выбираемой пользователем поведенческой моделью
 * (спека 2026-08-12-accounts-skeleton-design.md §3.1).
 *
 * <p>Четыре оси: природа {@code kind}; слежение за остатком {@code trackBalance};
 * зона ответственности {@code purposeCategory}; кредитные {@code creditLimit} и
 * {@code availableFloor} (планка возврата).
 *
 * <p><b>ВАЖНО.</b> У счёта с {@code kind = CREDIT} чекпоинт хранит ДОСТУПНЫЙ ОСТАТОК,
 * а не долг. Долг вычисляется как {@code creditLimit − доступно}. Так мыслит пользователь
 * (принцип №4 подхода, §1 спеки) — не «переворачивать обратно» при реализации.
 *
 * <p>{@code defaultAccount} — счёт-приёмник безадресных операций. Транзакции адреса не
 * имеют осознанно (§5.1), поэтому ровно один счёт объявлен приёмником. Это же место,
 * куда позже воткнётся импорт (ANO-10).
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountKind kind;

    /** Слежу ли за остатком. Главный тумблер: решает и участие в свободных деньгах,
     *  и смысл пополнения конверта (§5.2). */
    @Column(name = "track_balance", nullable = false)
    private boolean trackBalance;

    /** Зона ответственности: мягкая, переназначаемая (бензин → авто → транспорт). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_category_id")
    private Category purposeCategory;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    /** Планка возврата: уровень доступного, к которому возвращаешься. null — не задана,
     *  второе число кармашка не показывается (§3.1). */
    @Column(name = "available_floor", precision = 19, scale = 2)
    private BigDecimal availableFloor;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean defaultAccount = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 100;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Даёт ли счёт свободные деньги (спека §4.1). За чем не следишь — того не считаешь. */
    public boolean countsAsFreeMoney() {
        return !deleted && trackBalance && (kind == AccountKind.DEBIT || kind == AccountKind.CASH);
    }

    /** Полу-ликвид: входит в капитал, но не в свободные деньги (§4.3). */
    public boolean isSemiLiquid() {
        return !deleted && trackBalance && kind == AccountKind.DEPOSIT;
    }
}
```

- [ ] **Шаг 5: Запустить тест, убедиться что зелёный**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=AccountTest
```

Ожидаемо: 5 passed.

- [ ] **Шаг 6: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/model/Account.java \
        backend/src/main/java/ru/selfin/backend/model/enums/AccountKind.java \
        backend/src/test/java/ru/selfin/backend/model/AccountTest.java
git commit -m "feat(accounts): сущность счёта с поведенческими осями (ANO-9)"
```

### Task 1.2: Миграция V20

**Файлы:**
- Создать: `backend/src/main/resources/db/migration/V20__add_accounts.sql`
- Изменить: `backend/src/main/java/ru/selfin/backend/model/BalanceCheckpoint.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/model/TargetFund.java`
- Создать: `backend/src/main/java/ru/selfin/backend/repository/AccountRepository.java`

- [ ] **Шаг 1: Написать падающий IT на миграцию**

Создать `backend/src/test/java/ru/selfin/backend/AccountsMigrationIT.java`. Взять за образец существующий `backend/src/test/java/ru/selfin/backend/CapitalControllerIT.java` — там уже настроен Testcontainers-контекст, скопировать аннотации класса.

Тест проверяет три вещи:

```java
@Test
void migrationCreatesDefaultAccount() {
    List<Account> all = accountRepository.findAll();
    assertEquals(1, all.size());
    Account seeded = all.get(0);
    assertTrue(seeded.isDefaultAccount());
    assertTrue(seeded.isTrackBalance());
    assertEquals(AccountKind.DEBIT, seeded.getKind());
}

@Test
void existingCheckpointsGetDefaultAccount() {
    // чекпоинт, созданный ДО проверки, обязан иметь адрес
    BalanceCheckpoint cp = checkpointRepository.save(BalanceCheckpoint.builder()
            .date(LocalDate.now()).amount(new BigDecimal("1000.00"))
            .account(accountRepository.findAll().get(0))
            .build());
    assertNotNull(checkpointRepository.findById(cp.getId()).orElseThrow().getAccount());
}

@Test
void secondDefaultAccountIsRejected() {
    Account another = Account.builder().name("Вторая").kind(AccountKind.DEBIT)
            .trackBalance(true).defaultAccount(true).build();
    assertThrows(DataIntegrityViolationException.class, () -> {
        accountRepository.save(another);
        accountRepository.flush();
    });
}
```

- [ ] **Шаг 2: Запустить, убедиться что падает**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify -Dit.test=AccountsMigrationIT
```

Ожидаемо: ошибка компиляции либо падение на отсутствии таблицы `accounts`.

- [ ] **Шаг 3: Написать миграцию**

```sql
-- V20: счета (ANO-9).
-- Спека: docs/superpowers/specs/2026-08-12-accounts-skeleton-design.md §3, §7.
--
-- Инвариант миграции: кармашек после неё обязан совпасть с прежним до копейки.
-- Достигается тем, что создаётся ровно один счёт — одновременно дефолтный и
-- отслеживаемый — и все существующие чекпоинты адресуются ему. Формула §4.1
-- при таком раскладе схлопывается в прежнюю.

CREATE TABLE accounts (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255)   NOT NULL,
    kind                VARCHAR(16)    NOT NULL
                        CHECK (kind IN ('DEBIT', 'CREDIT', 'DEPOSIT', 'CASH')),
    track_balance       BOOLEAN        NOT NULL,
    purpose_category_id UUID           REFERENCES categories(id),
    credit_limit        NUMERIC(19, 2),
    available_floor     NUMERIC(19, 2),
    is_default          BOOLEAN        NOT NULL DEFAULT FALSE,
    sort_order          INT            NOT NULL DEFAULT 100,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    is_deleted          BOOLEAN        NOT NULL DEFAULT FALSE,

    -- кредитные поля бывают только у кредитного счёта
    CONSTRAINT ck_accounts_credit_fields CHECK (
        kind = 'CREDIT' OR (credit_limit IS NULL AND available_floor IS NULL)),
    -- планка не выше лимита
    CONSTRAINT ck_accounts_floor_le_limit CHECK (
        available_floor IS NULL OR credit_limit IS NULL OR available_floor <= credit_limit),
    -- вклад без остатка бессмыслен: он полу-ликвид в капитале
    CONSTRAINT ck_accounts_deposit_tracked CHECK (
        kind <> 'DEPOSIT' OR track_balance),
    -- счёт-приёмник безадресных операций обязан быть отслеживаемым и ликвидным
    CONSTRAINT ck_accounts_default_tracked CHECK (
        NOT is_default OR (track_balance AND kind IN ('DEBIT', 'CASH')))
);

-- не более одного дефолтного среди живых
CREATE UNIQUE INDEX uq_accounts_single_default
    ON accounts ((is_default)) WHERE is_default AND NOT is_deleted;

CREATE INDEX idx_accounts_active ON accounts (sort_order) WHERE NOT is_deleted;

-- Сид: единственный счёт, в который переезжает вся текущая картина.
INSERT INTO accounts (name, kind, track_balance, is_default, sort_order)
VALUES ('Основная карта', 'DEBIT', TRUE, TRUE, 0);

-- Адресация чекпоинтов
ALTER TABLE balance_checkpoints ADD COLUMN account_id UUID REFERENCES accounts(id);
UPDATE balance_checkpoints SET account_id = (SELECT id FROM accounts WHERE is_default);
ALTER TABLE balance_checkpoints ALTER COLUMN account_id SET NOT NULL;
CREATE INDEX idx_checkpoints_account_date ON balance_checkpoints (account_id, date DESC);

-- Необязательная привязка копилки к счёту; одна цель на счёт (спека §3.3)
ALTER TABLE target_funds ADD COLUMN account_id UUID REFERENCES accounts(id);
CREATE UNIQUE INDEX uq_funds_one_per_account
    ON target_funds (account_id) WHERE account_id IS NOT NULL AND NOT is_deleted;
```

- [ ] **Шаг 4: Добавить поле в `BalanceCheckpoint`**

В `backend/src/main/java/ru/selfin/backend/model/BalanceCheckpoint.java` после поля `amount`:

```java
    /**
     * Счёт, чей остаток зафиксирован. Для {@code AccountKind.CREDIT} здесь
     * ДОСТУПНЫЙ ОСТАТОК, не долг (спека §3.2).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
```

- [ ] **Шаг 5: Добавить поле в `TargetFund`**

```java
    /**
     * Счёт, на котором физически лежат деньги цели (спека §3.3). Если задан,
     * {@code currentBalance} перестаёт быть источником правды и вычисляется
     * из остатка счёта — иначе два числа за одни деньги разъедутся.
     */
    @Column(name = "account_id")
    private UUID accountId;
```

- [ ] **Шаг 6: Создать репозиторий**

```java
package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByDeletedFalseOrderBySortOrderAscNameAsc();

    Optional<Account> findByDefaultAccountTrueAndDeletedFalse();

    boolean existsByPurposeCategoryIdAndDeletedFalse(UUID categoryId);
}
```

- [ ] **Шаг 7: Запустить IT, убедиться что зелёный**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify -Dit.test=AccountsMigrationIT
```

- [ ] **Шаг 8: Прогнать весь пакет — миграция не должна ничего сломать**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Ожидаемо: всё зелёное. Если падают тесты, создающие `BalanceCheckpoint` без счёта — это ожидаемо, они чинятся в следующем шаге.

- [ ] **Шаг 9: Починить тесты, создающие чекпоинты**

Найти места:

```bash
grep -rln "BalanceCheckpoint.builder()" backend/src/test backend/src/main
```

В каждом добавить `.account(...)`. Для юнит-тестов с моками достаточно `Account.builder().name("тест").kind(AccountKind.DEBIT).trackBalance(true).defaultAccount(true).build()`. Завести общий хелпер в тестах, чтобы не плодить копии.

- [ ] **Шаг 10: Коммит**

```bash
git add backend/src/main/resources/db/migration/V20__add_accounts.sql \
        backend/src/main/java/ru/selfin/backend/model/ \
        backend/src/main/java/ru/selfin/backend/repository/AccountRepository.java \
        backend/src/test/
git commit -m "feat(accounts): миграция V20 — адресация чекпоинтов и копилок (ANO-9)"
```

### Task 1.3: Регрессия — кармашек не сдвинулся

**Это условие приёмки всего чанка.** Без него нельзя мерджить.

**Файлы:**
- Создать: `backend/src/test/java/ru/selfin/backend/service/PocketMigrationRegressionTest.java`

- [ ] **Шаг 1: Написать тест**

Собрать `PocketInput` с одним счётом (дефолтный + отслеживаемый), чекпоинтом и набором фактов и планов. Прогнать `PocketEngine.calculate`. Зафиксировать эталон: `pocket`, `currentBalance`, `minPoint`, полный список строк breakdown с суммами.

Эталон брать из прогона на текущем `main` до изменений движка — то есть сначала написать тест, убедиться что он зелёный СЕЙЧАС, и только потом трогать движок в Chunk 2.

```java
@Test
void singleDefaultAccountReproducesLegacyNumbers() {
    PocketInput in = /* один счёт, чекпоинт 50 000 от 01-го, три плановых расхода, один доход */;
    PocketResultDto r = PocketEngine.calculate(in);

    assertEquals(new BigDecimal("50000.00"), r.currentBalance());
    assertEquals(/* эталон */, r.pocket());
    assertEquals(/* эталон */, r.minPoint().balance());
    assertEquals(List.of(STARTING_BALANCE, PLANNED_EXPENSES, PLANNED_INCOME, TRAJECTORY_MIN, POCKET),
            r.breakdown().stream().map(BreakdownLine::type).toList());
}
```

- [ ] **Шаг 2: Запустить, убедиться что зелёный на текущем коде**

Это не TDD-падение, а фиксация эталона. Тест обязан пройти до изменений движка.

- [ ] **Шаг 3: Коммит**

```bash
git commit -am "test(accounts): эталон кармашка до перехода на счета (ANO-9)"
```

---

## Chunk 2: Свободные деньги как сумма счетов

**Задача чанка:** расчёт начинает видеть несколько счетов. Тест из Task 1.3 обязан остаться зелёным без правок — это доказательство, что для одного счёта ничего не изменилось.

### Task 2.1: `AccountBalanceService`

**Файлы:**
- Создать: `backend/src/main/java/ru/selfin/backend/service/AccountBalanceService.java`
- Создать: `backend/src/test/java/ru/selfin/backend/service/AccountBalanceServiceTest.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/repository/BalanceCheckpointRepository.java`

- [ ] **Шаг 1: Добавить запросы в разрезе счёта**

```java
    /** Последний чекпоинт счёта с {@code date ≤ t}; tiebreak created_at (ре-якорь дважды за день). */
    @Query("""
        SELECT cp FROM BalanceCheckpoint cp
        WHERE cp.account.id = :accountId AND cp.date <= :date
        ORDER BY cp.date DESC, cp.createdAt DESC LIMIT 1
        """)
    Optional<BalanceCheckpoint> findLatestForAccountAt(@Param("accountId") UUID accountId,
                                                       @Param("date") LocalDate date);

    /** История чекпоинтов одного счёта, от свежих к старым (цепочка дрейфа считается внутри счёта). */
    @Query("""
        SELECT cp FROM BalanceCheckpoint cp WHERE cp.account.id = :accountId
        ORDER BY cp.date DESC, cp.createdAt DESC
        """)
    List<BalanceCheckpoint> findAllForAccountOrderByDateDesc(@Param("accountId") UUID accountId);
```

Существующие методы не удалять — они ещё используются; чистка в Task 2.4.

- [ ] **Шаг 2: Написать падающие тесты сервиса**

Покрыть, с моками репозиториев:

1. `balanceAt` дефолтного счёта = якорь + знаковые факты строго после даты якоря и не позже `t`.
2. `balanceAt` НЕ дефолтного счёта = якорь без фактов (известное ограничение §6).
3. `balanceAt` счёта без чекпоинта = ноль.
4. `otherFreeMoneyAt` не включает дефолтный, конверты без слежения, кредитки и вклады.
5. `semiLiquidAt` = сумма вкладов.
6. `creditRestoreReserveAt` = сумма `max(0, планка − доступно)`; кредитка без планки даёт ноль; доступно выше планки даёт ноль.
7. `creditDebtAt` = сумма `max(0, лимит − доступно)`; кредитка без чекпоинта молчит, а не считает ноль долгом.

- [ ] **Шаг 3: Реализовать сервис**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Единственное место, где живёт правило «остаток счёта на дату» (спека §4.1).
 *
 * <p>Безадресные факты применяются ТОЛЬКО к дефолтному счёту: транзакции адреса не
 * имеют осознанно (§5.1), разнести их по счетам нечем. Остальные счета двигаются
 * только чекпоинтами — известное ограничение первой версии (§6). Фиктивных операций,
 * чтобы это обойти, не выдумываем: замерший остаток честнее мусора в аналитике.
 *
 * <p>NB: правило отбора фактов продублировано в {@link PocketEngine} — движок чистый и
 * суммирует факты сам. Схождение этих двух мест — предмет ANO-23, здесь не решается,
 * но при правке одного обязательно править второе.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBalanceService {

    private final AccountRepository accountRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final FinancialEventRepository eventRepository;

    public Optional<Account> defaultAccount() {
        return accountRepository.findByDefaultAccountTrueAndDeletedFalse();
    }

    public List<Account> active() {
        return accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc();
    }

    public Optional<BalanceCheckpoint> anchorAt(Account a, LocalDate t) {
        return checkpointRepository.findLatestForAccountAt(a.getId(), t);
    }

    /** Остаток счёта на дату. Для CREDIT — доступный остаток. */
    public BigDecimal balanceAt(Account a, LocalDate t) {
        Optional<BalanceCheckpoint> anchor = anchorAt(a, t);
        if (anchor.isEmpty()) return BigDecimal.ZERO;
        BigDecimal base = anchor.get().getAmount();
        if (!a.isDefaultAccount()) return base;
        return base.add(factsDelta(anchor.get().getDate(), t));
    }

    /** Свободные деньги со счетов, КРОМЕ дефолтного: его считает движок кармашка сам. */
    public BigDecimal otherFreeMoneyAt(LocalDate t) {
        return active().stream()
                .filter(Account::countsAsFreeMoney)
                .filter(a -> !a.isDefaultAccount())
                .map(a -> balanceAt(a, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Полу-ликвид: вклады (§4.3). */
    public BigDecimal semiLiquidAt(LocalDate t) {
        return active().stream()
                .filter(Account::isSemiLiquid)
                .map(a -> balanceAt(a, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Резерв возврата карт к планке (§4.2). Счёт без планки в резерв не входит. */
    public BigDecimal creditRestoreReserveAt(LocalDate t) {
        return creditGap(t, Account::getAvailableFloor);
    }

    /** Долг по кредиткам для капитала (§4.4). Считается от лимита, а не от планки. */
    public BigDecimal creditDebtAt(LocalDate t) {
        return creditGap(t, Account::getCreditLimit);
    }

    private BigDecimal creditGap(LocalDate t, java.util.function.Function<Account, BigDecimal> level) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Account a : active()) {
            if (a.getKind() != AccountKind.CREDIT) continue;
            BigDecimal target = level.apply(a);
            if (target == null) continue;
            Optional<BalanceCheckpoint> anchor = anchorAt(a, t);
            if (anchor.isEmpty()) continue; // нет остатка — счёт молчит, а не считает ноль
            BigDecimal gap = target.subtract(anchor.get().getAmount());
            if (gap.signum() > 0) sum = sum.add(gap);
        }
        return sum;
    }

    /**
     * Знаковая сумма фактов в {@code (from, to]}. Правило совпадает с шагом 1
     * {@link PocketEngine#calculate}: только факты, не-хотелки, дата не позже {@code to}
     * и строго после даты якоря — операции дня чекпоинта уже внутри его суммы (ANO-15 §5).
     */
    private BigDecimal factsDelta(LocalDate from, LocalDate to) {
        return eventRepository.findAllByDeletedFalseAndDateBetween(from, to).stream()
                .filter(e -> e.getFactAmount() != null)
                .filter(e -> e.getWishlistStatus() == null)
                .filter(e -> e.getDate() != null && e.getDate().isAfter(from))
                .map(e -> e.getType() == EventType.INCOME
                        ? e.getFactAmount() : e.getFactAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

- [ ] **Шаг 4: Прогнать тесты сервиса**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=AccountBalanceServiceTest
```

- [ ] **Шаг 5: Коммит**

```bash
git commit -am "feat(accounts): AccountBalanceService — остаток счёта и производные суммы (ANO-9)"
```

### Task 2.2: Движок кармашка видит несколько счетов

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketInput.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java:57-63`
- Изменить: `backend/src/main/java/ru/selfin/backend/service/PocketInputAssembler.java:201-206`
- Создать: `backend/src/test/java/ru/selfin/backend/service/PocketEngineAccountsTest.java`

- [ ] **Шаг 1: Написать падающий тест**

```java
@Test
void freeMoneyIsSumOfTrackedLiquidAccounts() {
    // дефолтный счёт: якорь 50 000; второй отслеживаемый: 20 000
    PocketInput in = inputWithCheckpoint(new BigDecimal("50000.00"))
            .withOtherAccountsBalance(new BigDecimal("20000.00"));
    assertEquals(new BigDecimal("70000.00"), PocketEngine.calculate(in).currentBalance());
}

@Test
void addresslessFactsHitOnlyDefaultAccount() {
    // факт −5 000 после якоря уменьшает только дефолтный, второй счёт не трогает
    ...
    assertEquals(new BigDecimal("65000.00"), PocketEngine.calculate(in).currentBalance());
}
```

- [ ] **Шаг 2: Запустить, убедиться что не компилируется**

- [ ] **Шаг 3: Расширить `PocketInput`**

Добавить три поля в конец записи и безопасные геттеры рядом с `futureForecastOrEmpty()`:

```java
        /** Свободные деньги с прочих счетов, кроме дефолтного (спека §4.1). */
        BigDecimal otherAccountsBalance,
        /** Резерв возврата кредитных карт к планке (§4.2). */
        BigDecimal creditRestoreReserve,
        /** Полу-ликвид: вклады, для третьего числа (§4.3). */
        BigDecimal semiLiquidBalance
```

```java
    public BigDecimal otherAccountsBalanceOrZero() { return orZero(otherAccountsBalance); }
    public BigDecimal creditRestoreReserveOrZero() { return orZero(creditRestoreReserve); }
    public BigDecimal semiLiquidBalanceOrZero() { return orZero(semiLiquidBalance); }

    private static BigDecimal orZero(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
```

Значение `null` обязано вести себя как ноль — это сохраняет зелёными все существующие тесты, конструирующие `PocketInput` старым способом. Тот же приём уже применён для `futureForecast`.

- [ ] **Шаг 4: Изменить шаг 1 движка**

В `PocketEngine.calculate`, после цикла суммирования фактов:

```java
        // Прочие счета (спека §4.1): их остатки уже посчитаны сборщиком входа,
        // безадресные факты к ним не применяются — они относятся к дефолтному счёту.
        currentBalance = currentBalance.add(in.otherAccountsBalanceOrZero());
```

- [ ] **Шаг 5: Заполнить поля в сборщике**

В `PocketInputAssembler.build`, перед созданием `PocketInput`:

```java
        // 6. Счета (спека §4.1–§4.3)
        BigDecimal otherAccounts = accountBalanceService.otherFreeMoneyAt(asOfDate);
        BigDecimal creditReserve = accountBalanceService.creditRestoreReserveAt(asOfDate);
        BigDecimal semiLiquid = accountBalanceService.semiLiquidAt(asOfDate);
```

Чекпоинт брать теперь у дефолтного счёта: заменить `checkpointRepository.findTopByOrderByDateDesc()` на поиск якоря дефолтного счёта через `accountBalanceService`. Если дефолтного счёта нет (невозможно после миграции, но защищаемся) — вести себя как раньше, от нуля.

- [ ] **Шаг 6: Прогнать новый тест и регрессию Task 1.3**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketEngineAccountsTest+PocketMigrationRegressionTest
```

Регрессия обязана быть зелёной **без единой правки**. Если пришлось её править — значит поведение для одного счёта изменилось, и это баг, а не тест.

- [ ] **Шаг 7: Прогнать всё**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

- [ ] **Шаг 8: Коммит**

```bash
git commit -am "feat(pocket): свободные деньги как сумма ликвидных отслеживаемых счетов (ANO-9)"
```

### Task 2.3: Капитал через счета

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/service/CapitalService.java:245-260`
- Изменить: `backend/src/test/java/ru/selfin/backend/service/CapitalServiceLiquidTest.java`

- [ ] **Шаг 1: Написать падающие тесты**

1. `liquidAt` включает вклады.
2. `liquidAt` не задваивает копилку, у которой задан `accountId`.
3. Обязательства включают долг по кредитным счетам, посчитанный от лимита.

- [ ] **Шаг 2: Переписать `liquidAt`**

```
ликвид(t) = Σ balanceAt(a, t) по счетам с trackBalance и kind ∈ {DEBIT, CASH, DEPOSIT}
          + Σ балансов копилок БЕЗ accountId
```

Копилки с `accountId` не прибавляются: их деньги уже внутри остатка счёта (спека §4.4).

- [ ] **Шаг 3: Добавить долг кредиток в обязательства**

В `summary()` и `capitalAt()`: к сумме `CapitalItem(LIABILITY)` прибавить `accountBalanceService.creditDebtAt(t)`.

- [ ] **Шаг 4: Прогнать тесты капитала, затем всё**

- [ ] **Шаг 5: Коммит**

```bash
git commit -am "feat(capital): ликвид и обязательства считаются по счетам (ANO-9)"
```

### Task 2.4: Дрейф чекпоинтов внутри счёта

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/service/BalanceCheckpointService.java:40-68`
- Изменить: `backend/src/test/java/ru/selfin/backend/service/BalanceCheckpointServiceTest.java`

- [ ] **Шаг 1: Написать падающий тест**

Два счёта, у каждого по два чекпоинта. Дрейф второго чекпоинта счёта A обязан считаться от первого чекпоинта счёта A, а не от чекпоинта счёта B.

- [ ] **Шаг 2: Разбить цепочку по счетам**

`findAll()` группирует чекпоинты по `account.id` и считает дрейф внутри группы. Дрейф считается только для дефолтного счёта: у прочих счетов между якорями по определению нет движения (§6), дрейф там всегда равен нулю и смысла не несёт — для них возвращать `null`, как для самого раннего.

- [ ] **Шаг 3: Прогнать тесты, коммит**

```bash
git commit -am "fix(checkpoints): дрейф считается внутри счёта (ANO-9)"
```

---

## Chunk 3: API и экран счетов

### Task 3.1: DTO и сервис счетов

**Файлы:**
- Создать: `backend/src/main/java/ru/selfin/backend/dto/account/AccountDto.java`
- Создать: `backend/src/main/java/ru/selfin/backend/dto/account/AccountCreateDto.java`
- Создать: `backend/src/main/java/ru/selfin/backend/service/AccountService.java`
- Создать: `backend/src/test/java/ru/selfin/backend/service/AccountServiceTest.java`

`AccountDto` отдаёт наружу: `id`, `name`, `kind`, `trackBalance`, `purposeCategoryId`, `purposeCategoryName`, `creditLimit`, `availableFloor`, `isDefault`, `balance` (или `null`, если не отслеживается), `balanceDate`, `debt` (для кредита), `allocatedThisMonth` (для конверта без слежения, §5.3), `floorSuggestion` (§4.2).

- [ ] **Шаг 1: Написать падающие тесты на инварианты §8**

По одному тесту на строку таблицы краевых случаев спеки:

| Тест | Ожидание |
|---|---|
| удаление дефолтного счёта | `ResponseStatusException` 409 |
| удаление обычного счёта | soft delete, чекпоинты остаются |
| планка выше лимита | 400 |
| выключить слежение у вклада | 400 |
| назначить дефолтным конверт без слежения | 400 |
| назначить дефолтным кредитку | 400 |
| смена дефолтного | старый снимается, новый ставится, в одной транзакции |
| удаление категории-зоны | ссылка обнуляется, счёт живёт |

- [ ] **Шаг 2: Реализовать сервис**

Валидацию писать в сервисе, а не полагаться на CHECK базы: пользователю нужен внятный 400, а не 500 от нарушения ограничения. Ограничения базы остаются вторым рубежом.

Смену дефолтного делать в одной транзакции: сначала снять флаг со старого, затем поставить новому — иначе частичный unique-индекс отвергнет промежуточное состояние.

- [ ] **Шаг 3: Реализовать `allocatedThisMonth`**

Сумма фактов по категории-зоне ответственности с первого числа текущего месяца. Считается только для счетов без слежения; у остальных `null`.

- [ ] **Шаг 4: Прогнать тесты, коммит**

### Task 3.2: Контроллер

**Файлы:**
- Создать: `backend/src/main/java/ru/selfin/backend/controller/AccountController.java`
- Создать: `backend/src/test/java/ru/selfin/backend/AccountControllerIT.java`

Эндпоинты: `GET /api/v1/accounts`, `POST /api/v1/accounts`, `PUT /api/v1/accounts/{id}`, `DELETE /api/v1/accounts/{id}`, `PATCH /api/v1/accounts/{id}/default`.

- [ ] Написать IT по образцу `CapitalControllerIT`; реализовать; прогнать; коммит.

### Task 3.3: Чекпоинт получает адрес в API

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/dto/BalanceCheckpointCreateDto.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/service/BalanceCheckpointService.java:70-87`
- Изменить: `backend/src/test/java/ru/selfin/backend/dto/BalanceCheckpointCreateDtoTest.java`

`accountId` необязательный: если не передан — дефолтный счёт. Это сохраняет совместимость старого фронта и делает «ввёл остаток» днём 1 по-прежнему однокликовым.

- [ ] Тесты, реализация, коммит.

### Task 3.4: Копилка на счёте

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`

- [ ] **Шаг 1: Тест** — у копилки с `accountId` баланс равен остатку счёта, а не сохранённому `currentBalance`.
- [ ] **Шаг 2: Тест** — перевод в такую копилку запрещён (400): деньги двигаются на самом счёте, а не переводом.
- [ ] **Шаг 3: Реализация, прогон, коммит.**

### Task 3.5: Фронт — типы, API, экран

**Файлы:**
- Изменить: `frontend/src/types/api.ts`
- Изменить: `frontend/src/api/index.ts`
- Создать: `frontend/src/components/accounts/AccountsSection.tsx`
- Создать: `frontend/src/components/accounts/AccountCard.tsx`
- Создать: `frontend/src/components/accounts/AccountForm.tsx`
- Изменить: `frontend/src/pages/Settings.tsx`
- Изменить: `frontend/src/components/pocket/ReanchorSheet.tsx`

Карточка счёта показывает, по спеке §5.3:

- слежу за остатком — остаток и дата последнего якоря;
- не слежу — «Выделено за месяц: N» с подписью «по категории такой-то»; **не «Осталось»** — остаток мы обещать не можем;
- кредит — доступно из лимита, планка, рассчитанный долг.

Форма создания: имя и природа, остальное по умолчанию. Раскрытие дополнительных полей — по кнопке, не сразу.

`ReanchorSheet` получает выбор счёта. Если счёт один — селектор не показывается вовсе, поведение как сейчас.

Рабочее слово на экране — «Счета». Название ещё обсуждается (§10 спеки), поэтому все видимые строки собрать в одном месте компонента, чтобы смена слова была правкой одного файла.

- [ ] **Шаги:** типы → api → компоненты → секция в Settings → `npm run build` → проверить в браузере через preview → коммит.

---

## Chunk 4: Кредитная планка, второе и третье числа

### Task 4.1: Числа в ответе кармашка

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/dto/pocket/BreakdownType.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java`
- Создать: `backend/src/test/java/ru/selfin/backend/service/PocketEngineCreditFloorTest.java`

- [ ] **Шаг 1: Тесты**

1. Планка задана, доступно ниже → `pocketAfterCreditRestore = pocket − резерв`, в breakdown появляется `CREDIT_RESTORE`.
2. Планки нет → второе число `null`, строки нет.
3. Доступно выше планки → резерв ноль, второе число `null`.
4. Есть вклад → `pocketWithDeposits = pocket + полуликвид`; нет вклада → `null`.
5. **Инвариант breakdown не изменился:** `STARTING − OVERDUE − EXPENSES − CONTRIB + INCOME − FORECAST = MIN`, `MIN − BUFFER = POCKET`. `CREDIT_RESTORE` информационная и в инвариант не входит — ровно как `WISHLIST_INFO`.

- [ ] **Шаг 2: Расширить DTO**

```java
        /** «Свободно, если вернуть карты к планке» (§4.2). null — планок нет или резерв нулевой. */
        BigDecimal pocketAfterCreditRestore,
        /** «Свободно, если распечатать вклад» (§4.3). null — вкладов нет. Показывать мелким. */
        BigDecimal pocketWithDeposits
```

- [ ] **Шаг 3: Реализация в движке**

```java
        BigDecimal creditReserve = in.creditRestoreReserveOrZero();
        BigDecimal pocketAfterRestore = creditReserve.signum() > 0
                ? pocket.subtract(creditReserve) : null;
        BigDecimal semiLiquid = in.semiLiquidBalanceOrZero();
        BigDecimal pocketWithDeposits = semiLiquid.signum() > 0 ? pocket.add(semiLiquid) : null;
```

Строку `CREDIT_RESTORE` добавлять **после** `POCKET`, рядом с `WISHLIST_INFO`, чтобы порядок отражал «это сноска, а не часть вычитания».

- [ ] **Шаг 4: Прогон, коммит.**

### Task 4.2: Подсказка поднять планку

- [ ] Тест: доступно выше планки → `floorSuggestion` равен доступному; иначе `null`.
- [ ] Реализация в `AccountService`, отдаётся в `AccountDto`.
- [ ] Фронт: на карточке кредитного счёта кнопка «Поднять планку до N».
- [ ] Коммит.

### Task 4.3: Перенос кредиток из Капитала

**Файлы:**
- Изменить: `frontend/src/components/accounts/AccountForm.tsx`

Автоматической миграции нет сознательно (спека §7.2): из данных нельзя понять, записан долг или доступный остаток, а ошибка сдвинет капитал на десятки тысяч в любую сторону.

- [ ] **Шаг 1:** при создании счёта с природой «кредит» подтянуть `GET /capital/items?kind=LIABILITY` и показать подходящие по имени записи.
- [ ] **Шаг 2:** текст: «В Капитале есть запись “Кредитка ТБанк” на 98 000. Если это доступный остаток, а не долг, заархивируй её — счёт теперь считает обязательство сам». Кнопка архивирует, отказ ничего не делает.
- [ ] **Шаг 3:** прогон, проверка в браузере, коммит.

### Task 4.4: Фронт — три числа

**Файлы:**
- Экран кармашка (Dashboard и связанные компоненты)

Порядок: свободно крупно → после возврата карт обычным → с распечатанным вкладом мелким. Второе и третье не показывать, когда `null`.

- [ ] Реализация, `npm run build`, визуальная проверка, коммит.

---

## Проверка перед мерджем

- [ ] `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify` — всё зелёное, включая IT.
- [ ] `PocketMigrationRegressionTest` зелёный и **ни разу не правился** за все четыре чанка.
- [ ] `npm run build` во фронте без ошибок типов.
- [ ] Приложение поднято, глазами проверено: кармашек не изменился по сравнению с прежним числом; создан конверт без слежения — свободные деньги не сдвинулись; создан кредитный счёт с планкой — появилось второе число.
- [ ] Обновить `MEMORY.md`: сущность счетов появилась, `BalanceCheckpoint` больше не одинокий якорь.

## Что осознанно НЕ делается

Перевод между счетами как операция; `account_id` на транзакции; долг корзине (ANO-44); раскладка процентом; импорт (ANO-10); инъекция `Clock` (ANO-39); ревизия план/факт (ANO-42). Обоснования — §6 спеки.
