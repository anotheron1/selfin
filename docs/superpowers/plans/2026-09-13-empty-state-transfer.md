# Копилка на пустом состоянии: мнение только при основаниях — план реализации (ANO-157)

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛ: `superpowers:subagent-driven-development` либо `superpowers:executing-plans`. Шаги отмечаются чекбоксами.

**Цель:** человек, ничего не вводивший, может отложить деньги в копилку; человек, у которого основания есть, впервые получает обещанное ANO-87 подтверждение вместо стены.

**Архитектура:** правило «знаем ли мы остаток» переезжает в `AccountBalanceService` — туда, где живут `freeMoneyAt` и `noAnchorFallbackAt`. `TargetFundService` только спрашивает. Подтверждаемый отказ получает свой тип исключения и машиночитаемый код в `ErrorResponse.details`. Клиент начинает бросать типизированную `ApiError` со статусом и деталями, фронт перестаёт решать сам.

**Стек:** Java 21, Spring Boot 4.0.3, PostgreSQL, JUnit 5, Mockito, Testcontainers; React + TypeScript, vitest.

**Спека:** `docs/superpowers/specs/2026-09-13-empty-state-transfer-design.md`. Ссылки «спека §…» ведут туда.

## Глобальные ограничения

- **Правило достаточности при наличии оснований не меняется.** Решение ANO-87 остаётся: сверх остатка — предупреждение с подтверждением. Меняется только случай «оснований нет».
- **Различение структурное, а не числовое.** Проверять `free == 0` запрещено: это склеивает ноль-знание и ноль-незнание обратно. Основание есть, если существует якорь с датой `≤ t` на счёте с `countsAsFreeMoney` **или** хотя бы один факт с датой `≤ t` (`factAmount != null`, `wishlistStatus == null`, не удалён).
- **Отказ на снятие сверх накопленного остаётся безусловным** и стоит раньше проверки достаточности. Он про арифметику копилки, к основаниям отношения не имеет, подтверждению не подлежит.
- **Статус подтверждаемого отказа остаётся 409.** Контракт ANO-87 и его тесты не ломаются.
- **Текст на экране по правилам продукта** №8 (предложениями), №12 (без упрёка), №13 (словами пользователя). Технический текст 409 не меняется — он адресован клиенту.
- **Компонентных тестов в проекте нет** (`environment: 'node'`, ни `jsdom`, ни `@testing-library`). Проверяемая логика фронта выносится в `src/lib/` чистой функцией; заводить инфраструктуру компонентных тестов в этой задаче нельзя.
- Каждый новый тест проверяется мутацией.

## Запуск тестов

Бэкенд, из каталога `backend`:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Один IT-класс отдельно:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify -Dit.test=EmptyStateTransferIT -DfailIfNoSpecifiedTests=false -Dtest=ZzzNoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
```

Фронт, из каталога `frontend`: `npm test` и `npx tsc --noEmit`.

**Ловушка отчётов.** Падающий юнит-тест не даёт Maven дойти до фазы интеграционных, а отчёт в `target/failsafe-reports` остаётся от прошлого прогона и выглядит зелёным. При проверке мутаций сверяйте время файла отчёта либо гоните IT отдельно.

## Структура файлов

| файл | ответственность |
|---|---|
| `repository/FinancialEventRepository.java` | **изменяется.** Запрос «есть ли хоть один факт на дату» |
| `service/AccountBalanceService.java` | **изменяется.** `knowsFreeMoneyAt` — единственное место правила «знаем ли мы остаток» |
| `service/TargetFundService.java` | **изменяется.** Спрашивает про основания, бросает типизированное исключение |
| `exception/ConfirmationRequiredException.java` | **новый.** Подтверждаемый отказ как тип, с константой кода |
| `config/GlobalExceptionHandler.java` | **изменяется.** Обработчик, кладущий код в `details` |
| `frontend/src/api/client.ts` | **изменяется.** `ApiError` со `status`, `message`, `details` |
| `frontend/src/api/index.ts` | **изменяется.** `transferToFund` получает `confirm` |
| `frontend/src/lib/transferConfirm.ts` | **новый.** Чистое решение «подтверждаемый ли это отказ» |
| `frontend/src/pages/Funds.tsx` | **изменяется.** Перестаёт решать сам, показывает диалог |
| `test/.../EmptyStateTransferIT.java` | **новый.** Пустое состояние на живой базе |

---

### Задача 1: Продукт знает, есть ли у него основания

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/AccountBalanceService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/AccountBalanceServiceTest.java`

