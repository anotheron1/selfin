# Денежный поток копилки — план реализации (ANO-86, ANO-87)

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛ: `superpowers:subagent-driven-development` либо `superpowers:executing-plans`. Шаги отмечаются чекбоксами.

**Цель:** деньги в копилке начинают ходить в обе стороны, а удаление копилки перестаёт их уничтожать и раздувать капитал.

**Архитектура:** перевод становится знаковым — одно действие «переместить», направление задаёт знак. Удаление при ненулевом балансе требует явного выбора: вернуть деньги или признать потраченными на цель. Ликвид капитала перестаёт учитывать движения удалённых копилок, чем восстанавливается закон сохранения, записанный в спеке капитала.

**Стек:** Java 21, Spring Boot 4.0.3, PostgreSQL, Flyway, Lombok, JUnit 5, Mockito, Testcontainers.

**Спека:** `docs/superpowers/specs/2026-09-12-fund-money-flow-design.md`. Ссылки вида «спека §4.3» ведут туда.

## Глобальные ограничения

- Правок схемы **нет**. Ни одной новой миграции этот план не создаёт.
- Идемпотентность перевода сохраняется: `Idempotency-Key` остаётся обязательным заголовком, повтор с тем же ключом возвращает закэшированный результат.
- `TargetFundService.delete` — **единственная** точка удаления копилки. Не вызывать её из `WishlistArtifactService`: там сознательно стоит отказ (ANO-103), и он остаётся.
- Копилка со счётом (`accountId != null`) поведения не меняет: переводы туда по-прежнему отвергаются `rejectTransferToAccountBackedFund`, баланс читается со счёта, удаление не спрашивает про деньги (спека §4.6).
- Каждый новый тест проверяется мутацией: правка в N местах требует N тестов, каждый со своей мутацией.

## Запуск тестов

Из каталога `backend`:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
```

Юниты плюс интеграционные (нужен Docker):

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Один IT-класс:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify -Dit.test=FundMoneyFlowIT -DfailIfNoSpecifiedTests=false -Dtest=ZzzNoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
```

**Про запуск одного IT.** Если прогонять `verify` с падающим юнит-тестом, Maven до фазы интеграционных **не дойдёт**, а отчёт в `target/failsafe-reports` останется от прошлого прогона и будет выглядеть зелёным. На этом уже один раз чуть не построили ложный вывод. При проверке мутаций сверяйте время файла отчёта или гоните IT отдельно командой выше.

## Структура файлов

| файл | ответственность |
|---|---|
| `controller/TargetFundController.java` | контракт ручек: знаковая сумма, флаг подтверждения, выбор судьбы денег при удалении |
| `service/TargetFundService.java` | правила перевода и удаления; единственное место, где живёт решение «что с деньгами» |
| `repository/FundTransactionRepository.java` | запрос ликвида перестаёт видеть удалённые копилки |
| `model/enums/FundMoneyDisposal.java` | новое перечисление: `RETURN` \| `SPENT` |
| `test/.../FundMoneyFlowIT.java` | закон сохранения на Testcontainers |
| `test/.../FundSignedAmountIT.java` | страховка знака: аналитика и дашборд не ломаются отрицательной суммой |

---

### Задача 1: Страховка знака — зафиксировать поведение сумм ДО правки

Спека §5 объявляет это предусловием. Отрицательная сумма пойдёт в два места, которые суммируют факты без фильтра по типу; их поведение нужно зафиксировать тестом **до** того, как знак появится, иначе потом не отличить «так и было» от «мы сломали».

Разбор уже сделан, повторять не нужно:

