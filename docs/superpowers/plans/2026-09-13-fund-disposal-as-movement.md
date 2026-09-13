# Удаление копилки — движение денег, а не флаг: план реализации (ANO-156)

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛ: `superpowers:subagent-driven-development` либо `superpowers:executing-plans`. Шаги отмечаются чекбоксами.

**Цель:** удаление копилки перестаёт переписывать прошлые точки графика ликвида; капитал при «потрачено на цель» падает с даты удаления, а не с даты первого взноса.

**Архитектура:** ветка «потрачено» начинает писать компенсирующее движение сегодняшней датой — как уже делает «вернуть». Фильтр по `fund.deleted` убирается совсем: удаление становится движением денег, а не флагом. Миграция `V24` компенсирует копилки, удалённые раньше.

**Стек:** Java 21, Spring Boot 4.0.3, PostgreSQL, Flyway, JUnit 5, Testcontainers.

**Спека:** `docs/superpowers/specs/2026-09-13-fund-disposal-as-movement-design.md`.

## Глобальные ограничения

- **Порядок задач обязателен.** Компенсация (задача 1) идёт строго ДО снятия фильтра (задача 2). Обратный порядок оставил бы между коммитами окно, в котором деньги копилки, удалённой через «потрачено», снова висят в капитале вечно — то есть воскресил бы ANO-86.
- **ANO-86 не ломается.** Текущее число в обеих ветках остаётся прежним: при «потрачено» капитал падает на сумму, при «вернуть» не меняется. Это проверяется существующими тестами `delete_spent_dropsCapital` и `delete_return_givesMoneyBack` — они обязаны остаться зелёными без правок.
- **«Потрачено» НЕ создаёт событие `FUND_TRANSFER`.** Деньги ушли на цель, на счёт они не возвращаются: счёт потерял их ещё при первом переводе. Создать событие значило бы вернуть деньги, которых нет.
- **Миграция компенсирует СУММУ ДВИЖЕНИЙ, а не `current_balance`.** Копилка с балансом, но без движений, от компенсации по балансу ушла бы в минус.
- **Копилки со счётом не трогаются:** своих денег у них нет, движений тоже.
- Каждый новый тест проверяется мутацией.

## Проверка перед выкладкой на рабочую базу

`V24` — миграция данных, она отработает при первом старте бэкенда. До выкладки выполнить на целевой базе и посмотреть, что именно она тронет:

```sql
SELECT f.id, f.name, f.current_balance,
       COALESCE(SUM(t.amount) FILTER (WHERE NOT t.is_deleted), 0) AS movements
FROM target_funds f
LEFT JOIN fund_transactions t ON t.fund_id = f.id
WHERE f.is_deleted = true AND f.account_id IS NULL
GROUP BY f.id, f.name, f.current_balance
HAVING COALESCE(SUM(t.amount) FILTER (WHERE NOT t.is_deleted), 0) <> 0;
```

Пустой ответ — миграция будет пустой операцией. На эталонном стенде ответ пуст.

## Запуск тестов

Из каталога `backend`:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Один IT-класс отдельно:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify -Dit.test=FundMoneyFlowIT -DfailIfNoSpecifiedTests=false -Dtest=ZzzNoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
```

**Ловушка отчётов.** Падающий юнит-тест не даёт Maven дойти до фазы интеграционных, а отчёт в `target/failsafe-reports` остаётся от прошлого прогона и выглядит зелёным.

## Структура файлов

| файл | ответственность |
|---|---|
| `service/TargetFundService.java` | **изменяется.** Ветка `SPENT` пишет движение и обнуляет баланс |
| `repository/FundTransactionRepository.java` | **изменяется.** Фильтр `fund.deleted` снимается, javadoc переписывается |
| `db/migration/V24__compensate_disposed_funds.sql` | **новый.** Копилки, удалённые до правки |
| `test/.../FundMoneyFlowIT.java` | **изменяется.** Прошлое не переписывается — обе ветки |
| `test/.../FundTransactionRepositoryIT.java` | **изменяется.** Движения удалённой копилки считаются в прошлом |
| `test/.../DisposedFundMigrationIT.java` | **новый.** Легаси-копилка после миграции ведёт себя как свежая |

---

### Задача 1: «Потрачено на цель» пишет движение

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` (ветка `SPENT` в `delete(UUID, FundMoneyDisposal)`)
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`

**Interfaces:**
- Consumes: ничего
- Produces: после удаления через `money=SPENT` сумма движений копилки равна нулю, `current_balance` равен нулю

- [x] **Шаг 1: Написать падающий тест**

Дописать в `FundMoneyFlowIT` рядом с `delete_spent_dropsCapital`:

```java
    @Test
    @DisplayName("ANO-156: «потрачено» списывает деньги движением, а не флагом")
    void delete_spent_writesCompensatingMovement() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        assertThat(movementSum(fundId))
                .as("деньги ушли из копилки — это обязано быть записано движением")
                .isEqualByComparingTo("0");
        assertThat(fundBalance(fundId))
                .as("поле и сумма движений не имеют права разъезжаться")
                .isEqualByComparingTo("0");
        assertThat(fundTransferEvents(fundId))
                .as("события возврата быть не должно: деньги потрачены, а не возвращены")
                .isEqualTo(1);
    }