**Interfaces:**
- Consumes: ничего
- Produces: `AccountBalanceService.knowsFreeMoneyAt(LocalDate t)` → `boolean`

- [x] **Шаг 1: Написать падающие тесты**

Дописать в `AccountBalanceServiceTest` новой секцией:

```java
    // ── 6. основания для мнения об остатке (ANO-157) ────────────────────────

    @Test
    @DisplayName("ANO-157: ни якоря, ни фактов — оснований нет")
    void knowsFreeMoneyAt_nothingEntered_isFalse() {
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of());
        when(eventRepository
                .existsByDeletedFalseAndFactAmountNotNullAndWishlistStatusIsNullAndDateLessThanEqual(any()))
                .thenReturn(false);

        assertThat(service.knowsFreeMoneyAt(LocalDate.of(2026, 3, 10)))
                .as("ноль здесь значит «мы не знаем», а не «у вас ничего нет»")
                .isFalse();
    }

    @Test
    @DisplayName("ANO-157: якорь на НОЛЬ — это знание, основание есть")
    void knowsFreeMoneyAt_zeroAnchor_isTrue() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        LocalDate t = LocalDate.of(2026, 3, 10);
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        when(checkpointRepository.findLatestForAccountAt(defaultAccount.getId(), t))
                .thenReturn(Optional.of(anchor(defaultAccount, t, 0)));

        assertThat(service.knowsFreeMoneyAt(t))
                .as("человек сам сказал, что на счёте ноль — это основание, а не пустота")
                .isTrue();
    }

    @Test
    @DisplayName("ANO-157: якоря нет, но факты есть — основание есть (фолбэк ANO-28)")
    void knowsFreeMoneyAt_factsWithoutAnchor_isTrue() {
        LocalDate t = LocalDate.of(2026, 3, 10);
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of());
        when(eventRepository
                .existsByDeletedFalseAndFactAmountNotNullAndWishlistStatusIsNullAndDateLessThanEqual(t))
                .thenReturn(true);

        assertThat(service.knowsFreeMoneyAt(t))
                .as("noAnchorFallbackAt существует ровно для этого случая")
                .isTrue();
    }

    @Test
    @DisplayName("ANO-157: счёт без свободных денег основанием не считается")
    void knowsFreeMoneyAt_nonFreeMoneyAccount_isFalse() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        LocalDate t = LocalDate.of(2026, 3, 10);
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(deposit));
        when(eventRepository
                .existsByDeletedFalseAndFactAmountNotNullAndWishlistStatusIsNullAndDateLessThanEqual(t))
                .thenReturn(false);

        assertThat(service.knowsFreeMoneyAt(t))
                .as("вклад не даёт свободных денег — по нему о них судить нельзя")
                .isFalse();
        verifyNoInteractions(checkpointRepository);
    }
```

- [x] **Шаг 2: Прогнать и убедиться, что не компилируется**

Ожидается: нет метода `knowsFreeMoneyAt` и нет метода репозитория.

- [x] **Шаг 3: Добавить запрос в репозиторий**

Рядом с существующими запросами Pocket в `FinancialEventRepository`:

```java
    /**
     * Есть ли хотя бы один факт с датой не позже {@code date} (ANO-157).
     *
     * <p>Предикат зеркалит отбор {@code AccountBalanceService.factsDelta}: факт — это
     * не удалённое событие с заполненным {@code factAmount} и без {@code wishlistStatus}.
     * Основанием судить о деньгах может быть только то, что деньгами и считается.
     *
     * <p>Существование, а не сумма: факты на +100 и −100 дают ноль, но основание дают.
     */
    boolean existsByDeletedFalseAndFactAmountNotNullAndWishlistStatusIsNullAndDateLessThanEqual(
            LocalDate date);
```