| место | фильтр | вывод |
|---|---|---|
| `AccountService.allocatedThisMonth:269-277` | `type == EXPENSE` | `FUND_TRANSFER` не доходит, безопасно |
| `BaselineTimelineBuilder.sumByType:183-188` | партиция по типу | даст нетто по `FUND_TRANSFER`, это верно |
| `AnalyticsService:486,500` | строка «Переводы в копилки» | даст нетто вместо брутто — **зафиксировать** |
| `DashboardService:84` | группировка по категории | даст нетто по категории перевода — **зафиксировать** |
| `FundTransactionRepository:31` | `SUM(t.amount)` | знак поддерживает сам |

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/FundSignedAmountIT.java`

**Interfaces:**
- Consumes: ничего
- Produces: ничего; задача только фиксирует поведение

- [ ] **Шаг 1: Написать падающий тест**

Тест вставляет пару переводов — в копилку и обратно — напрямую в базу и проверяет, что суммирующие места дают нетто, а не брутто. На этом шаге он падает, потому что отрицательные суммы ещё некому создать: вставляем их SQL-ом руками.

```java
package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.CapitalService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-86/87, страховка знака (спека §5). Обратный перевод делает factAmount и
 * FundTransaction.amount отрицательными. Этот класс фиксирует, что суммирующие места
 * от этого не ломаются, а дают НЕТТО — и фиксирует ДО появления знака в коде, чтобы
 * потом нельзя было спутать «так и было» с «мы сломали».
 */
