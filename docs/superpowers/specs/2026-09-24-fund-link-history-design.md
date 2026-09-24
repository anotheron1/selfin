# История привязок копилки к счёту (ANO-163)

Спека и план одним документом (план спринта §7). Способ — «история привязок» из трёх предложенных — принят владельцем в чате 24.09.2026.

## 1. Что сломано

Сумму копилок-конвертов в ликвидном капитале складывает `FundTransactionRepository.sumEnvelopeFundsByTransactionDateLessThanEqual`:

```sql
WHERE t.deleted = false AND t.transactionDate <= :date AND t.fund.accountId IS NULL
```

Признак «у копилки СЕЙЧАС нет счёта» применяется ко всем прошлым датам. Привяжешь копилку сегодня — её взносы выпадут из капитала за каждый прошлый день, начиная с первого взноса. Отвяжешь — вернутся.

Замер на стенде 16.09 (из задачи): копилка с одним взносом 20 000 от 05.08, капитал за август:

| состояние копилки | капитал за август |
|---|---|
| конверт | 2 558 277 |
| привязана к карте сегодня | 2 538 277 |
| снова отвязана | 2 558 277 |

**Кого задевает.** Всё прошлое идёт через `CapitalService.cashLiquidAt(t)`:

* «Стратегия» — прошлые точки графика, конец каждого месяца (`BaselineTimelineBuilder.buildPastPoints`);
* «Капитал» — траектория по концам месяцев и изменения «за месяц / квартал / год» (`CapitalService.trajectory` и `summary` через `liquidAt`).

**На данных владельца.**

* Дамп 17.09: ни одной привязанной копилки, движений копилок нет вовсе.
* Живой экземпляр 24.09, список копилок через API, только чтение: четыре копилки, привязанных нет. У «Египта» 25 000, внесены в сентябре.

Испорченного сейчас нет. Дефект проявится, если привязать копилку с деньгами в месяце после взноса: «Египет», привязанный в октябре, уменьшил бы капитал за сентябрь на 25 000.

## 2. Почему не способы из задачи

* **«Хранить в движении, было ли оно конвертным».** В копилку на счёте переводить нельзя (`rejectTransferToAccountBackedFund`), значит каждое движение пишется, пока копилка конверт. Признак у всех одинаковый и ничего не различает.
* **«При привязке писать закрывающее движение».** Прошлое чинит, но кладёт служебные строки в таблицу денег. Их пришлось бы пропускать везде, где движения читаются как взносы:
  * прогноз даты цели (`calcEstimatedCompletion`);
  * отвязка — ANO-158 возвращает копилке «её собственную историю переводов»;
  * любой будущий список взносов.

  Ловушка того же рода, что `deleted` в Java и `is_deleted` в схеме: кто не знает, тот посчитает неверно.
* **Дата привязки у самой копилки** (одна колонка) чинит привязку, но не отвязку. После отвязки движения снова считаются за все даты, и период на счёте переписывается задним числом.

## 3. Решение

Привязка — не признак, а история. Новая таблица `fund_account_links`:

* копилка и счёт;
* `linked_from` — первый день на счёте;
* `linked_to` — первый день снова конвертом; пусто — копилка на счёте и сейчас.

Запрос на дату `t` отбрасывает движения копилки, если в этот день она жила на счёте:

```sql
AND NOT EXISTS (SELECT 1 FROM FundAccountLink l
                WHERE l.fundId = t.fund.id
                  AND l.linkedFrom <= :date
                  AND (l.linkedTo IS NULL OR l.linkedTo > :date))
```

**Единица истории — день.** День привязки — день на счёте, день отвязки — день конверта. Привязка и отвязка в один день дают пустой период: копилка весь день конверт.

**Историю пишет `TargetFundService`** — единственное место, где меняется `accountId`. Проверено поиском: `setAccountId` один, `.accountId(` в построителе один; `WishlistConversionService` создаёт копилки без счёта; удаление счёта с целью запрещено («unlink it first»).

* `create` со счётом — открытый период с сегодняшнего дня.
* `update` со сменой счёта — открытый период закрывается сегодняшним днём; если счёт новый, открывается следующий.
* Правка без смены счёта историю не трогает. Это важно: фронт шлёт `accountId` в каждой правке копилки (`Funds.tsx`).