- [x] **Шаг 4: Написать правило**

В `AccountBalanceService`, рядом с `noAnchorFallbackAt`:

```java
    /**
     * Есть ли у продукта ОСНОВАНИЯ судить о свободных деньгах на дату {@code t} (ANO-157).
     *
     * <p>{@code freeMoneyAt} и {@code noAnchorFallbackAt} возвращают ноль в двух разных
     * случаях, и склеивать их нельзя. Якорь, говорящий «на счёте ноль», — это ЗНАНИЕ, и
     * предупреждать по нему верно: человек сам ввёл это число. Отсутствие якоря и фактов —
     * НЕЗНАНИЕ, и мнения из него не бывает (правило продукта 5: ни один экран не сообщает
     * пользователю, что он завёл неправильно).
     *
     * <p>Поэтому различение структурное, а не числовое: проверка {@code free == 0} склеила бы
     * оба случая обратно.
     *
     * <p>Живёт здесь, а не у вызывающего: класс объявлен единственным местом правила «остаток
     * счёта на дату», и «знаем ли мы остаток» — часть того же правила. Второе место, знающее,
     * из чего складывается остаток, разъехалось бы с этим при первой правке.
     */
    public boolean knowsFreeMoneyAt(LocalDate t) {
        boolean anyAnchor = active().stream()
                .filter(Account::countsAsFreeMoney)
                .anyMatch(a -> anchorAt(a, t).isPresent());
        return anyAnchor || eventRepository
                .existsByDeletedFalseAndFactAmountNotNullAndWishlistStatusIsNullAndDateLessThanEqual(t);
    }
```

- [x] **Шаг 5: Прогнать — четыре теста проходят**

- [x] **Шаг 6: Проверить мутациями**

| мутация | обязан упасть |
|---|---|
| `return true;` в начале метода | `knowsFreeMoneyAt_nothingEntered_isFalse` |
| убрать `|| eventRepository.exists…` | `knowsFreeMoneyAt_factsWithoutAnchor_isTrue` |
| убрать `.filter(Account::countsAsFreeMoney)` | `knowsFreeMoneyAt_nonFreeMoneyAccount_isFalse` |

Каждую применить, прогнать, вернуть.

- [x] **Шаг 7: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java \
        backend/src/main/java/ru/selfin/backend/service/AccountBalanceService.java \
        backend/src/test/java/ru/selfin/backend/service/AccountBalanceServiceTest.java
git commit -m "feat(ano-157): продукт различает ноль-знание и ноль-незнание"
```

---

### Задача 2: Подтверждаемый отказ — отдельный тип, с машиночитаемым кодом

Делается до задачи 3, потому что задача 3 бросает именно это исключение.

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/exception/ConfirmationRequiredException.java`
- Modify: `backend/src/main/java/ru/selfin/backend/config/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/ru/selfin/backend/config/GlobalExceptionHandlerIntegrityTest.java`

**Interfaces:**
- Consumes: ничего
- Produces: `ConfirmationRequiredException(String message)`; константа `ConfirmationRequiredException.CODE` = `"CONFIRM_REQUIRED"`; ответ 409 с `details = ["CONFIRM_REQUIRED"]`

- [x] **Шаг 1: Написать падающий тест**

Дописать в `GlobalExceptionHandlerIntegrityTest`:

```java
    @Test
    @DisplayName("ANO-157: подтверждаемый отказ — 409 с машиночитаемым кодом в details")
    void confirmationRequired_carriesMachineReadableCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response = handler.handleConfirmationRequired(
                new ConfirmationRequiredException("Account holds 0, transferring 5000"));

        assertThat(response.getStatusCode().value())
                .as("статус остаётся 409 — контракт ANO-87 не ломается")
                .isEqualTo(409);
        assertThat(response.getBody().details())
                .as("фронт обязан отличать подтверждаемый отказ от безусловного НЕ по тексту")
                .containsExactly(ConfirmationRequiredException.CODE);
        assertThat(response.getBody().message())
                .isEqualTo("Account holds 0, transferring 5000");
    }
```