@SpringBootTest
@Testcontainers
class FundSignedAmountIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    @Test
    @DisplayName("SUM(t.amount) по движениям копилки даёт нетто при отрицательном движении")
    void fundTransactionSum_isNetWhenNegative() {
        String fundId = insertFund("Знаковая копилка");
        insertFundTransaction(fundId, new BigDecimal("20000"));
        insertFundTransaction(fundId, new BigDecimal("-5000"));

        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM fund_transactions"
                        + " WHERE fund_id = ?::uuid AND is_deleted = false",
                BigDecimal.class, fundId);

        assertThat(net)
                .as("движения складываются со знаком, отдельного поля направления нет")
                .isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    @DisplayName("ликвид капитала принимает отрицательное движение и даёт нетто")
    void capitalLiquid_acceptsNegativeTransaction() {
        BigDecimal before = capitalService.cashLiquidAt(LocalDate.now());

        String fundId = insertFund("Копилка для ликвида");
        insertFundTransaction(fundId, new BigDecimal("20000"));
        BigDecimal afterIn = capitalService.cashLiquidAt(LocalDate.now());

        insertFundTransaction(fundId, new BigDecimal("-20000"));
        BigDecimal afterOut = capitalService.cashLiquidAt(LocalDate.now());

        assertThat(afterIn.subtract(before))
                .as("движение в копилку поднимает ликвид").isEqualByComparingTo("20000");
        assertThat(afterOut)
                .as("обратное движение возвращает ликвид ровно назад")
                .isEqualByComparingTo(before);
    }

    private String insertFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at)
                VALUES (?::uuid, ?, 100000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }

    private void insertFundTransaction(String fundId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, amount, transaction_date, idempotency_key, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, CURRENT_DATE, ?::uuid, false, now())
                """, fundId, amount, UUID.randomUUID().toString());
    }
}
```

- [ ] **Шаг 2: Прогнать и убедиться, что тест ЗЕЛЁНЫЙ**

Команда из раздела «Запуск тестов», с `-Dit.test=FundSignedAmountIT`.

Ожидается: **PASS**. Это тест-характеризация: он не должен падать, он фиксирует, что база и запрос ликвида знак уже поддерживают. Если он падает — знаковое представление не годится, и надо остановиться и пересмотреть спеку §5, а не чинить тест.

- [ ] **Шаг 3: Коммит**

```bash
git add backend/src/test/java/ru/selfin/backend/FundSignedAmountIT.java
git commit -m "test(ano-86): зафиксировать, что суммы движений копилки принимают знак"
```

---

### Задача 2: Снятие из копилки

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/TargetFundController.java:93-99`
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` (метод `doTransfer`)
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: ничего
- Produces: `POST /funds/{id}/transfer` принимает отрицательный `amount`; `TargetFundService.transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount, boolean confirm)` — новая сигнатура, `confirm` добавляется в задаче 3, здесь пока прежняя трёхаргументная

- [ ] **Шаг 1: Создать класс теста с оснасткой**

Все последующие задачи дописывают тесты в этот класс и пользуются его помощниками. Создать целиком:

```java
package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.CapitalService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-86 и ANO-87: деньги в копилке ходят в обе стороны, удаление их не уничтожает.
 *
 * <p>Проверяется ЗАКОН СОХРАНЕНИЯ, а не отдельные методы: спека капитала
 * ({@code 2026-05-10-capital-net-worth-design.md:66}) обещает, что FUND_TRANSFER и
 * FundTransaction взаимно компенсируются. Утверждение о системе ловит дефект независимо
 * от того, в скольких местах записано правило.
 *
 * <p>План: {@code docs/superpowers/plans/2026-09-12-fund-money-flow.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FundMoneyFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    /** Копилка без счёта — базовый случай: у неё собственный баланс. */
    private String createFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }

    /** Копилка поверх счёта — не базовый случай, своих денег не имеет (спека §4.6). */
    private String createFundOnAccount(String name, String accountId) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at, account_id)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now(), ?::uuid)
                """, id, name, accountId);
        return id;
    }

    private String firstTrackedAccountId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_deleted = false"
                        + " AND track_balance = true LIMIT 1", String.class);
    }

    /** @param confirm {@code null} — без подтверждения */
    private ResultActions transfer(String fundId, BigDecimal amount, Boolean confirm) {
        String body = confirm == null
                ? "{\"amount\": " + amount.toPlainString() + "}"
                : "{\"amount\": " + amount.toPlainString() + ", \"confirm\": " + confirm + "}";
        try {
            return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal fundBalance(String fundId) {
        return jdbc.queryForObject(
                "SELECT current_balance FROM target_funds WHERE id = ?::uuid",
                BigDecimal.class, fundId);
    }

    private boolean isDeleted(String fundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_deleted FROM target_funds WHERE id = ?::uuid", Boolean.class, fundId));
    }

    /** Помечает копилку удалённой в обход сервиса — проверяется именно запрос ликвида. */
    private void softDeleteFundDirectly(String fundId) {
        jdbc.update("UPDATE target_funds SET is_deleted = true WHERE id = ?::uuid", fundId);
    }
}
```

- [ ] **Шаг 2: Написать падающий тест**

Дописать в класс:

```java
    @Test
    @DisplayName("ANO-87: из копилки можно забрать деньги обратно")
    void withdraw_returnsMoneyToAccount() {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-5000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("ANO-87: снять больше накопленного нельзя — в копилке столько нет")
    void withdraw_moreThanBalance_isRefused() {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-20001"), null).andExpect(status().isConflict());

        assertThat(fundBalance(fundId))
                .as("отказ не должен списать ничего").isEqualByComparingTo("20000");
    }
```

- [ ] **Шаг 3: Прогнать и убедиться, что падает**

Ожидается: FAIL — валидация `@Positive` отвергает отрицательную сумму с кодом 400, а не 200.

- [ ] **Шаг 4: Снять `@Positive` и добавить правило снятия**

В `TargetFundController` заменить запись запроса:

```java
    /**
     * Тело запроса на перемещение денег между счётом и копилкой.
     *
     * <p>Сумма ЗНАКОВАЯ (ANO-87, спека §4.1): положительная — отложить, отрицательная —
     * забрать обратно. Отдельной ручки для обратного перевода нет осознанно: это одно
     * действие «переместить», направление задаёт знак.
     *
     * @param amount сумма перемещения, не ноль
     */
    record TransferRequest(@NotNull BigDecimal amount) {
    }
```

Импорт `jakarta.validation.constraints.Positive` заменить на `jakarta.validation.constraints.NotNull`.

В `TargetFundService.doTransfer`, сразу после `rejectTransferToAccountBackedFund(fund)`:

```java
        if (amount.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer amount must not be zero");
        }
        // Снять можно только то, что накоплено. Это не запрет, а арифметика: в копилке
        // столько физически нет (спека §4.1).
        if (amount.signum() < 0 && amount.negate().compareTo(fund.getCurrentBalance()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fund holds only " + fund.getCurrentBalance());
        }
```

Ниже по методу заменить установку статуса — при снятии копилка может перестать быть достигнутой:

```java
        fund.setStatus(fund.getTargetAmount() != null
                && newBalance.compareTo(fund.getTargetAmount()) >= 0
                ? FundStatus.REACHED : FundStatus.FUNDING);
```

Здесь же `doTransfer` получает четвёртый параметр — он понадобится в задачах 3 и 5. Сейчас просто пробрасывается и не используется:

```java
    private TargetFundDto doTransfer(UUID fundId, UUID idempotencyKey,
                                     BigDecimal amount, boolean confirm) {
```

Вызов из `transferToPocket` обновить соответственно.

- [ ] **Шаг 5: Прогнать — тесты проходят**

- [ ] **Шаг 6: Проверить мутацией**

Убрать проверку `amount.signum() < 0 && ...` → `withdraw_moreThanBalance_isRefused` обязан покраснеть. Вернуть.

- [ ] **Шаг 7: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/TargetFundController.java backend/src/main/java/ru/selfin/backend/service/TargetFundService.java backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java
git commit -m "feat(ano-87): перевод в копилку стал знаковым — деньги можно забрать обратно"
```

---

### Задача 3: Подтверждение при переводе сверх остатка

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/TargetFundController.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: знаковый `amount` из задачи 2
- Produces: `TargetFundService.transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount, boolean confirm)`

- [ ] **Шаг 1: Написать падающий тест**

```java
    @Test
    @DisplayName("ANO-87: перевод больше остатка требует подтверждения")
    void transfer_overBalance_needsConfirmation() {
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("99999999"), null).andExpect(status().isConflict());
        assertThat(fundBalance(fundId))
                .as("без подтверждения не должно пройти ничего").isEqualByComparingTo("0");

        transfer(fundId, new BigDecimal("99999999"), true).andExpect(status().isOk());
        assertThat(fundBalance(fundId)).isEqualByComparingTo("99999999");
    }
```

- [ ] **Шаг 2: Прогнать и убедиться, что падает**

Ожидается: FAIL — сейчас первый перевод возвращает 200, проверки достаточности нет вовсе.

- [ ] **Шаг 3: Добавить флаг и проверку**

`TransferRequest` получает второе поле:

```java
    record TransferRequest(@NotNull BigDecimal amount, Boolean confirm) {
        /** {@code null} трактуется как «не подтверждено». */
        boolean confirmed() {
            return Boolean.TRUE.equals(confirm);
        }
    }
```

Контроллер передаёт флаг: `fundService.transferToPocket(id, idempotencyKey, request.amount(), request.confirmed())`.

В `TargetFundService` обе сигнатуры — старую сохранить как делегат, чтобы существующие вызовы не сломались:

```java
    @Transactional
    public TargetFundDto transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount) {
        return transferToPocket(fundId, idempotencyKey, amount, false);
    }
```

В `doTransfer`, после проверки снятия:

```java
        // Перевести больше, чем показывает остаток, можно — но только осознанно.
        // Остаток в продукте не банковская истина, а якорь плюс введённое: он отстаёт от
        // реальности, и жёсткий отказ наказывал бы за неточный ввод (правило 5, спека §4.2).
        if (amount.signum() > 0 && !confirm) {
            BigDecimal free = accountBalanceService.freeMoneyAt(LocalDate.now());
            if (amount.compareTo(free) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Account holds " + free + ", transferring " + amount
                                + "; resend with confirm=true to proceed");
            }
        }
```

- [ ] **Шаг 4: Прогнать — тесты проходят**

- [ ] **Шаг 5: Проверить мутацией**

Заменить `!confirm` на `false` → `transfer_overBalance_needsConfirmation` обязан покраснеть на первой строке. Вернуть.

- [ ] **Шаг 6: Коммит**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(ano-87): перевод сверх остатка требует подтверждения вместо молчаливых 200"
```

---

### Задача 4: Ликвид перестаёт считать удалённые копилки

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FundTransactionRepository.java:30-36`
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: ничего
- Produces: изменённая семантика `sumEnvelopeFundsByTransactionDateLessThanEqual`

- [ ] **Шаг 1: Написать падающий тест**

```java
    @Test
    @DisplayName("ANO-86: движения удалённой копилки уходят из ликвида капитала")
    void deletedFund_dropsOutOfLiquid() {
        String fundId = createFund("Ипотека");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());
        softDeleteFundDirectly(fundId);
        BigDecimal liquidAfter = capitalService.cashLiquidAt(LocalDate.now());

        assertThat(liquidBefore.subtract(liquidAfter))
                .as("удалённая копилка не имеет права продолжать раздувать капитал")
                .isEqualByComparingTo("20000");
    }
```

`softDeleteFundDirectly` уже есть в каркасе из задачи 2 — удаление здесь идёт в обход сервиса намеренно: проверяется именно запрос ликвида, а не логика удаления.

- [ ] **Шаг 2: Прогнать и убедиться, что падает**

Ожидается: FAIL, разница равна 0 вместо 20000 — сейчас запрос не смотрит на `t.fund.deleted`.

- [ ] **Шаг 3: Добавить фильтр**

```java
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
            WHERE t.deleted = false
              AND t.fund.deleted = false
              AND t.transactionDate <= :date
              AND t.fund.accountId IS NULL
            """)
    BigDecimal sumEnvelopeFundsByTransactionDateLessThanEqual(@Param("date") LocalDate date);
```

К javadoc метода дописать:

```java
     * <p>ANO-86: фильтр {@code t.fund.deleted} обязателен. Без него удалённая копилка
     * продолжает давать деньги в ликвид: событие FUND_TRANSFER уже вычло их из остатка
     * счёта, движение осталось живым, и одна и та же сумма оказывалась одновременно
     * недоступной и посчитанной. Спека капитала обещает, что FUND_TRANSFER и
     * FundTransaction взаимно компенсируются — этот фильтр и есть условие обещания.
```

- [ ] **Шаг 4: Прогнать — тест проходит**

- [ ] **Шаг 5: Проверить мутацией**

Убрать строку `AND t.fund.deleted = false` → `deletedFund_dropsOutOfLiquid` обязан покраснеть. Вернуть.

- [ ] **Шаг 6: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/FundTransactionRepository.java backend/src/test/java
git commit -m "fix(ano-86): удалённая копилка перестаёт раздувать ликвид капитала"
```

---

### Задача 5: Удаление спрашивает, что с деньгами

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/enums/FundMoneyDisposal.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/TargetFundController.java:63-68`
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` (метод `delete`)
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: знаковый перевод из задачи 2, фильтр ликвида из задачи 4
- Produces: `TargetFundService.delete(UUID id, FundMoneyDisposal disposal)`; старая `delete(UUID id)` остаётся делегатом с `null`

- [ ] **Шаг 1: Написать падающие тесты**

```java
    @Test
    @DisplayName("ANO-86: удаление копилки с деньгами без ответа — 409 с суммой")
    void delete_withMoney_withoutChoice_isRefused() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}", fundId))
                .andExpect(status().isConflict());

        assertThat(isDeleted(fundId)).as("отказ не должен удалять").isFalse();
    }

    @Test
    @DisplayName("ANO-86: «вернуть» — деньги возвращаются, капитал не меняется")
    void delete_return_givesMoneyBack() throws Exception {
        String fundId = createFund("Отпуск");
        BigDecimal liquidStart = capitalService.cashLiquidAt(LocalDate.now());
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=RETURN", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("возврат своих же денег не меняет чистую стоимость")
                .isEqualByComparingTo(liquidStart);
        assertThat(isDeleted(fundId)).isTrue();
    }

    @Test
    @DisplayName("ANO-86: «потрачено» — капитал падает ровно на сумму")
    void delete_spent_dropsCapital() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());
        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        assertThat(liquidBefore.subtract(capitalService.cashLiquidAt(LocalDate.now())))
                .as("вещь куплена — этих денег в чистой стоимости больше нет")
                .isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("ANO-86: пустая копилка удаляется без выбора")
    void delete_emptyFund_needsNoChoice() throws Exception {
        String fundId = createFund("Пустая");

        mockMvc.perform(delete("/api/v1/funds/{id}", fundId))
                .andExpect(status().isNoContent());

        assertThat(isDeleted(fundId)).isTrue();
    }
```

`isDeleted` уже есть в каркасе из задачи 2.

- [ ] **Шаг 2: Прогнать и убедиться, что падают**

Ожидается: FAIL — сейчас `DELETE` всегда отвечает 204 и не спрашивает ничего.

- [ ] **Шаг 3: Завести перечисление**

`backend/src/main/java/ru/selfin/backend/model/enums/FundMoneyDisposal.java`:

```java
package ru.selfin.backend.model.enums;

/**
 * Что сделать с деньгами при удалении копилки (ANO-86, спека §4.3).
 *
 * <p>Копилка — условное место, где деньги лежат. Поэтому при её удалении деньги НЕ обязаны
 * возвращаться: они могли быть потрачены на саму цель. Но удаление может быть и вынужденным,
 * и тогда деньги должны вернуться. Выбрать за человека продукт не может — он спрашивает.
 */
public enum FundMoneyDisposal {
    /** Деньги возвращаются в свободные: обратный перевод на всю сумму, затем закрытие. */
    RETURN,
    /** Деньги потрачены на цель: перевод остаётся в журнале настоящей тратой. */
    SPENT
}
```

- [ ] **Шаг 4: Реализовать выбор**

Контроллер:

```java
    @Operation(summary = "Удалить целевой фонд",
            description = "Soft delete. Если на копилке есть деньги, обязателен параметр money: "
                    + "RETURN — вернуть в свободные деньги, SPENT — признать потраченными на цель.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "ID фонда") @PathVariable UUID id,
            @Parameter(description = "Судьба денег: RETURN | SPENT")
            @RequestParam(required = false) FundMoneyDisposal money) {
        fundService.delete(id, money);
    }
```

Сервис:

```java
    @Transactional
    public void delete(UUID id) {
        delete(id, null);
    }

    /**
     * Удаляет копилку, явно решая судьбу лежащих на ней денег (ANO-86, спека §4.3).
     *
     * <p>Молчаливое удаление копилки с деньгами было дефектом: сумма исчезала из свободных
     * денег и продолжала раздувать капитал. Теперь без ответа — отказ.
     *
     * @param disposal что сделать с деньгами; обязателен только при ненулевом балансе
     * @throws ResponseStatusException 409, если на копилке есть деньги, а выбор не сделан
     */
    @Transactional
    public void delete(UUID id, FundMoneyDisposal disposal) {
        TargetFund fund = fundRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", id));

        // У копилки со счётом собственных денег нет — её баланс это остаток счёта, и он
        // остаётся на месте. Спрашивать не о чем (спека §4.6).
        BigDecimal balance = fund.getAccountId() != null
                ? BigDecimal.ZERO
                : (fund.getCurrentBalance() != null ? fund.getCurrentBalance() : BigDecimal.ZERO);

        if (balance.signum() != 0) {
            if (disposal == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Fund holds " + balance + "; pass money=RETURN or money=SPENT");
            }
            if (disposal == FundMoneyDisposal.RETURN) {
                doTransfer(id, UUID.randomUUID(), balance.negate(), true);
            }
        }
        fund.setDeleted(true);
        fundRepository.save(fund);
    }
```

- [ ] **Шаг 5: Прогнать — тесты проходят**

- [ ] **Шаг 6: Проверить мутациями**

| мутация | обязан упасть |
|---|---|
| убрать `if (disposal == null) throw` | `delete_withMoney_withoutChoice_isRefused` |
| в ветке `RETURN` не звать `doTransfer` | `delete_return_givesMoneyBack` |
| считать баланс копилки со счётом как `currentBalance` | добавить тест задачи 7 |

Каждую применить, прогнать, вернуть.

- [ ] **Шаг 7: Коммит**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(ano-86): удаление копилки спрашивает, что сделать с деньгами"
```

---

### Задача 6: При «потрачено» строка журнала становится тратой

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` (метод `delete`)
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: `FundMoneyDisposal` из задачи 5
- Produces: ничего

- [ ] **Шаг 1: Написать падающий тест**

```java
    @Test
    @DisplayName("ANO-86: при «потрачено» журнал перестаёт звать трату переводом")
    void delete_spent_renamesJournalEntry() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        String description = jdbc.queryForObject(
                "SELECT description FROM financial_events"
                        + " WHERE target_fund_id = ?::uuid AND is_deleted = false LIMIT 1",
                String.class, fundId);

        assertThat(description)
                .as("место, куда переводили, больше не существует — это была трата")
                .isEqualTo("Отпуск");
    }