**Почему не триггер в базе.** Триггер поймал бы и обход сервиса, но взял бы `CURRENT_DATE` базы вместо внедрённых часов (ANO-39, `ClockInjectionGuardTest`). Дата привязки разошлась бы с датой продукта около полуночи и не подменялась бы в тестах.

**Что стережёт база:**

* одна открытая привязка на копилку — `uq_fund_account_links_open`;
* `linked_to ≥ linked_from`.

## 4. Что не меняется

* **Числа «сегодня».** Открытый период есть ровно у копилки, привязанной сейчас, а закрытые кончаются не позже сегодняшнего дня. На сегодняшнюю дату новое условие совпадает со старым `accountId IS NULL`.
* **Отвязка по ANO-158:** поле копилки = сумма её движений. Движения не трогаются, правило прежнее.
* **Выбытие по ANO-156** компенсирует сумму движений, прогноз даты цели считает движения. Служебных строк в таблице денег нет, обоим не о чем узнавать.
* **Деньги конверта в день привязки** по-прежнему перестают считаться отдельно. Сказать об этом на экране — ANO-164, не эта задача.

## 5. Старые данные

Копилки, привязанные до правки, получают открытый период с даты рождения копилки. Настоящая дата привязки неизвестна: у `target_funds` нет `updated_at`, журнала изменений нет.

«С рождения» — ровно то, как их считал старый запрос: исключал за все даты. Поэтому миграция не сдвигает ни одного числа. Прошлое старых привязок остаётся таким, каким его показывали. Удалённые копилки со счётом переносятся тоже: старый запрос исключал и их.

У владельца таких строк ноль (§1). Метка «меняет-данные» не нужна.

`fund_id` ссылается на `target_funds` с `ON DELETE CASCADE`. Продукт копилки не стирает, удаление мягкое. Но шесть тестовых классов чистят `target_funds` через `DELETE`, и без каскада упали бы на внешнем ключе.

## 6. Как проверяется

Каждое место правки — свой тест и своя мутация (память: правка в N местах требует N тестов).

| что | тест | мутация, которая обязана его уронить |
|---|---|---|
| запрос смотрит на день, а не на сегодня | `FundTransactionRepositoryIT`: привязка сегодня — месяц назад сумма та же | вернуть `t.fund.accountId IS NULL` |
| первый день на счёте — день на счёте | там же, период с `monthAgo`: на `monthAgo` суммы нет | `l.linkedFrom < :date` |
| день отвязки — день конверта | там же, период по `today`: на `today` сумма есть | `l.linkedTo >= :date` |
| привязка через сервис пишет историю | `FundMoneyFlowIT`: привязка через API не меняет прошлый месяц, а сегодня деньги конверта не считаются | не писать открытый период |
| отвязка закрывает, а не стирает | `FundMoneyFlowIT`: период на счёте после отвязки остаётся без денег конверта, сегодня они вернулись | удалять строку вместо закрытия; не закрывать вовсе |
| перепривязка со счёта на счёт | `FundMoneyFlowIT`: 200, две строки, первая закрыта сегодня | `save` вместо `saveAndFlush` при закрытии — вставка идёт раньше обновления и упирается в `uq_fund_account_links_open` |
| копилка, рождённая на счёте | `FundMoneyFlowIT`: после создания открытый период с сегодняшнего дня | не писать период в `create` |
| перенос старых привязок | `FundLinkMigrationIT`: SQL миграции дословно на легаси-данных — числа те же, что давал старый запрос | `linked_from = CURRENT_DATE`; перенос только живых копилок |

Стенд: сценарий из задачи до и после правки — взнос 20 000 задним числом в прошлый месяц, привязка, отвязка, капитал за тот месяц через API после каждого шага.

## 7. Что не закрывает

* ANO-164: при привязке копилки с деньгами человеку не говорят, что отложенное перестаёт считаться отдельно.
* Прошлое старых привязок: даты нет, оно остаётся таким, каким его показывали.

---

# План

Ветка `fix/ano-163-fund-link-history`. Локально гоняется только затронутое, полный набор — CI (план спринта §7).

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend verify -Dit.test=<Класс> -Dtest=<Класс> -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