```

Оснастка — дописать рядом с существующими помощниками класса:

```java
    private BigDecimal movementSum(String fundId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM fund_transactions"
                        + " WHERE fund_id = ?::uuid AND is_deleted = false",
                BigDecimal.class, fundId);
    }

    /** Сколько живых событий FUND_TRANSFER у копилки: «вернуть» добавляет второе, «потрачено» — нет. */
    private int fundTransferEvents(String fundId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE target_fund_id = ?::uuid"
                        + " AND is_deleted = false AND type = 'FUND_TRANSFER'",
                Integer.class, fundId);
        return n == null ? 0 : n;
    }
```

- [x] **Шаг 2: Прогнать IT отдельно и убедиться, что падает**

Ожидается: `movementSum` равен 20000 — компенсации нет.

- [x] **Шаг 3: Написать компенсацию**

В `TargetFundService.delete(UUID, FundMoneyDisposal)`, в ветку `else` (SPENT), ПОСЛЕ переименования событий:

```java
                eventRepository.findAllByTargetFundIdAndDeletedFalse(id)
                        .forEach(e -> e.setDescription(fund.getName()));

                // ANO-156: деньги ушли из копилки СЕГОДНЯ, и это обязано быть движением, а не
                // флагом. Раньше их «списывал» фильтр t.fund.deleted в запросе суммы копилок —
                // но флаг «удалена сейчас» применялся и ко всем прошлым датам, и график
                // ликвида задним числом проседал от даты первого взноса.
                //
                // Событие FUND_TRANSFER здесь НЕ создаётся, в отличие от ветки RETURN: счёт
                // потерял эти деньги ещё при первом переводе, и возвращать их некуда —
                // они потрачены на цель. Создать событие значило бы вернуть несуществующее.
                transactionRepository.save(FundTransaction.builder()
                        .fund(fund)
                        .idempotencyKey(UUID.randomUUID())
                        .amount(balance.negate())
                        .transactionDate(LocalDate.now())
                        .build());
                fund.setCurrentBalance(BigDecimal.ZERO);
```

- [x] **Шаг 4: Прогнать — новый тест зелёный, `delete_spent_dropsCapital` и `delete_spent_renamesJournalEntry` не затронуты**

- [x] **Шаг 5: Проверить мутацией**

Убрать `transactionRepository.save(...)` → новый тест краснеет на `movementSum`. Вернуть.

- [x] **Шаг 6: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/service/TargetFundService.java \
        backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java
git commit -m "feat(ano-156): «потрачено на цель» списывает деньги движением, а не флагом"
```

---

### Задача 2: Фильтр снят, прошлое перестаёт меняться

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FundTransactionRepository.java`
- Test: `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`
- Test: `backend/src/test/java/ru/selfin/backend/repository/FundTransactionRepositoryIT.java`

**Interfaces:**
- Consumes: компенсацию из задачи 1
- Produces: `sumEnvelopeFundsByTransactionDateLessThanEqual` перестаёт зависеть от текущего флага копилки

- [x] **Шаг 1: Написать падающие тесты — прошлое не меняется, обе ветки**

Дописать в `FundMoneyFlowIT`:

```java
    @Test
    @DisplayName("ANO-156: «потрачено» не переписывает прошлое")
    void delete_spent_doesNotRewriteHistory() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        LocalDate past = LocalDate.now().minusMonths(1);
        contributeOn(fundId, new BigDecimal("20000"), past);
        BigDecimal liquidPastBefore = capitalService.cashLiquidAt(past);

        mockMvc.perform(delete("/api/v1/funds/{id}?money=SPENT", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(past))
                .as("месяц назад деньги лежали в копилке — удаление сегодня не меняет прошлое")
                .isEqualByComparingTo(liquidPastBefore);
    }

    @Test
    @DisplayName("ANO-156: «вернуть» тоже не переписывает прошлое")
    void delete_return_doesNotRewriteHistory() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        LocalDate past = LocalDate.now().minusMonths(1);
        contributeOn(fundId, new BigDecimal("20000"), past);
        BigDecimal liquidPastBefore = capitalService.cashLiquidAt(past);

        mockMvc.perform(delete("/api/v1/funds/{id}?money=RETURN", fundId))
                .andExpect(status().isNoContent());

        assertThat(capitalService.cashLiquidAt(past))
                .as("возврат датируется сегодняшним днём и прошлого не касается")
                .isEqualByComparingTo(liquidPastBefore);
    }