```

- [ ] **Шаг 2: Прогнать и убедиться, что падает**

Ожидается: FAIL — описание останется `«В копилку: Отпуск»`.

- [ ] **Шаг 3: Переименовать записи при `SPENT`**

В `delete`, в ветке выбора:

```java
            if (disposal == FundMoneyDisposal.RETURN) {
                doTransfer(id, UUID.randomUUID(), balance.negate(), true);
            } else {
                // Деньги потрачены на цель. Журнал обязан назвать это тратой, а не
                // перемещением в место, которого больше нет (правило 13, спека §4.5).
                // Меняется ТОЛЬКО описание: сумма, дата и тип — по-прежнему та же операция.
                eventRepository.findAllByTargetFundIdAndDeletedFalse(id)
                        .forEach(e -> e.setDescription(fund.getName()));
            }
```

Если метода репозитория нет, добавить в `FinancialEventRepository`:

```java
    /** Все живые события, привязанные к копилке (ANO-86: переименование при «потрачено»). */
    List<FinancialEvent> findAllByTargetFundIdAndDeletedFalse(UUID targetFundId);
```

- [ ] **Шаг 4: Прогнать — тест проходит**

- [ ] **Шаг 5: Проверить мутацией**

Убрать ветку переименования → `delete_spent_renamesJournalEntry` обязан покраснеть. Вернуть.

- [ ] **Шаг 6: Коммит**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(ano-86): при «потрачено на цель» журнал называет операцию тратой"
```