### Задача 1. Таблица, сущность, запрос

**Файлы:**

* создать `backend/src/main/resources/db/migration/V26__fund_account_links.sql`;
* создать `backend/src/main/java/ru/selfin/backend/model/FundAccountLink.java`;
* создать `backend/src/main/java/ru/selfin/backend/repository/FundAccountLinkRepository.java`;
* изменить `backend/src/main/java/ru/selfin/backend/repository/FundTransactionRepository.java` — запрос и javadoc;
* тест `backend/src/test/java/ru/selfin/backend/repository/FundTransactionRepositoryIT.java`.

- [ ] **Шаг 1.** Миграция, сущность, репозиторий — без изменения запроса. Поведение прежнее, сборка зелёная.

```sql
CREATE TABLE fund_account_links (
    id          UUID      PRIMARY KEY,
    fund_id     UUID      NOT NULL REFERENCES target_funds(id) ON DELETE CASCADE,
    account_id  UUID      NOT NULL REFERENCES accounts(id),
    linked_from DATE      NOT NULL,
    linked_to   DATE,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_fund_account_links_period
        CHECK (linked_to IS NULL OR linked_to >= linked_from)
);

CREATE UNIQUE INDEX uq_fund_account_links_open
    ON fund_account_links (fund_id) WHERE linked_to IS NULL;

INSERT INTO fund_account_links (id, fund_id, account_id, linked_from, linked_to, created_at)
SELECT gen_random_uuid(), f.id, f.account_id, f.created_at::date, NULL, now()
FROM target_funds f
WHERE f.account_id IS NOT NULL;
```

```java
public interface FundAccountLinkRepository extends JpaRepository<FundAccountLink, UUID> {
    Optional<FundAccountLink> findByFundIdAndLinkedToIsNull(UUID fundId);
}
```

- [ ] **Шаг 2.** Тесты в `FundTransactionRepositoryIT` через строки истории напрямую:
  * `excludesFundLinkedThatDay` — переписанный `excludesFundsLinkedToAnAccount`: у привязанной копилки открытый период с сегодняшнего дня;
  * `linkToday_keepsPast` — взнос 20 000 месяц назад, период с сегодняшнего дня: месяц назад 20 000, сегодня 0;
  * `unlink_keepsPeriodOnAccount` — взнос два месяца назад, период `[monthAgo, today)`: два месяца назад 20 000, на `monthAgo` 0, на `today` 20 000.

  `cleanDb` удаляет строки истории до копилок.
- [ ] **Шаг 3.** Прогон — красный: запрос не читает историю, `linkToday_keepsPast` даёт 0 месяц назад.
- [ ] **Шаг 4.** Запрос через `NOT EXISTS` (§3), javadoc репозитория переписан: признак «сейчас» снят так же, как ANO-156 сняла `deleted`, и возвращать его нельзя.
- [ ] **Шаг 5.** Прогон — зелёный. Мутации строк 1–3 таблицы §6, каждая красная.
- [ ] **Шаг 6.** Коммит.

### Задача 2. Сервис пишет историю

**Файлы:**

* изменить `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` — зависимость, `create`, `update`, `recordAccountLink`;
* изменить `backend/src/test/java/ru/selfin/backend/service/TargetFundAccountTest.java` — конструктор;
* тест `backend/src/test/java/ru/selfin/backend/FundMoneyFlowIT.java`.

- [ ] **Шаг 1.** Тесты в `FundMoneyFlowIT`, через API:
  * `link_doesNotRewritePastCapital` — `contributeOn(past)`, привязка через `PUT`: `cashLiquidAt(past)` не изменился, `cashLiquidAt(today)` меньше на 20 000, как и раньше;
  * `unlink_doesNotRewritePeriodOnAccount` — взнос два месяца назад, привязка через `PUT`, открытая строка сдвигается на месяц назад через SQL (API ставит только сегодняшнюю дату — как `contributeOn` для движений), отвязка через `PUT`: `cashLiquidAt(monthAgo)` не изменился, `cashLiquidAt(today)` больше на 20 000;
  * `relink_toAnotherAccount_closesAndOpens` — второй отслеживаемый счёт через SQL; `PUT` на первый, `PUT` на второй: 200, две строки, первая закрыта сегодня, вторая открыта;
  * `createOnAccount_recordsLinkFromToday` — `POST` со счётом: одна открытая строка с сегодняшнего дня.

  `resetMoneyState` чистит историю вместе с копилками.