- [x] **Шаг 2: Прогнать и убедиться, что не компилируется**

- [x] **Шаг 3: Завести исключение**

```java
package ru.selfin.backend.exception;

/**
 * Отказ, который человек может отменить осознанным подтверждением (ANO-87 §4.2, ANO-157).
 *
 * <p>Отличается от безусловного отказа на снятие сверх накопленного: там денег физически
 * нет, и подтверждать нечего. Оба отвечают 409, и различить их по статусу нельзя — поэтому
 * подтверждаемый несёт машиночитаемый код в {@code ErrorResponse.details}.
 *
 * <p>Матчинг по тексту сообщения был бы негласным контрактом: правка формулировки молча
 * сломала бы диалог подтверждения на фронте.
 */
public class ConfirmationRequiredException extends RuntimeException {

    /** Код в {@code ErrorResponse.details}. Дублируется на фронте в {@code lib/transferConfirm.ts}. */
    public static final String CODE = "CONFIRM_REQUIRED";

    public ConfirmationRequiredException(String message) {
        super(message);
    }
}
```

- [x] **Шаг 4: Добавить обработчик**

В `GlobalExceptionHandler`, ВЫШЕ обработчика `ResponseStatusException` (более специфичный матч):

```java
    /**
     * Подтверждаемый отказ (ANO-157). Статус тот же 409, что у безусловного, но в
     * {@code details} лежит код — по нему фронт решает, предлагать ли подтверждение.
     * Обработчик {@code ResponseStatusException} кладёт {@code details} пустым, поэтому
     * нужен отдельный тип, а не reason с префиксом.
     */
    @ExceptionHandler(ru.selfin.backend.exception.ConfirmationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleConfirmationRequired(
            ru.selfin.backend.exception.ConfirmationRequiredException ex) {
        log.warn("confirmation required: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                HttpStatus.CONFLICT.value(), ex.getMessage(),
                List.of(ru.selfin.backend.exception.ConfirmationRequiredException.CODE)));
    }
```

- [x] **Шаг 5: Прогнать — тест проходит**

- [x] **Шаг 6: Проверить мутацией**

Вернуть `List.of()` вместо кода → тест краснеет. Вернуть обратно.

- [x] **Шаг 7: Коммит**

---

### Задача 3: Перевод молчит, когда оснований нет

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` (метод `doTransfer`, блок проверки достаточности)
- Test: `backend/src/test/java/ru/selfin/backend/service/TargetFundAccountTest.java`

**Interfaces:**
- Consumes: `AccountBalanceService.knowsFreeMoneyAt`, `ConfirmationRequiredException`
- Produces: изменённая семантика `POST /funds/{id}/transfer`

- [x] **Шаг 1: Снять костыль в существующем тесте**

`TargetFundAccountTest:156` передаёт `confirm=true` и признаёт это комментарием. Заменить вызов и комментарий:

```java
        // ANO-157: подтверждения тут больше не нужно, и это предмет проверки. У этого
        // AccountBalanceService нет ни счетов, ни фактов — оснований судить об остатке нет,
        // а значит нет и мнения. До ANO-157 перевод упирался в предупреждение из пустоты,
        // и тест обходил его через confirm=true.
        service.transferToPocket(f.getId(), key, new BigDecimal("5000"));
```

- [x] **Шаг 2: Написать второй тест — что предупреждение НЕ исчезло при основаниях**

Дописать в `TargetFundAccountTest`:

```java
    @Test
    @DisplayName("ANO-157: якорь на НОЛЬ — предупреждение остаётся, это знание, а не пустота")
    void transferOverBalance_withZeroAnchor_stillNeedsConfirmation() {
        // Самая острая пара к тесту выше: свободных денег ноль в обоих случаях, а исход
        // противоположный. Там ноль означал «мы не знаем», здесь — «человек ввёл ноль».
        TargetFund f = fund(null, "0");
        UUID key = UUID.randomUUID();
        Account defaultAccount = AccountFixtures.defaultAccount();
        when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(fundRepo.findById(f.getId())).thenReturn(Optional.of(f));
        when(accountRepo.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount));
        when(checkpointRepo.findLatestForAccountAt(eq(defaultAccount.getId()), any()))
                .thenReturn(Optional.of(BalanceCheckpoint.builder()
                        .id(UUID.randomUUID()).date(LocalDate.now()).amount(BigDecimal.ZERO)
                        .account(defaultAccount).build()));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() ->
                service.transferToPocket(f.getId(), key, new BigDecimal("5000")))
                .isInstanceOf(ConfirmationRequiredException.class);

        verify(fundRepo, never()).save(any());
    }