---

### Задача 7: Закон сохранения и копилка со счётом

Здесь собирается то, ради чего всё делалось: инвариант проверяется как утверждение о системе, а не о методе. Этот тест потом переиспользуется для ANO-125.

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: всё из задач 2–6
- Produces: ничего

- [ ] **Шаг 1: Написать тесты**

```java
    @Test
    @DisplayName("закон сохранения: перевод туда и обратно не меняет ликвид")
    void conservation_transferRoundTrip_keepsLiquid() {
        String fundId = createFund("Отпуск");
        BigDecimal start = capitalService.cashLiquidAt(LocalDate.now());

        transfer(fundId, new BigDecimal("15000"), null).andExpect(status().isOk());
        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("перемещение между своими деньгами не создаёт и не уничтожает их")
                .isEqualByComparingTo(start);

        transfer(fundId, new BigDecimal("-15000"), null).andExpect(status().isOk());
        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("и обратное перемещение тоже").isEqualByComparingTo(start);
    }

    @Test
    @DisplayName("копилка со счётом удаляется без выбора, остаток счёта не тронут")
    void accountBackedFund_deletesWithoutChoice() throws Exception {
        String accountId = firstTrackedAccountId();
        String fundId = createFundOnAccount("Цель на карте", accountId);
        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());

        mockMvc.perform(delete("/api/v1/funds/{id}", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("деньги лежат на счёте и никуда не делись — удалена только цель поверх них")
                .isEqualByComparingTo(liquidBefore);
    }
```