- [ ] **Шаг 2.** Прогон — красный: сервис историю не пишет, привязка через API снова переписывает прошлое.
- [ ] **Шаг 3.** Реализация:

```java
private void recordAccountLink(UUID fundId, UUID oldAccountId, UUID newAccountId) {
    if (Objects.equals(oldAccountId, newAccountId)) return;
    LocalDate today = LocalDate.now(clock);
    linkRepository.findByFundIdAndLinkedToIsNull(fundId).ifPresent(open -> {
        open.setLinkedTo(today);
        // Hibernate сбрасывает вставки раньше обновлений: без flush новая открытая строка
        // встретила бы старую в uq_fund_account_links_open.
        linkRepository.saveAndFlush(open);
    });
    if (newAccountId != null) {
        linkRepository.save(FundAccountLink.builder()
                .fundId(fundId).accountId(newAccountId).linkedFrom(today).build());
    }
}
```

  В `update`: `UUID newAccountId = validateAccountLink(...)`, затем `recordAccountLink(fund.getId(), fund.getAccountId(), newAccountId)`, затем `fund.setAccountId(newAccountId)`. В `create`: после `save` — `recordAccountLink(saved.getId(), null, saved.getAccountId())`.
- [ ] **Шаг 4.** `TargetFundAccountTest`: мок `FundAccountLinkRepository` в конструкторе. Прогон `FundMoneyFlowIT` и `TargetFundAccountTest` — зелёный. Мутации строк 4–7 таблицы §6.
- [ ] **Шаг 5.** Коммит.

### Задача 3. Перенос старых привязок

**Файлы:** создать `backend/src/test/java/ru/selfin/backend/FundLinkMigrationIT.java`.

Миграцию её собственным прогоном не проверить: на Testcontainers `V26` наступает при пустых таблицах. Поэтому, как в `DisposedFundMigrationIT`, тест применяет INSERT миграции дословно к данным в легаси-состоянии.

- [ ] **Шаг 1.** Тест `legacyLinks_keepOldNumbers`: живая и удалённая копилки со счётом, `created_at` три месяца назад, по взносу 20 000 два месяца назад, строк истории нет.
  * До переноса — сумма сегодня 40 000: без истории деньги копилок на счёте посчитались бы.
  * После INSERT миграции — ноль и сегодня, и два месяца назад, как давал старый запрос.
- [ ] **Шаг 2.** Прогон — зелёный. Мутации: `linked_from = CURRENT_DATE` — два месяца назад 40 000, красный; `AND NOT f.is_deleted` — сегодня 20 000, красный.
- [ ] **Шаг 3.** Коммит.

### Задача 4. Тексты вокруг

- [ ] `CapitalService` — javadoc класса и `cashLiquidAt`: «копилки без account_id» → «копилки, не жившие на счёте в тот день».
- [ ] `TargetFundService.update` — комментарий у записи истории.
- [ ] `docs/superpowers/specs/2026-08-12-accounts-skeleton-design.md` §4.4 — поправка с датой и ссылкой на эту спеку, как сделано для §8 по ANO-158.
- [ ] Коммит.

### Задача 5. Проверка

- [ ] Затронутые классы целиком: `FundTransactionRepositoryIT`, `FundMoneyFlowIT`, `FundLinkMigrationIT`, `DisposedFundMigrationIT`, `TargetFundAccountTest`, `CapitalControllerIT`, `StrategyTimelineControllerIT`. Полный набор — CI.
- [ ] Стенд `docker-compose.test.yml`, сценарий §6 до правки (сборка `main`) и после (сборка ветки). Стенд вернуть в исходное состояние.
- [ ] Раздел «Выполнено» ниже.

### Задача 6. PR

- [ ] PR с историей: что было, почему так, замер, мутации, что не закрыто. Сигнал владельцу; вливание — после его «ок». Linear — после вливания.

---

# Выполнено