```

- [x] **Шаг 3: Прогнать и убедиться, что оба падают**

Ожидается: первый — 409 из пустоты, второй — `ResponseStatusException` вместо `ConfirmationRequiredException`.

- [x] **Шаг 4: Перевести проверку достаточности**

В `doTransfer` заменить блок §4.2 целиком:

```java
        // ANO-87 (спека §4.2). Переложить больше, чем показывает остаток, можно — но только
        // осознанно. Остаток в продукте не банковская истина, а якорь плюс введённое: он
        // отстаёт от реальности, и жёсткий отказ наказывал бы за неточный ввод, что запрещает
        // правило 5.
        //
        // ANO-157: и только если у продукта ЕСТЬ основания судить. Без якоря и без фактов
        // свободные деньги равны нулю не потому, что их нет, а потому что мы не знаем.
        // Предупреждение из незнания — то же правило 5, нарушенное с другой стороны: человек,
        // ничего не вводивший, упирался в стену на первом же действии.
        //
        // NB ANO-39: LocalDate.now() здесь — прямой вызов. Clock в этот сервис не инжектится,
        // а половинчатая миграция одного сервиса хуже честных мест. При инъекции Clock не
        // пропустить: это денежный путь, тот же, где живёт ANO-125.
        if (amount.signum() > 0 && !confirm) {
            LocalDate today = LocalDate.now();
            if (accountBalanceService.knowsFreeMoneyAt(today)) {
                // Ровно то число, которое продукт САМ называет свободными деньгами: так же
                // считает CapitalService.cashLiquidAt.
                BigDecimal free = accountBalanceService.freeMoneyAt(today)
                        .add(accountBalanceService.noAnchorFallbackAt(today));
                if (amount.compareTo(free) > 0) {
                    throw new ConfirmationRequiredException(
                            "Account holds " + free + ", transferring " + amount
                                    + "; resend with confirm=true to proceed");
                }
            }
        }
```

Импорт `ru.selfin.backend.exception.ConfirmationRequiredException` добавить к остальным.

- [x] **Шаг 5: Прогнать весь `TargetFundAccountTest` — зелено**

- [x] **Шаг 6: Проверить мутацией**

Убрать условие `knowsFreeMoneyAt(today)` → тест без костыля краснеет. Вернуть.

- [x] **Шаг 7: Полный юнит-прогон бэкенда, затем коммит**

---

### Задача 4: Клиент отдаёт статус и детали

**Files:**
- Modify: `frontend/src/api/client.ts`
- Create: `frontend/src/lib/transferConfirm.ts`
- Test: `frontend/src/lib/transferConfirm.test.ts`

**Interfaces:**
- Consumes: код `"CONFIRM_REQUIRED"` из ответа бэкенда
- Produces: класс `ApiError { status: number; details: string[] }`; функция `needsConfirmation(err: unknown): boolean`

- [x] **Шаг 1: Написать падающие тесты**

```ts
import { describe, expect, it } from 'vitest';
import { needsConfirmation } from './transferConfirm';

describe('needsConfirmation', () => {
    it('409 с кодом CONFIRM_REQUIRED — предлагаем подтвердить', () => {
        expect(needsConfirmation({ status: 409, details: ['CONFIRM_REQUIRED'] })).toBe(true);
    });

    it('409 без кода — отказ безусловный, подтверждать нечего', () => {
        // «Снять больше накопленного»: денег в копилке физически нет.
        expect(needsConfirmation({ status: 409, details: [] })).toBe(false);
    });

    it('другой статус с тем же кодом — не наш случай', () => {
        expect(needsConfirmation({ status: 500, details: ['CONFIRM_REQUIRED'] })).toBe(false);
    });

    it('не ошибка API — false, а не исключение', () => {
        expect(needsConfirmation(new Error('boom'))).toBe(false);
        expect(needsConfirmation(undefined)).toBe(false);
        expect(needsConfirmation(null)).toBe(false);
    });
});
```

- [x] **Шаг 2: Прогнать `npm test` — падает, модуля нет**

- [x] **Шаг 3: Написать чистую функцию**

```ts
// frontend/src/lib/transferConfirm.ts