- [ ] **Шаг 2: Прогнать — тесты проходят**

Если `conservation_transferRoundTrip_keepsLiquid` падает уже на первом утверждении — значит задача 4 сделана неверно либо всплыл ANO-125. Проверить дату якоря: если якорь стоит сегодняшним числом, это **ANO-125**, а не регрессия этого плана. Остановиться и сообщить, не «чинить» тест.

- [ ] **Шаг 3: Коммит**

```bash
git add backend/src/test/java
git commit -m "test(ano-86,ano-87): закон сохранения денег при переводе и удалении копилки"
```

---

### Задача 8: Проверка на стенде и резерв взносов

**Files:** правок кода нет, если §8 спеки не вскроет дефект.

- [ ] **Шаг 1: Полный прогон**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Ожидается: 343+ юнита и 125+ интеграционных, ноль падений.

- [ ] **Шаг 2: Пересобрать стенд**

```bash
COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build backend
```

Дождаться `200` на `http://localhost:8081/api/v1/pocket`. Собирается несколько минут — не проверять раньше времени, иначе ответит старый контейнер.

- [ ] **Шаг 3: Воспроизвести исходный сценарий ANO-86**

Перевести 20 000 в копилку без счёта, запомнить кармашек, остаток и ликвид, удалить копилку обоими способами (на разных копилках) и сверить с таблицей спеки §4.3.

