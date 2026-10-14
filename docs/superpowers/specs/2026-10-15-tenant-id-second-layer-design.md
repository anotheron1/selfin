# Второй слой изоляции: `@TenantId`, ссылки по владельцу, тест в обход RLS (ANO-147)

Шаг 8 спринта 5 (`2026-10-11-sprint-5-plan.md`, раздел 3), решение владельца 14.10. Спека и план — одним документом. Факультатив — блок 8, вне git; стержень — артефакт «Hibernate без магии», части 16, 17 и 22.

Задача — второй слой изоляции под RLS: такой, что держит изоляцию сам, когда RLS не действует — приложение под ролью в обход RLS, забытая политика, — и проверяется отдельно от RLS.

## Что есть сейчас

* **Первый слой — RLS** (#151, `V32`): политика `owner_rows` на 11 таблицах, приложение — ролью `selfin_app`, владелец — на каждой выдаче соединения (`OwnerContext` → `selfin.user_id`).
* **Hibernate владельца не знает.** Сущности `user_id` не отображают; владельца при вставке ставит база — значением по умолчанию `COALESCE(current_owner(), синтетический)`, переходом «до шага 8» (`V31`, `V32`).
* **Запросы:** 11 репозиториев, 59 объявленных методов; из них 33 `@Query`, одно массовое обновление (`@Modifying`), одна блокировка (`@Lock`) и один нативный запрос — `CapitalRevaluationRepository.snapshotAt`. Плюс унаследованные методы `JpaRepository`: `findById`, `save`, `deleteAll` и прочие. Мимо Hibernate — только `OwnerService`: `JdbcTemplate` по таблице `users`.
* **Ссылок между таблицами с владельцем — 17:** 7 связей `@ManyToOne` и 10 полей-UUID; у каждой — внешний ключ на одну колонку `id`. Проверка внешнего ключа идёт в обход RLS: `targetFundId` перевода в копилку ложится со ссылкой на чужую копилку — замер 14.10 в ANO-147.
* **Тест на двух людей (#152) пропуска фильтра в приложении не видит**, пока RLS включён: база отфильтрует сама.

## Проверка на одной сущности — 15.10

План шага 8, п. 1: сначала проверить `@TenantId` на нашем коде; не ляжет — делать руками. `@TenantId` — на `Category`, резолвер — `OwnerContext`. RLS на `categories` выключен, поэтому чужую строку прятать может только Hibernate. Чужая — категория второго человека, вставлена ролью владельца таблиц; своё событие ссылается на неё.

| что | итог |
|---|---|
| производные методы — `findAllByDeletedFalse`, `findByName` | чужой нет |
| JPQL с подзапросом по той же сущности | фильтр и в корне, и в подзапросе |
| массовое `UPDATE` | изменены только свои строки, чужая не тронута |
| `findById` по чужому id | пусто: фильтр действует и на загрузку по ключу |
| ленивая связь своего события на чужую категорию; `getReferenceById` | `EntityNotFoundException`: «строки с таким id нет» |
| `JOIN FETCH` своего события с чужой категорией | событие не вернулось: фильтр и на присоединённой сущности |
| вставка | `user_id` поставил Hibernate — владелец потока |
| вставка с чужим `userId` | отказ: `PropertyValueException`, «assigned tenant id differs» |
| нативный запрос | чужая видна — фильтр Hibernate его не касается, как и ожидалось |
| без владельца, резолвер отдаёт `null` | **приложение не стартует:** Spring Data проверяет запросы при старте, открывая сессию без владельца, — `HibernateException`, «no tenant identifier specified» |
| без владельца, резолвер отдаёт «никого» | пусто |
| запросы без входа — `GET /version`; `POST` без CSRF, ответ через `/error` | 200 и 403, как прежде |

**Ложится.** Руками не делаем.

Поведение сверено и по исходникам Hibernate 7.2.4 нашей сборки: фильтр `_tenantId` включает сама сессия при открытии, если у неё есть арендатор, а без арендатора бросает исключение (`AbstractSharedSessionContract.setUpMultitenancy`); `TenantIdGeneration` при вставке ставит арендатора сессии и отвергает другого.

## Решение

### 1. `@TenantId` на 11 сущностях

У каждой сущности — поле владельца:

```java
@TenantId
@Column(name = "user_id", nullable = false, updatable = false)
private UUID userId;
```

Hibernate сам добавляет `user_id = владелец` ко всем запросам сущности — производным, JPQL, массовым, загрузке по id и ленивым связям — и сам ставит владельца при вставке. Ни один метод репозитория не меняется.

### 2. Арендатор — `OwnerContext`, без владельца — «никто»

Резолвер (`config/OwnerTenantConfig`) отдаёт владельца потока, а без него — `00000000-0000-0000-0000-000000000000`, «никого».

* `null` нельзя: приложение не стартует (проверка выше). И сессию без владельца открывает каждый веб-запрос без входа — open-in-view открывает её до контроллера, — так что `/version` и `/error` упали бы тоже.
* «Никто» — та же семантика, что у RLS: нет владельца — нет строк. Запись «никому» упрётся во внешний ключ на `users` и в `WITH CHECK` политики.

Арендатор фиксируется при открытии сессии. В веб-запросе её открывает open-in-view в `DispatcherServlet` — после `OwnerFilter`, который владельца уже поставил. В тестах поток — от синтетического владельца (`SyntheticOwnerListener`), сессия — на вызов репозитория или транзакцию.

Корневого арендатора (`isRoot`) нет: «видеть всё» приложению не нужно — миграции ходят ролью владельца таблиц мимо Hibernate.

### 3. Нативный `snapshotAt` — владелец явно

Нативный SQL фильтр Hibernate не получает. Владелец пишется в самом запросе, в обеих таблицах: `r.user_id = current_owner() AND i.user_id = current_owner()`. `current_owner()` — владелец соединения: его ставит пул приложения (`DatabaseRolesConfig`), от политик он не зависит и под ролью в обход RLS работает так же. Иначе прогон в обход RLS сложил бы предметы обоих людей — ревью Codex на #154.

Параметр из Java дал бы того же владельца ценой правки сигнатуры и вызовов; `current_owner()` — условие политики, записанное явно.

### 4. Ссылки — составные внешние ключи `(user_id, ссылка) → (user_id, id)`

Миграция `V33`. У шести таблиц-родителей — уникальность `(user_id, id)`: составной ключ ссылается только на уникальное. Каждая из 17 ссылок — внешним ключом на пару, и ссылка на строку другого человека невозможна для любого кода: сервиса, миграции, нативного SQL.

* Одиночные ключи сняты — составной их покрывает. Ищутся по смыслу, как в `V31`: у базы владельца имена от Hibernate.
* Действия при удалении — прежние. `ON DELETE SET NULL` — только колонке ссылки (`SET NULL (колонка)`, Postgres 15): иначе база обнулила бы и `user_id`.
* Индексы `idx_*_owner` у четырёх родителей сняты: новая уникальность начинается с `user_id` и служит тем же индексом. У `categories` и `financial_events` своих одиночных индексов по владельцу нет.

`targetFundId` с чужой копилкой после этого падает на ключе: ответ 409, «конфликт с ограничением данных», как у прочих нарушений схемы. 404, как у остальных ссылок в теле, дала бы проверка в сервисе — её можно забыть в новом пути записи, ключ — нет.

### 5. Значение по умолчанию у `user_id` — владелец соединения, без синтетического

Было `COALESCE(current_owner(), синтетический)` — переход: код записи владельца не знал. Теперь его ставит Hibernate, а синтетический запасной стал ловушкой: строка, вставленная ролью владельца таблиц без владельца — будущей миграцией данных, — молча досталась бы синтетическому владельцу, то есть хозяину нынешних данных.

| | что | цена |
|---|---|---|
| А | снять значение совсем | около 60 вставок в 21 IT указывают владельца руками |
| **Б** | **`current_owner()` без синтетического** | вставка без владельца сеанса — отказ `NOT NULL`; IT — владелец потока на соединении |
| В | оставить | ловушка остаётся |

**Б.** Роль приложения пишет как прежде, миграции без владельца падают громко. Вставки IT ролью владельца таблиц получают владельца потока теста: `JdbcTemplate` тестов (`testsupport/DataAsTableOwner`) ставит его на соединение тем же `OwnerAwareDataSource`, что и пул приложения.

### 6. Каждый метод поимённо

Правило приёмки ANO-147: каждый метод — под фильтром Hibernate, с владельцем в запросе явно или в списке «только RLS».

| репозиторий | методы | как |
|---|---|---|
| `AccountRepository` (4) | `findAllByDeletedFalseOrderBySortOrderAscNameAsc`, `findByDefaultAccountTrueAndDeletedFalse`, `findAllActiveWithPurpose`, `findAllByPurposeCategoryIdAndDeletedFalse` | фильтр |
| `BalanceCheckpointRepository` (5) | `findTopByOrderByDateAsc`, `findAllByOrderByDateDesc`, `existsByAccountId`, `findEarliestCheckpointDate`, `findLatestForAccountAt` | фильтр |
| `CapitalItemRepository` (2) | `findActiveById`, `findAllActive` | фильтр |
| `CapitalRevaluationRepository` (4) | `findActiveById`, `findHistoryByItemId`, `findEarliestValuedAt` | фильтр |
| | `snapshotAt` — нативный | владелец явно |
| `CategoryRepository` (5) | `findAllByDeletedFalse`, `findAllByDeletedFalseAndType`, `findByName`, `findAllByForecastEnabledTrueAndDeletedFalse`, `existsByPrimaryIncomeTrueAndDeletedFalse` | фильтр |
| `FinancialEventRepository` (27) | `findAllByDeletedFalseAndDateBetweenOrderByDateAscCreatedAtAscIdAsc`, `existsByCategoryIdAndDeletedFalse`, `findAllByDeletedFalseAndDateBetween`, `findByIdempotencyKey`, `findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc`, `findAllByDeletedFalseAndStatusNot`, `findFactAggregatesByPlanIds`, `findFactsByDateRange`, `findFirstSpendingDate`, `findSpendingByDateRange`, `sumOverdueMandatoryExpenses`, `findPlannedEventsByDateRange`, `findEarliestFactDate`, `findMaxActiveDateByRule`, `softDeletePlanEventsByRuleFromDate` (массовое), `findExecutedDatesByRule`, `findMaxExecutedDateByRule`, `findActiveByRuleAndDate`, `findAllWishlistEvents`, `findByWishlistStatusAndDeletedFalse`, `findOverdueMandatoryExpenses`, `existsByDeletedFalseAndFactAmountNotNullAndWishlistStatusIsNullAndDateLessThanEqual`, `findPlannedIncomeDates`, `findByWishlistStatusInAndDeletedFalse`, `findCreditPaymentRuleIds`, `findFirstByRecurringRuleIdAndDeletedFalseAndStatusAndDateGreaterThanEqualOrderByDateAsc`, `findCreditPaymentLinks` | фильтр |
| `FundAccountLinkRepository` (1) | `findByFundIdAndLinkedToIsNull` | фильтр |
| `FundTransactionRepository` (5) | `findByIdempotencyKey`, `existsByIdempotencyKey`, `findByFundIdAndDeletedFalse`, `sumEnvelopeFundsByTransactionDateLessThanEqual`, `sumLiveByFundId` | фильтр |
| `RecurringRuleRepository` (2) | `findIndefiniteActiveIds`, `findForUpdate` (блокировка) | фильтр |
| `TargetFundRepository` (3) | `findAllByDeletedFalseOrderByPriorityAsc`, `findByWishlistStatusAndDeletedFalse`, `findAllWishlistFunds` | фильтр |
| `UserSettingsRepository` (1) | `findBySettingsKey` | фильтр |
| унаследованные `JpaRepository` | `findById`, `findAll`, `save`, `delete*`, `count`, `existsById`, `getReferenceById` | фильтр, загрузка по ключу — тоже |

**«Только RLS»** — `OwnerService`: `JdbcTemplate` по таблице `users`. Владельца у неё нет, RLS тоже: в ней сервер ищет владельца по `sub`, ещё его не зная.

Новый нативный запрос без владельца список не пополнит молча: сторож требует `current_owner()` в каждом нативном `@Query`.

### 7. Второй слой проверяется отдельно

`TwoPeopleIsolationIT` — второй тест: тот же сценарий и тот же оракул, но RLS на 11 таблицах выключен (`DISABLE ROW LEVEL SECURITY` ролью владельца таблиц на время теста). Зелёный — изоляцию держат `@TenantId`, владелец в `snapshotAt` и составные ключи сами. Люди у тестов — свои.

План говорил «пулом в обход RLS». Выключенный RLS даёт то же, но всё остальное — пул, роль, владелец на соединении — остаётся как в первом тесте; пул суперпользователем потребовал бы второго контекста со своей базой.

В обоих тестах — удары записью **со ссылкой на строку другого человека в теле**: `categoryId` плана и факта, `purposeCategoryId` счёта, `accountId` отметки остатка и копилки, `targetFundId` перевода. Пять — 404: сервис ищет ссылку через `findById`. `targetFundId` — 409: ключ.

`SecondLayerIT` — механизм и схема второго слоя, двойник `RowLevelSecurityIT`:

* у каждой таблицы с `user_id` — сущность с `@TenantId`, и наоборот;
* каждая ссылка между таблицами с владельцем — составным ключом с `user_id`;
* каждый нативный `@Query` пишет владельца — `current_owner()`;
* без владельца — ни строки, запись — отказ; запись с чужим владельцем — отказ (RLS выключен — смотрит только Hibernate);
* вставка ролью владельца таблиц без владельца сеанса — отказ `NOT NULL`.

## Как проверяется

| что | проверка | мутация |
|---|---|---|
| сущность под фильтром | второй тест `TwoPeopleIsolationIT`; сторож сущностей | нет `@TenantId` — по каждой из 11 |
| нативный запрос с владельцем | второй тест: сводка и траектория капитала; сторож нативных | `snapshotAt` без `current_owner()` |
| без владельца — «никто» | старт приложения; `SecondLayerIT` | резолвер отдаёт `null`; резолвер отдаёт синтетического |
| фильтр включён | второй тест | резолвер: «никто» — корень, фильтр выключен |
| ссылка только на своё | удар `targetFundId`; сторож ключей | одиночный ключ у `target_fund_id` |
| без синтетического по умолчанию | `SecondLayerIT` | значение с синтетическим запасным |
| тест не холостой | второй тест на коде до правки | CONTROL — красный |

## План

1. Тест: второй режим `TwoPeopleIsolationIT` и удары ссылками в теле. На коде до правки — красный.
2. `@TenantId` на 11 сущностях, резолвер, `snapshotAt` — второй режим зелёный, кроме `targetFundId`.
3. `V33`: составные ключи, уникальности родителей, значение по умолчанию; `DataAsTableOwner` — владелец потока на соединении. `targetFundId` — 409.
4. `SecondLayerIT`.
5. Полный прогон; мутации на итоговом коде.
6. Стенд до и после: `V33` на копии, числа копии, живая проверка, удар `targetFundId` вторым человеком.
7. PR, блок 8.

## Не входит

* **ANO-229** — приложение не стартует под ролью в обход RLS: шаг 8а, своя задача.
* **ANO-150** — закрыть после этого шага комментарием: поиск системных категорий — под фильтром. Строковые признаки ANO-5 — отдельно.
* **Open-in-view, `@Version`, время в моделях** — свои темы, артефакт, часть 22.
* **Фоновые работы** без запроса (бот, уведомления) ставят `OwnerContext` сами — забота тех задач (план, шаг 7).

## Правила продукта — сверка

Канон `2026-09-01-product-rules.md`, по каждому правилу, 15.10:

| правило | как соблюдено |
|---|---|
| 1 категорийные лимиты | не касается |
| 2 план-факт по умолчанию | не касается |
| 3 не продаём точность | не касается |
| 4 пейволл | не касается |
| 5 без требования правильности | не касается: экранов не меняет |
| 6 ввод редкий, вывод частый | не касается |
| 7 уведомления | не касается |
| 8 breakdown предложениями | не касается |
| 9 быстрый ввод под кассу | не касается |
| 10 не обвинять конкурентов | не касается |
| 11 не потянуться за кредиткой | не касается |
| 12 без упрёков | не касается: своих текстов нет; 409 на чужую копилку — ответ API, экран его не показывает — фронт шлёт только свои id |
| 13 слова пользователей | не касается |
| данные респондентов | не касается |

## Характер плановой строки

Не касается.

## Как мерил

* **Проверка на одной сущности** — IT в ветке (`TenantIdSpikeIT`, в PR не входит): `@TenantId` на `Category`, RLS на `categories` выключен ролью владельца таблиц, чужая строка — вставкой той же ролью; шаги — через репозитории, `EntityManager` в транзакции и HTTP к поднятому серверу без входа.
* **Механизм** — исходники `hibernate-core-7.2.4.Final-sources.jar` из локального Maven: `TenantIdBinder`, `TenantIdGeneration`, `AbstractSharedSessionContract`, `MultiTenancy`.
* **Методы репозиториев** — разбор исходников `repository/*Repository.java` скриптом: объявления методов без ключевых слов SQL и геттеров проекций — 59.
* **Ссылки** — `REFERENCES` и `FOREIGN KEY` в миграциях `V1`–`V26`: 17, у каждой — внешний ключ на одну колонку.
* **Вставки IT без владельца** — `INSERT INTO` одиннадцати таблиц в `src/test`: 21 файл, около 60 вставок, `user_id` — в пяти.