/** Код из ErrorResponse.details. Зеркалит ConfirmationRequiredException.CODE на бэкенде. */
export const CONFIRM_REQUIRED = 'CONFIRM_REQUIRED';

/**
 * Можно ли отменить этот отказ осознанным подтверждением (ANO-157).
 *
 * Проверка структурная, а не `instanceof ApiError`: модуль остаётся чистым и не тянет за
 * собой `api/client.ts` с его `import.meta.env`. Тесты проекта ходят под `environment: 'node'`,
 * и чистый модуль проверяется без всякой оснастки.
 *
 * По тексту сообщения НЕ матчим: формулировка стала бы негласным контрактом.
 */
export function needsConfirmation(err: unknown): boolean {
    const e = err as { status?: unknown; details?: unknown } | null | undefined;
    return e?.status === 409
        && Array.isArray(e.details)
        && e.details.includes(CONFIRM_REQUIRED);
}
```

- [x] **Шаг 4: Прогнать — четыре теста проходят**

- [x] **Шаг 5: Научить клиент бросать типизированную ошибку**

В `client.ts` над `request`:

```ts
/**
 * Ошибка ответа API. До ANO-157 клиент бросал голый `Error` со строкой, и ни один экран
 * не мог отличить 409 от 500 — и два разных 409 друг от друга тем более.
 *
 * Текст сообщения сохраняет прежний формат: экраны, показывающие `err.message`, не меняются.
 */
export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly details: string[],
        message: string,
    ) {
        super(message);
        this.name = 'ApiError';
    }
}
```

И в `request` заменить бросок:

```ts
    if (!res.ok) {
        // Причина с бэка (ErrorResponse.message) — иначе на фронте виден голый код
        // и любая 400 выглядит как «ничего не произошло» (ANO-30).
        let detail = '';
        let details: string[] = [];
        try {
            const body = await res.json();
            detail = body?.message ?? body?.error ?? '';
            if (Array.isArray(body?.details)) details = body.details;
        } catch { /* тело не JSON — обойдёмся кодом */ }
        throw new ApiError(res.status, details,
            `API error: ${res.status} ${path}${detail ? ` — ${detail}` : ''}`);
    }
```

- [x] **Шаг 6: Прогнать `npm test` и `npx tsc --noEmit`**

- [x] **Шаг 7: Проверить мутацией**

В `needsConfirmation` убрать проверку `e?.status === 409` → тест «другой статус с тем же кодом» краснеет. Вернуть.

- [x] **Шаг 8: Коммит**

---

### Задача 5: Фронт перестаёт решать сам

**Files:**
- Modify: `frontend/src/api/index.ts` (`transferToFund`)
- Modify: `frontend/src/pages/Funds.tsx` (`FundCard` строка 405, `TransferModal` строки 195-205 и кнопка submit)

**Interfaces:**
- Consumes: `needsConfirmation`, `transferToFund(fundId, amount, confirm?)`
- Produces: интерфейс, в котором решает бэкенд

- [x] **Шаг 1: Дать API третий параметр**

```ts
/**
 * @param fundId  идентификатор фонда
 * @param amount  сумма пополнения; знаковая (ANO-87), отрицательная снимает
 * @param confirm человек увидел предупреждение и настаивает (ANO-87 §4.2)
 */
export const transferToFund = (fundId: string, amount: number, confirm?: boolean) =>
    post<TargetFund>(`/funds/${fundId}/transfer`,
        confirm === undefined ? { amount } : { amount, confirm },
        { 'Idempotency-Key': generateUUID() });