```

Оснастка — взнос ПРОШЛОЙ датой; через API так нельзя, `doTransfer` всегда ставит сегодня:

```java
    /**
     * Взнос в копилку прошлой датой: движение, событие FUND_TRANSFER и баланс копилки —
     * ровно то, что создал бы перевод, но задним числом. Через API так нельзя: doTransfer
     * всегда ставит сегодняшнюю дату.
     */
    private void contributeOn(String fundId, BigDecimal amount, LocalDate date) {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE type = 'EXPENSE' AND is_deleted = false LIMIT 1",
                String.class);
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?::numeric, ?::date, false, now())
                """, fundId, amount.toPlainString(), date.toString());
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, fact_amount, status, priority,
                     is_deleted, event_kind, created_at, target_fund_id, description)
                VALUES (gen_random_uuid(), ?::date, ?::uuid, 'FUND_TRANSFER', ?::numeric,
                        'EXECUTED', 'MEDIUM', false, 'FACT', now(), ?::uuid, 'В копилку')
                """, date.toString(), categoryId, amount.toPlainString(), fundId);
        jdbc.update("UPDATE target_funds SET current_balance = ?::numeric WHERE id = ?::uuid",
                amount.toPlainString(), fundId);
    }
```

- [x] **Шаг 2: Прогнать и убедиться, что оба падают**

Ожидается: прошлая точка проседает на 20 000 в обеих ветках.

- [x] **Шаг 3: Снять фильтр**

В `FundTransactionRepository` убрать строку `AND t.fund.deleted = false` и переписать абзац javadoc про ANO-86:

```java
     * <p><b>ANO-156: фильтра по {@code t.fund.deleted} здесь НЕТ, и это важно.</b> Он стоял
     * тут с ANO-86 и закрывал одну дыру: ветка «потрачено на цель» не писала компенсирующее
     * движение, и деньги удалённой копилки висели в капитале вечно. Но флаг «удалена СЕЙЧАС»
     * применялся ко ВСЕМ прошлым датам, а {@code BaselineTimelineBuilder.buildPastPoints}
     * зовёт {@code cashLiquidAt} для каждого прошлого месяца — и удаление копилки сегодня
     * переписывало историю ликвида от даты первого взноса.
     *
     * <p>Теперь обе ветки удаления пишут движение (ANO-156), и флаг здесь не нужен: сумма
     * сама обнуляется с даты выбытия и сама сохраняет прошлое. Возвращать фильтр нельзя —
     * он снова сломает историю.
```

- [x] **Шаг 4: Дописать тест репозитория**

В `FundTransactionRepositoryIT`:

```java
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
                .as("месяц назад деньги в копилке были")
                .isEqualByComparingTo("20000");
        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("с даты выбытия их нет")
                .isEqualByComparingTo("0");
    }
```

- [x] **Шаг 5: Прогнать — новые тесты зелёные, `delete_spent_dropsCapital` и `delete_return_givesMoneyBack` не затронуты**

- [x] **Шаг 6: Проверить мутацией**

Вернуть `AND t.fund.deleted = false` → оба теста «не переписывает прошлое» и тест репозитория краснеют. Вернуть обратно.

- [x] **Шаг 7: Коммит**

---

### Задача 3: Миграция для копилок, удалённых раньше

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__compensate_disposed_funds.sql`
- Test: `backend/src/test/java/ru/selfin/backend/DisposedFundMigrationIT.java`

**Interfaces:**
- Consumes: снятый фильтр из задачи 2
- Produces: у каждой удалённой копилки-конверта сумма движений равна нулю

- [x] **Шаг 1: Написать миграцию**