- [ ] **Шаг 4: Проверить открытый вопрос спеки §8 — резерв взносов**

`PocketInputAssembler:167-190` резервирует взносы в копилки. После обратного перевода накопленное уменьшается, значит резерв будущих взносов должен вырасти. Проверить на стенде: записать `pocket` до и после снятия из копилки с заданным `targetAmount`.

Если резерв ведёт себя неверно — **завести отдельную задачу, не чинить здесь**. Этот план её не покрывает, и молча расширять его нельзя.

- [ ] **Шаг 5: Прогнать скрипты**

```bash
node tools/ano50-invariants.mjs
node tools/ano50-stage67.mjs
```

Оба обязаны быть зелёными. `stage67` — этап копилок и капитала.

**Внимание на ловушку `stage34`/`stage67`:** ожидания этих скриптов зависят от того, куда попадает следующий доход стенда относительно горизонта кармашка. Расхождение вида «сдвиг 0» может означать не дефект, а то, что событие легло за горизонт. Прежде чем заводить баг — проверить горизонт в ответе `/pocket`.

- [ ] **Шаг 6: Восстановить стенд**

```bash
bash tools/ano50-reset-stand.sh C:/Users/Kirill/selfin-backups/ano50-fixsession-start-2026-09-12.sql
```

Сверить: кармашек 26 000, остаток 60 000, капитал 2 668 277, ликвид 210 000, якорь 2026-08-29.

- [ ] **Шаг 7: Отметиться в Linear**

Комментарий в ANO-86 и ANO-87 с замерами до и после, списком мутаций и результатом прогона скриптов. Перевести обе в Done.