```

- [x] **Шаг 2: Убрать стену в карточке**

`Funds.tsx`, условие рендера кнопки «пополнить»: убрать `&& pocketBalance > 0`, условие `!fund.accountId` оставить. Комментарий над ним заменить:

```tsx
                    {/* У копилки на счёте перевода нет: деньги двигаются на самом счёте, а
                        перевод создал бы вторую запись за те же рубли (бэкенд вернёт 400).
                        По сумме кармашка кнопку НЕ прячем (ANO-157): ноль означает и «денег
                        нет», и «мы не знаем», а решает это бэкенд — у него есть все числа. */}
                    {!reached && !fund.accountId && (
```

- [x] **Шаг 3: Убрать стену в форме и показать диалог**

Заменить `handleSubmit` целиком:

```tsx
    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        const num = amountValue(amount);   // ANO-33: сумма может быть выражением
        if (!num || num <= 0) return;
        setLoading(true);
        try {
            try {
                await transferToFund(fund.id, num);
            } catch (err) {
                // ANO-157: решает бэкенд. Подтверждаемый отказ — единственный, который мы
                // предлагаем отменить; безусловный (снять больше накопленного) пробрасываем.
                if (!needsConfirmation(err)) throw err;
                if (!confirm('Это больше, чем мы считаем свободным. Отложить всё равно?')) return;
                await transferToFund(fund.id, num, true);
            }
            onSuccess();
            onClose();
        } finally { setLoading(false); }
    };
```

И в кнопке submit убрать из `disabled` условие `|| (amountValue(amount) ?? 0) > pocketBalance`.

Импорт: `import { needsConfirmation } from '../lib/transferConfirm';`

- [x] **Шаг 4: Прогнать `npm test` и `npx tsc --noEmit`**

- [x] **Шаг 5: Коммит**

---

### Задача 6: Пустое состояние на живой базе

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/EmptyStateTransferIT.java`

- [x] **Шаг 1: Написать тесты**

Оснастку перенести из `FundMoneyFlowIT` без изменений — четыре метода и уборку:

* `resetMoneyState()` под `@BeforeEach` — `DELETE` из `fund_transactions`, `financial_events WHERE type = 'FUND_TRANSFER'`, `target_funds`, `balance_checkpoints`. Без неё тесты протекают друг в друга: контейнер один на класс;
* `anchorDefaultAccount(String amount)` — вставка чекпоинта на `CURRENT_DATE - 1`;
* `createFund(String name)` — копилка-конверт без `account_id`;
* `transfer(String fundId, BigDecimal amount, Boolean confirm)` — POST с `Idempotency-Key`, `confirm == null` означает «без подтверждения»;
* `fundBalance(String fundId)` — чтение `current_balance` напрямую.

```java
    @Test
    @DisplayName("ANO-157: без единого введённого числа перевод в копилку проходит молча")
    void transferOnEmptyState_passesWithoutConfirmation() throws Exception {
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("5000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId))
                .as("оснований судить об остатке нет — значит нет и мнения")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("ANO-157: якорь введён — предупреждение возвращается и несёт код")
    void transferOverBalance_withAnchor_isConfirmable() throws Exception {
        anchorDefaultAccount("1000");
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("5000"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("CONFIRM_REQUIRED"));

        transfer(fundId, new BigDecimal("5000"), true).andExpect(status().isOk());
        assertThat(fundBalance(fundId)).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("ANO-87: снять больше накопленного — отказ без кода, подтверждать нечего")
    void withdrawOverFundBalance_isNotConfirmable() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("5000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-5001"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").isEmpty());
    }
```

Якорь ставить ВЧЕРАШНИМ днём (`CURRENT_DATE - 1`), как в `FundMoneyFlowIT`: при якоре «сегодня» переводы ведут себя иначе, и тест мерил бы соседнее правило.

- [x] **Шаг 2: Прогнать IT отдельно — три теста проходят**

- [x] **Шаг 3: Проверить зубы мутацией**

Убрать условие `knowsFreeMoneyAt(today)` в `TargetFundService` → первый тест краснеет. Вернуть.

- [x] **Шаг 4: Коммит**

---

### Задача 7: Полная проверка и отметка

- [x] **Шаг 1: Полный прогон** — `mvnw verify` из `backend`, `npm test` и `npx tsc --noEmit` из `frontend`. Ожидается 366+ юнитов, 146+ интеграционных, 106+ фронтовых, ноль падений.

- [x] **Шаг 2: Пересобрать стенд**

```bash
COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build backend
```

Дождаться `200` на `/api/v1/pocket`. Собирается несколько минут — не проверять раньше, иначе ответит старый контейнер.

- [x] **Шаг 3: Проверить, что при основаниях ничего не изменилось**

На эталонном стенде якорь есть, поэтому поведение обязано остаться прежним: перевод сверх остатка даёт 409 с `details: ["CONFIRM_REQUIRED"]`, в пределах остатка проходит. Сверить числа стенда с эталоном: кармашек 26 000, остаток 60 000, капитал 2 668 277, ликвид 210 000, якорь 2026-08-29.

- [x] **Шаг 4: Воспроизвести пустое состояние**

```bash
bash tools/ano50-reset-stand.sh
```

Без аргумента скрипт поднимает пустую базу — новый пользователь. Создать копилку и перевести в неё сумму: **обязано пройти без подтверждения**. До починки упиралось в 409.

- [x] **Шаг 5: Восстановить стенд**

```bash
bash tools/ano50-reset-stand.sh C:/Users/Kirill/selfin-backups/ano50-fixsession-start-2026-09-12.sql
```

Сверить эталонные числа ещё раз.

- [x] **Шаг 6: Отметиться в ANO-157** — комментарий с замерами до и после, списком мутаций и результатом стенда. Перевести в Done.

---

## Известный остаток, в задачу не входит

Подпись формы перевода говорит `доступно {fmt(pocketBalance)}` и на пустом состоянии покажет «доступно 0» — то же мнение из незнания, но уже текстом. Починить честно нельзя, не дав фронту тот же источник, что у бэкенда: сейчас у него `pocket.trajectory[0].balance`, а у бэкенда `freeMoneyAt + noAnchorFallbackAt`. Сведение этих двух чисел — отдельный вопрос, смежный с ANO-9, и спека прямо выносит его за рамки.

---

## Выполнено 13 сентября 2026

Коммиты `7b0a8c9`, `e244cb3`, `3830d68`, `b221e0d`, `ccb8175`, `3ada909`, `2ea7e0e`. Прогон: **371 юнит + 146 интеграционных**, фронт 106 тестов, `tsc` чистый. Семь мутаций, каждая роняет ровно свой тест.

### Замер на стенде

```
новый пользователь, база пустая     перевод 15 000 → 200, копилка 15 000
                                    до починки → 409 (замерено мутацией IT)

эталонный стенд, якорь 29.08        перевод 99 999 999 → 409
                                    details: ["CONFIRM_REQUIRED"]
                                    с confirm: true → 200
```

Числа стенда после пересборки совпали с эталонными до копейки, инварианты зелёные.

### Где реальность разошлась с планом

**Второй костыль того же вида, которого план не предвидел.** Полный прогон уронил `FundMoneyFlowIT.transfer_overBalance_needsConfirmation`: тест не ставил якорь вовсе и доказывал правило ANO-87, опираясь на дефект ANO-157 — предупреждение приходило из незнания. Дан якорь на 1000; теперь проверяется заявленное правило. План предвидел один такой костыль (`TargetFundAccountTest`), их оказалось два.

**Тест обработчика написан не так, как в плане.** Существующий класс проверяет обработчик через `MockMvc` с бросающим контроллером, а не прямым вызовом. Взят этот стиль — он доказывает и выбор обработчика. Заодно добавлен парный тест на безусловный отказ.

**Порядок проверки в `doTransfer` переставлен относительно черновика.** Проверка оснований стоит ПЕРЕД подсчётом свободных денег: у человека без якоря и фактов считать незачем, сравнивать не с чем. Первая редакция считала всегда.

**Проп `pocketBalance` удалён из `FundCard` целиком**, а не оставлен неиспользуемым: после снятия стены он там больше ни для чего не нужен.