```sql
-- ANO-156. Копилки, удалённые ДО перехода на компенсирующее движение.
--
-- Снятие фильтра t.fund.deleted означает, что движения всех удалённых копилок снова
-- считаются. У копилки, удалённой через «потрачено на цель» (или ещё раньше, одной
-- строкой setDeleted), компенсации нет — и её деньги вернулись бы в текущий капитал,
-- воскресив ANO-86 ровно для неё.
--
-- Компенсируется СУММА ДВИЖЕНИЙ, а не current_balance: именно движения складывает запрос
-- sumEnvelopeFundsByTransactionDateLessThanEqual. Копилка с ненулевым балансом, но без
-- движений, от компенсации по балансу ушла бы в минус — миграция создала бы отрицательные
-- деньги там, где лечила лишние.
--
-- Дата — сегодняшняя, и это вынужденно: у target_funds нет updated_at, даты удаления в
-- схеме не существует. Продукт правит свои книги сегодня; история до сегодняшнего дня
-- остаётся такой, какой человек её видел.
--
-- Копилки, удалённые через «вернуть», сюда не попадают: doTransfer уже обнулил им движения.
-- Копилки со счётом не попадают тоже: своих денег у них нет.
INSERT INTO fund_transactions
    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
SELECT gen_random_uuid(), f.id, gen_random_uuid(), -SUM(t.amount), CURRENT_DATE, false, now()
FROM target_funds f
JOIN fund_transactions t ON t.fund_id = f.id AND t.is_deleted = false
WHERE f.is_deleted = true
  AND f.account_id IS NULL
GROUP BY f.id
HAVING SUM(t.amount) <> 0;

-- Поле и сумма движений не имеют права разъезжаться: сервис с этой правки обнуляет и его.
UPDATE target_funds
SET current_balance = 0
WHERE is_deleted = true
  AND account_id IS NULL
  AND COALESCE(current_balance, 0) <> 0;
```

- [x] **Шаг 2: Написать тест на живой базе**

**Чего этот тест НЕ делает и почему.** Проверить саму миграцию прогоном Flyway нельзя: на базе Testcontainers её очередь наступает, когда компенсировать нечего — таблицы пусты. Утверждение вида «у всех удалённых копилок движения обнулены» на свежем контейнере зелёное по построению, то есть не проверяет ничего.

Поэтому проверяется **то же SQL на тех же данных**: заводится копилка в состоянии «удалена до правки», применяется в точности запрос миграции, и проверяется результат. Расхождение между запросом здесь и запросом в `V24` — единственный способ обмануть этот тест, поэтому SQL копируется дословно.

`DisposedFundMigrationIT`:

```java
    @Test
    @DisplayName("ANO-156: легаси-копилка после компенсации ведёт себя как свежая")
    void legacyDisposedFund_behavesLikeFreshOne() {
        LocalDate past = LocalDate.now().minusMonths(1);
        String fundId = insertDisposedFundWithMovement(new BigDecimal("20000"), past);

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("до компенсации деньги удалённой копилки висят в капитале — это ANO-86")
                .isEqualByComparingTo("20000");

        applyMigrationSql();

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(past))
                .as("прошлое сохранено: месяц назад деньги в копилке были")
                .isEqualByComparingTo("20000");
        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("сегодня их нет — ANO-86 не воскресла")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-156: копилка с балансом, но без движений, в минус не уходит")
    void fundWithBalanceButNoMovements_isNotDrivenNegative() {
        // Ради этого случая миграция компенсирует сумму движений, а не current_balance.
        String fundId = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at)
                VALUES (?::uuid, 'Легаси без движений', 100000, 5000, 'FUNDING', 'SAVINGS',
                        100, true, now())
                """, fundId);

        applyMigrationSql();

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("компенсировать нечего — миграция не имеет права создавать отрицательные деньги")
                .isEqualByComparingTo("0");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /** Копилка в состоянии «удалена до ANO-156»: движение есть, компенсации нет. */
    private String insertDisposedFundWithMovement(BigDecimal amount, LocalDate date) {
        String fundId = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at)
                VALUES (?::uuid, 'Легаси', 100000, ?::numeric, 'FUNDING', 'SAVINGS',
                        100, true, now())
                """, fundId, amount.toPlainString());
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?::numeric, ?::date,
                        false, now())
                """, fundId, amount.toPlainString(), date.toString());
        return fundId;
    }

    /** Дословно тело V24__compensate_disposed_funds.sql. Расхождение здесь — дыра в проверке. */
    private void applyMigrationSql() {
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                SELECT gen_random_uuid(), f.id, gen_random_uuid(), -SUM(t.amount), CURRENT_DATE,
                       false, now()
                FROM target_funds f
                JOIN fund_transactions t ON t.fund_id = f.id AND t.is_deleted = false
                WHERE f.is_deleted = true
                  AND f.account_id IS NULL
                GROUP BY f.id
                HAVING SUM(t.amount) <> 0
                """);
        jdbc.update("""
                UPDATE target_funds
                SET current_balance = 0
                WHERE is_deleted = true
                  AND account_id IS NULL
                  AND COALESCE(current_balance, 0) <> 0
                """);
    }
```

Поля класса и уборка — как в `FundTransactionRepositoryIT`: `@Autowired FundTransactionRepository fundTxRepo`, `@Autowired JdbcTemplate jdbc`, контейнер через `@Container @ServiceConnection`, а в `@AfterEach` — `jdbc.update("DELETE FROM fund_transactions")` и `jdbc.update("DELETE FROM target_funds")`.

- [x] **Шаг 3: Прогнать IT отдельно — оба теста зелёные**

Первый обязан пройти через оба утверждения: 20 000 ДО компенсации и 0 после. Если первое утверждение не выполнится, тест ничего не проверяет — значит данные легаси собраны неверно.

- [x] **Шаг 4: Проверить мутацией**

Заменить в `applyMigrationSql` компенсацию суммы движений на компенсацию по `current_balance` (`-f.current_balance` вместо `-SUM(t.amount)`, без `JOIN`) → второй тест краснеет: копилка без движений уходит в −5000. Вернуть.

- [x] **Шаг 5: Коммит**

---

### Задача 4: Полная проверка и отметка

- [x] **Шаг 1: Полный прогон** — `mvnw verify`. Ожидается 371+ юнитов и 151+ интеграционных, ноль падений.

- [x] **Шаг 2: Проверочный запрос на стенде** — тот, что в начале плана. Ожидается пустой ответ: на эталонном стенде компенсировать нечего.

- [x] **Шаг 3: Пересобрать стенд**

```bash
COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build backend
```

Дождаться `200` на `/api/v1/pocket`. Сверить эталонные числа: кармашек 26 000, остаток 60 000, капитал 2 668 277, ликвид 210 000, якорь 2026-08-29.

- [x] **Шаг 4: Воспроизвести сценарий ANO-156**

Копилка-конверт со взносом прошлой датой (через SQL — API датирует сегодня), снять точки `/strategy/timeline`, удалить копилку через `money=SPENT`, снять точки снова. **Прошлые точки обязаны совпасть**; до починки проседали на сумму взноса.

- [x] **Шаг 5: Восстановить стенд**

```bash
bash tools/ano50-reset-stand.sh C:/Users/Kirill/selfin-backups/ano50-fixsession-start-2026-09-12.sql
```

Сверить эталонные числа и прогнать `node tools/ano50-invariants.mjs`.

- [x] **Шаг 6: Отметиться в ANO-156** — комментарий с замерами до и после, списком мутаций и результатом проверочного запроса. Перевести в Done.

---

## Выполнено 13 сентября 2026

Коммиты `27226a1`, `b9c9972`, миграция и её тест. Прогон: **371 юнит + 151 интеграционный**, ноль падений. Четыре мутации, каждая роняет ровно свой тест.

### Замер на стенде

```
точка графика   до починки (утром)   после починки
2026-07         67 436 → 47 436      67 436 → 67 436
2026-08         80 000 → 60 000      80 000 → 80 000
```

Движения копилки после удаления через «потрачено»: `+20 000` от 15.07 и `−20 000` сегодняшним днём. Баланс копилки ноль, ликвид сегодня вернулся к эталонным 210 000 — ANO-86 держится компенсацией, а не фильтром.

Проверочный запрос на эталонном стенде дал пустой ответ: компенсировать нечего, `V24` отработала пустой операцией и записана в `flyway_schema_history` как успешная.

### Где реальность разошлась с планом

**Удалён тест `deletedFund_dropsOutOfLiquid`, и это решение, а не уборка.** План его не предвидел. Он помечал копилку удалённой В ОБХОД сервиса и проверял ровно снятый фильтр — другого смысла у обхода не было. После правки обход сервиса есть обход компенсации, то есть больше не эквивалент удаления, и прежнее утверждение противоречило бы замыслу.

Само утверждение не потеряно: `delete_spent_dropsCapital` проверяет то же — капитал падает ровно на сумму — но через настоящий путь. Причина удаления оставлена комментарием на месте теста, чтобы следующий читатель не завёл его заново.
