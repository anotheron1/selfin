# Capital / Net Worth Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать модуль «Капитал» (net worth): отдельная страница `/capital`, где пользователь видит свой капитал = ликвидные деньги + материальные активы − обязательства, с историей и графиком траектории.

**Architecture:** Две новые таблицы (`capital_items` + `capital_revaluations`) — единая модель для активов и обязательств с дискриминатором `kind`. Стоимость item'а на дату = последняя переоценка с `valued_at ≤ дата`. Жидкая часть переиспользует существующие сущности (`BalanceCheckpoint`, `FinancialEvent`, `FundTransaction`). Frontend — отдельный маршрут с hero-сводкой, двумя колонками, Sheet-панелью и графиком на recharts.

**Tech Stack:** Spring Boot 4.0.3 / Java 21 / PostgreSQL 15 / Flyway / JUnit 5 + Mockito + Testcontainers / React 18 / TypeScript / Vite / shadcn UI (Sheet, Dialog, Button) / recharts / Tailwind.

**Spec:** [docs/superpowers/specs/2026-05-10-capital-net-worth-design.md](../specs/2026-05-10-capital-net-worth-design.md) (commit `d39f245`)

---

## Глоссарий путей

- Backend root: `backend/src/main/java/ru/selfin/backend/`
- Backend tests: `backend/src/test/java/ru/selfin/backend/`
- Migrations: `backend/src/main/resources/db/migration/`
- Frontend root: `frontend/src/`

---

## Chunk 1: Backend — миграция, сущности, репозитории

**Цель чанка:** дать рабочую схему БД и слой доступа к данным с покрытым тестом «получить срез значений всех item'ов на дату».

**Files to create:**
- `backend/src/main/resources/db/migration/V17__add_capital_items.sql`
- `backend/src/main/java/ru/selfin/backend/model/enums/CapitalItemKind.java`
- `backend/src/main/java/ru/selfin/backend/model/CapitalItem.java`
- `backend/src/main/java/ru/selfin/backend/model/CapitalRevaluation.java`
- `backend/src/main/java/ru/selfin/backend/repository/CapitalItemRepository.java`
- `backend/src/main/java/ru/selfin/backend/repository/CapitalRevaluationRepository.java`
- `backend/src/main/java/ru/selfin/backend/repository/CapitalSnapshotProjection.java` (interface-projection для query «срез на дату»)
- `backend/src/test/java/ru/selfin/backend/repository/CapitalRevaluationRepositoryIT.java`

### Task 1: Миграция Flyway V17

**Файл:** `backend/src/main/resources/db/migration/V17__add_capital_items.sql`

- [ ] **Step 1: Создать файл миграции**

```sql
-- V17: модуль «Капитал» — единицы капитала (активы / обязательства) и журнал переоценок.
-- Одна таблица для активов и обязательств различимы дискриминатором kind.

CREATE TABLE capital_items (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY')),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE capital_revaluations (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id    UUID           NOT NULL REFERENCES capital_items(id),
    value      NUMERIC(19, 2) NOT NULL CHECK (value >= 0),
    valued_at  DATE           NOT NULL,
    note       TEXT,
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN        NOT NULL DEFAULT FALSE
);

-- Партиционные индексы — поиск по живым записям.
CREATE INDEX idx_capital_items_active
    ON capital_items (kind)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_capital_revaluations_item_at
    ON capital_revaluations (item_id, valued_at DESC, created_at DESC)
    WHERE is_deleted = FALSE;
```

- [ ] **Step 2: Запустить тесты, чтобы убедиться что Flyway применяет миграцию без ошибок**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=BackendApplicationTests`

Ожидаемый результат: PASS (приложение поднимается с применённой миграцией).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V17__add_capital_items.sql
git commit -m "feat(capital): V17 migration — capital_items and capital_revaluations"
```

### Task 2: Enum `CapitalItemKind`

**Файл:** `backend/src/main/java/ru/selfin/backend/model/enums/CapitalItemKind.java`

- [ ] **Step 1: Создать enum**

```java
package ru.selfin.backend.model.enums;

public enum CapitalItemKind {
    ASSET,
    LIABILITY
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/enums/CapitalItemKind.java
git commit -m "feat(capital): CapitalItemKind enum"
```

### Task 3: Сущность `CapitalItem`

**Файл:** `backend/src/main/java/ru/selfin/backend/model/CapitalItem.java`

Следуем паттерну `RecurringRule.java`: `@Entity @Table(name="...") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. Soft-delete: колонка БД `is_deleted`, Java-поле `deleted` (Lombok `@Builder.Default`).

- [ ] **Step 1: Создать сущность**

```java
package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Единица капитала — актив или обязательство.
 * <p>Симметричная сущность: дискриминатор {@code kind} различает активы (вклад в капитал со знаком +)
 * и обязательства (со знаком −). История стоимости — в {@link CapitalRevaluation}.
 *
 * <p>Soft-delete: {@code deleted = true} (колонка {@code is_deleted}) скрывает item из всех расчётов
 * целиком — это «удалить безвозвратно, ошибся при создании». Для «продал актив / закрыл кредит»
 * добавляется переоценка с {@code value=0}, item остаётся, но уходит в архивные.
 */
@Entity
@Table(name = "capital_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapitalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CapitalItemKind kind;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

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
}
```

- [ ] **Step 2: Запустить тесты (проверка маппинга через старт контекста)**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=BackendApplicationTests`

Ожидаемый результат: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/CapitalItem.java
git commit -m "feat(capital): CapitalItem entity"
```

### Task 4: Сущность `CapitalRevaluation`

**Файл:** `backend/src/main/java/ru/selfin/backend/model/CapitalRevaluation.java`

- [ ] **Step 1: Создать сущность**

```java
package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Запись в журнале переоценок единицы капитала.
 * <p>Стоимость item'а на дату {@code t} = последняя живая переоценка с {@code valued_at ≤ t}
 * (порядок tie-break: {@code valued_at DESC, created_at DESC}). Если ни одной такой записи нет —
 * вклад item'а в капитал = 0 (item «не существовал» до своей первой переоценки).
 *
 * <p>{@code value ≥ 0} всегда: знак применяется в формуле капитала по {@code item.kind}.
 *
 * <p>Soft-delete нужен для «удалить ошибочную запись» — без потери остального журнала.
 */
@Entity
@Table(name = "capital_revaluations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapitalRevaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal value;

    @Column(name = "valued_at", nullable = false)
    private LocalDate valuedAt;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
```

**Замечание:** `itemId` хранится как `UUID`, не как `@ManyToOne CapitalItem`. Это сознательно: мы делаем массовый запрос «срез значений по item'ам на дату» через нативный SQL без JOIN, и `@ManyToOne` тут только мешал бы.

- [ ] **Step 2: Запустить тесты**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=BackendApplicationTests`

Ожидаемый результат: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/CapitalRevaluation.java
git commit -m "feat(capital): CapitalRevaluation entity"
```

### Task 5: Репозиторий `CapitalItemRepository`

**Файл:** `backend/src/main/java/ru/selfin/backend/repository/CapitalItemRepository.java`

- [ ] **Step 1: Создать интерфейс**

```java
package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapitalItemRepository extends JpaRepository<CapitalItem, UUID> {

    /** Найти живой item по id (с фильтром {@code deleted=false}). */
    @Query("SELECT i FROM CapitalItem i WHERE i.id = :id AND i.deleted = false")
    Optional<CapitalItem> findActiveById(@Param("id") UUID id);

    /** Все живые item'ы, опционально отфильтрованные по типу. */
    @Query("""
            SELECT i FROM CapitalItem i
            WHERE i.deleted = false
              AND (:kind IS NULL OR i.kind = :kind)
            ORDER BY i.createdAt ASC
            """)
    List<CapitalItem> findAllActive(@Param("kind") CapitalItemKind kind);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/CapitalItemRepository.java
git commit -m "feat(capital): CapitalItemRepository"
```

### Task 6: Проекция `CapitalSnapshotProjection`

**Файл:** `backend/src/main/java/ru/selfin/backend/repository/CapitalSnapshotProjection.java`

Spring Data interface-projection для нативного запроса «срез значений всех живых item'ов на дату» (используется в траектории).

- [ ] **Step 1: Создать интерфейс**

```java
package ru.selfin.backend.repository;

import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Проекция «срез item'а на дату»: для каждого живого item'а — его стоимость,
 * полученная как последняя живая переоценка с {@code valued_at ≤ asOfDate}.
 * <p>Если переоценок нет — item в результат не попадает (внешний код считает вклад = 0).
 */
public interface CapitalSnapshotProjection {
    UUID getItemId();
    CapitalItemKind getKind();
    BigDecimal getValue();
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/CapitalSnapshotProjection.java
git commit -m "feat(capital): CapitalSnapshotProjection interface"
```

### Task 7: Репозиторий `CapitalRevaluationRepository`

**Файл:** `backend/src/main/java/ru/selfin/backend/repository/CapitalRevaluationRepository.java`

Содержит:
- CRUD-обёртка `JpaRepository<CapitalRevaluation, UUID>` — даёт saves/finds.
- Метод `findHistoryByItemId` — лента переоценок одного item'а для UI Sheet.
- Метод `snapshotAt(asOfDate)` — нативный SQL для расчёта траектории; одна query, без N+1.

**Замечание по TDD-порядку:** интерфейс репозитория пишем первым (Step 1), тесты на нативный запрос — в Task 8 после интерфейса. Это формально нарушает «test-first», но без интерфейса тест не компилируется. В тестах Task 8 жёстко проверяем семантику `snapshotAt` — это и есть TDD-страховка.

- [ ] **Step 1: Создать репозиторий**

```java
package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.CapitalRevaluation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapitalRevaluationRepository extends JpaRepository<CapitalRevaluation, UUID> {

    /** Найти живую переоценку по id. */
    @Query("SELECT r FROM CapitalRevaluation r WHERE r.id = :id AND r.deleted = false")
    Optional<CapitalRevaluation> findActiveById(@Param("id") UUID id);

    /** История переоценок одного item'а: новейшие сверху, soft-deleted скрыты. */
    @Query("""
            SELECT r FROM CapitalRevaluation r
            WHERE r.itemId = :itemId
              AND r.deleted = false
            ORDER BY r.valuedAt DESC, r.createdAt DESC
            """)
    List<CapitalRevaluation> findHistoryByItemId(@Param("itemId") UUID itemId);

    /**
     * Срез значений всех живых item'ов на дату {@code asOfDate}.
     * Для каждого item'а возвращает последнюю живую переоценку с {@code valued_at ≤ asOfDate}
     * (tie-break по {@code created_at DESC}). Item'ы без подходящей переоценки в результат не попадают.
     *
     * <p>Реализация — один SQL с {@code DISTINCT ON} (PostgreSQL). Не делать N+1.
     */
    @Query(value = """
            SELECT DISTINCT ON (r.item_id)
                   r.item_id  AS itemId,
                   i.kind     AS kind,
                   r.value    AS value
            FROM capital_revaluations r
            JOIN capital_items i ON i.id = r.item_id
            WHERE r.is_deleted = false
              AND i.is_deleted = false
              AND r.valued_at <= :asOfDate
            ORDER BY r.item_id, r.valued_at DESC, r.created_at DESC
            """, nativeQuery = true)
    List<CapitalSnapshotProjection> snapshotAt(@Param("asOfDate") LocalDate asOfDate);

    /** Самая ранняя дата переоценки (для дефолтного {@code from} в траектории). */
    @Query("SELECT MIN(r.valuedAt) FROM CapitalRevaluation r WHERE r.deleted = false")
    Optional<LocalDate> findEarliestValuedAt();
}
```

**Замечание про `DISTINCT ON`:** это специфика PostgreSQL — для каждого `item_id` берём первую строку после `ORDER BY r.item_id, r.valued_at DESC, r.created_at DESC`. Эквивалент: `ROW_NUMBER() OVER (PARTITION BY ...) = 1`.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/CapitalRevaluationRepository.java
git commit -m "feat(capital): CapitalRevaluationRepository with snapshotAt query"
```

### Task 8: Интеграционный тест репозитория

**Файл:** `backend/src/test/java/ru/selfin/backend/repository/CapitalRevaluationRepositoryIT.java`

Покрывает критичный нативный запрос `snapshotAt` — самую сложную часть слоя данных. Использует Testcontainers Postgres 15 (как `BalanceCheckpointControllerIT`).

- [ ] **Step 1: Написать тест**

```java
package ru.selfin.backend.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.CapitalRevaluation;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class CapitalRevaluationRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired CapitalItemRepository itemRepo;
    @Autowired CapitalRevaluationRepository revRepo;

    // @SpringBootTest reuses the DB across tests in this class. Tests like
    // findEarliestValuedAt_emptyDb_returnsEmpty assume an empty table — clean
    // explicitly between methods.
    @AfterEach
    void cleanDb() {
        revRepo.deleteAll();
        itemRepo.deleteAll();
    }

    @Test
    void snapshotAt_returnsLatestRevaluationForEachActiveItem() {
        CapitalItem flat = saveItem(CapitalItemKind.ASSET, "Квартира");
        CapitalItem car  = saveItem(CapitalItemKind.ASSET, "Volvo");
        CapitalItem mort = saveItem(CapitalItemKind.LIABILITY, "Ипотека");

        // Квартира: 8.0M (1 янв) → 8.5M (1 апр)
        saveRev(flat.getId(), bd("8000000"), date(2026, 1, 1));
        saveRev(flat.getId(), bd("8500000"), date(2026, 4, 1));
        // Volvo: 1.4M (1 янв) → 1.2M (1 мар)
        saveRev(car.getId(),  bd("1400000"), date(2026, 1, 1));
        saveRev(car.getId(),  bd("1200000"), date(2026, 3, 1));
        // Ипотека: 5.0M (1 янв) → 4.8M (1 мар)
        saveRev(mort.getId(), bd("5000000"), date(2026, 1, 1));
        saveRev(mort.getId(), bd("4800000"), date(2026, 3, 1));

        // Срез на 2 марта: квартира=8.0M (jan), volvo=1.2M (mar), ипотека=4.8M (mar)
        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 3, 2));
        Map<UUID, BigDecimal> byId = snapshot.stream()
                .collect(Collectors.toMap(CapitalSnapshotProjection::getItemId, CapitalSnapshotProjection::getValue));

        assertThat(byId.get(flat.getId())).isEqualByComparingTo("8000000");
        assertThat(byId.get(car.getId())).isEqualByComparingTo("1200000");
        assertThat(byId.get(mort.getId())).isEqualByComparingTo("4800000");
    }

    @Test
    void snapshotAt_excludesItemsWithoutAnyRevaluationOnOrBeforeDate() {
        CapitalItem newCar = saveItem(CapitalItemKind.ASSET, "Новая машина");
        saveRev(newCar.getId(), bd("2000000"), date(2026, 5, 1));

        // На 1 апреля item ещё не существовал — его нет в срезе.
        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 4, 1));

        assertThat(snapshot).extracting(CapitalSnapshotProjection::getItemId).doesNotContain(newCar.getId());
    }

    @Test
    void snapshotAt_excludesSoftDeletedRevaluations() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Test");
        saveRev(item.getId(), bd("1000"), date(2026, 1, 1));
        // Эта переоценка soft-deleted — должна быть проигнорирована.
        CapitalRevaluation deleted = saveRev(item.getId(), bd("9999"), date(2026, 2, 1));
        deleted.setDeleted(true);
        revRepo.save(deleted);

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 3, 1));

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getValue()).isEqualByComparingTo("1000");
    }

    @Test
    void snapshotAt_excludesSoftDeletedItems() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Test");
        saveRev(item.getId(), bd("1000"), date(2026, 1, 1));
        item.setDeleted(true);
        itemRepo.save(item);

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 3, 1));

        assertThat(snapshot).extracting(CapitalSnapshotProjection::getItemId).doesNotContain(item.getId());
    }

    @Test
    void snapshotAt_tieBreaksByCreatedAtWhenSameValuedAt() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Tie-break test");
        // Две переоценки в один день — побеждает та, что создана позже (created_at DESC).
        saveRevAt(item.getId(), bd("100"), date(2026, 1, 1), LocalDateTime.now().minusMinutes(5));
        saveRevAt(item.getId(), bd("200"), date(2026, 1, 1), LocalDateTime.now());

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 1, 1));

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getValue()).isEqualByComparingTo("200");
    }

    @Test
    void findHistoryByItemId_returnsNewestFirstAndExcludesDeleted() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Test");
        saveRev(item.getId(), bd("100"), date(2026, 1, 1));
        saveRev(item.getId(), bd("200"), date(2026, 3, 1));
        CapitalRevaluation deleted = saveRev(item.getId(), bd("999"), date(2026, 2, 1));
        deleted.setDeleted(true);
        revRepo.save(deleted);

        List<CapitalRevaluation> history = revRepo.findHistoryByItemId(item.getId());

        assertThat(history).extracting(CapitalRevaluation::getValuedAt)
                .containsExactly(date(2026, 3, 1), date(2026, 1, 1));
    }

    @Test
    void findEarliestValuedAt_emptyDb_returnsEmpty() {
        assertThat(revRepo.findEarliestValuedAt()).isEmpty();
    }

    // --- helpers ---

    private CapitalItem saveItem(CapitalItemKind kind, String name) {
        return itemRepo.save(CapitalItem.builder().kind(kind).name(name).build());
    }

    private CapitalRevaluation saveRev(UUID itemId, BigDecimal value, LocalDate at) {
        return revRepo.save(CapitalRevaluation.builder()
                .itemId(itemId).value(value).valuedAt(at).build());
    }

    private CapitalRevaluation saveRevAt(UUID itemId, BigDecimal value, LocalDate at, LocalDateTime createdAt) {
        return revRepo.save(CapitalRevaluation.builder()
                .itemId(itemId).value(value).valuedAt(at).createdAt(createdAt).build());
    }

    private static LocalDate date(int y, int m, int d) { return LocalDate.of(y, m, d); }
    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
```

- [ ] **Step 2: Запустить тест, убедиться что проходит (Docker должен быть запущен для Testcontainers)**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=CapitalRevaluationRepositoryIT`

Ожидаемый результат: 7 тестов PASS.

- [ ] **Step 3: Запустить полный backend-suite чтобы убедиться что ничего не сломалось**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test`

Ожидаемый результат: все тесты PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/ru/selfin/backend/repository/CapitalRevaluationRepositoryIT.java
git commit -m "test(capital): repository IT covering snapshotAt and history queries"
```

---

## Chunk 2: Backend — DTO, сервис, контроллер, integration tests

**Цель чанка:** реализовать API целиком — CRUD по items/revaluations, агрегаты `summary` и `trajectory`, с покрытыми тестами на бизнес-логику и валидации.

**Files to create:**
- `backend/src/main/java/ru/selfin/backend/dto/capital/` (новый под-пакет, 7 records)
- `backend/src/main/java/ru/selfin/backend/service/CapitalService.java`
- `backend/src/main/java/ru/selfin/backend/controller/CapitalController.java`
- `backend/src/test/java/ru/selfin/backend/service/CapitalServiceTest.java`
- `backend/src/test/java/ru/selfin/backend/CapitalControllerIT.java`

### Task 9: DTO records

Все DTO как Java records в новом под-пакете `dto.capital`. Bean Validation: `@NotBlank`, `@PositiveOrZero`, `@Size`, `@PastOrPresent`. Соответствие — Секция 3 спеки.

- [ ] **Step 1: Создать все 7 record-классов**

Файлы (по одному record в каждом, новый каталог `backend/src/main/java/ru/selfin/backend/dto/capital/`):

`CapitalItemCreateDto.java`:
```java
package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.*;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CapitalItemCreateDto(
        @NotNull CapitalItemKind kind,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @PositiveOrZero BigDecimal initialValue,
        @PastOrPresent LocalDate initialValuedAt
) {}
```

`CapitalItemUpdateDto.java`:
```java
package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.Size;

public record CapitalItemUpdateDto(
        @Size(min = 1, max = 255) String name,
        @Size(max = 2000) String description
) {}
```

`CapitalItemDto.java`:
```java
package ru.selfin.backend.dto.capital;

import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CapitalItemDto(
        UUID id,
        CapitalItemKind kind,
        String name,
        String description,
        Instant createdAt,
        BigDecimal currentValue,
        LocalDate lastValuedAt,
        boolean isArchived
) {}
```

`CapitalRevaluationCreateDto.java`:
```java
package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CapitalRevaluationCreateDto(
        @NotNull @PositiveOrZero BigDecimal value,
        @PastOrPresent LocalDate valuedAt,
        @Size(max = 1000) String note
) {}
```

`CapitalRevaluationUpdateDto.java`:
```java
package ru.selfin.backend.dto.capital;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CapitalRevaluationUpdateDto(
        @PositiveOrZero BigDecimal value,
        @PastOrPresent LocalDate valuedAt,
        @Size(max = 1000) String note
) {}
```

`CapitalRevaluationDto.java`:
```java
package ru.selfin.backend.dto.capital;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CapitalRevaluationDto(
        UUID id,
        UUID itemId,
        BigDecimal value,
        LocalDate valuedAt,
        String note,
        Instant createdAt
) {}
```

`CapitalSummaryDto.java`:
```java
package ru.selfin.backend.dto.capital;

import java.math.BigDecimal;
import java.util.List;

public record CapitalSummaryDto(
        BigDecimal total,
        BigDecimal liquid,
        BigDecimal assetsTotal,
        BigDecimal liabilitiesTotal,
        List<CapitalItemDto> items,
        Deltas deltas
) {
    public record Deltas(BigDecimal month, BigDecimal quarter, BigDecimal year) {}
}
```

`CapitalTrajectoryDto.java`:
```java
package ru.selfin.backend.dto.capital;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CapitalTrajectoryDto(List<Point> points) {
    public record Point(
            LocalDate date,
            BigDecimal capital,
            BigDecimal liquid,
            BigDecimal assets,
            BigDecimal liabilities
    ) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/capital/
git commit -m "feat(capital): DTOs for items, revaluations, summary, trajectory"
```

### Task 10: Сервис `CapitalService` — заготовка и CRUD

**Файл:** `backend/src/main/java/ru/selfin/backend/service/CapitalService.java`

Реализуем по TDD: сначала тесты на CRUD-методы, потом методы. Затем агрегаты (Task 11). `@Transactional(readOnly=true)` на классе, `@Transactional` на мутирующих.

Вычисление `liquidAt(date)` — приватный метод, переиспользует `BalanceCheckpointRepository`, `FinancialEventRepository`, `FundTransactionRepository`. Формула:

```
liquid(t) = checkpoint_amount_on_or_before(t)        // или 0 если нет
          + Σ INCOME факт events с date IN (after_checkpoint_date .. t]
          − Σ EXPENSE факт events с date IN (after_checkpoint_date .. t]
```

(`FUND_TRANSFER` и `FundTransaction` сокращаются — деньги в копилке всё ещё считаются, см. секцию «Жидкая часть» спеки.)

- [ ] **Step 1: Написать первый тест — `create` создаёт item с первой переоценкой одной транзакцией**

Файл `backend/src/test/java/ru/selfin/backend/service/CapitalServiceTest.java`:

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.dto.capital.*;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.CapitalRevaluation;
import ru.selfin.backend.model.enums.CapitalItemKind;
import ru.selfin.backend.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CapitalServiceTest {

    @Mock CapitalItemRepository itemRepo;
    @Mock CapitalRevaluationRepository revRepo;
    @Mock BalanceCheckpointRepository checkpointRepo;
    @Mock FinancialEventRepository eventRepo;
    @Mock FundTransactionRepository fundTxRepo;

    @InjectMocks CapitalService service;

    @Test
    void create_persistsItemAndFirstRevaluation() {
        CapitalItem saved = CapitalItem.builder()
                .kind(CapitalItemKind.ASSET).name("Квартира").build();
        saved.setId(UUID.randomUUID());

        when(itemRepo.save(any(CapitalItem.class))).thenReturn(saved);
        when(revRepo.save(any(CapitalRevaluation.class))).thenAnswer(inv -> inv.getArgument(0));

        CapitalItemDto result = service.create(new CapitalItemCreateDto(
                CapitalItemKind.ASSET, "Квартира", null,
                new BigDecimal("8500000"), null));

        assertThat(result.kind()).isEqualTo(CapitalItemKind.ASSET);
        assertThat(result.name()).isEqualTo("Квартира");
        assertThat(result.currentValue()).isEqualByComparingTo("8500000");
        assertThat(result.isArchived()).isFalse();
        verify(itemRepo).save(any());
        verify(revRepo).save(any());
    }

    @Test
    void create_usesProvidedValuedAt_orFallsBackToToday() {
        CapitalItem saved = CapitalItem.builder()
                .kind(CapitalItemKind.LIABILITY).name("Ипотека").build();
        saved.setId(UUID.randomUUID());

        when(itemRepo.save(any())).thenReturn(saved);
        when(revRepo.save(any(CapitalRevaluation.class))).thenAnswer(inv -> inv.getArgument(0));

        CapitalItemDto result = service.create(new CapitalItemCreateDto(
                CapitalItemKind.LIABILITY, "Ипотека", null,
                new BigDecimal("4800000"), LocalDate.of(2026, 1, 1)));

        assertThat(result.lastValuedAt()).isEqualTo(LocalDate.of(2026, 1, 1));
    }
}
```

- [ ] **Step 2: Запустить тест — должен упасть (CapitalService не существует)**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=CapitalServiceTest`

Ожидаемый результат: FAIL — компиляция падает на отсутствии класса.

- [ ] **Step 3: Создать `CapitalService` с минимальной реализацией CRUD**

Файл `backend/src/main/java/ru/selfin/backend/service/CapitalService.java`:

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.capital.*;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.CapitalRevaluation;
import ru.selfin.backend.model.enums.CapitalItemKind;
import ru.selfin.backend.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Доменный сервис модуля «Капитал». См. spec §«Архитектура backend».
 *
 * <p>Все мутации (CRUD по items и revaluations) — {@code @Transactional}.
 * Чтения (summary, trajectory, list, history) — readOnly.
 *
 * <p>{@code liquidAt(date)} вычисляется здесь же как приватный метод: переиспользует
 * существующие репозитории {@code BalanceCheckpoint*}, {@code FinancialEvent*}, {@code FundTransaction*}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CapitalService {

    private final CapitalItemRepository itemRepo;
    private final CapitalRevaluationRepository revRepo;
    private final BalanceCheckpointRepository checkpointRepo;
    private final FinancialEventRepository eventRepo;
    private final FundTransactionRepository fundTxRepo;

    // === CRUD: items ===

    @Transactional
    public CapitalItemDto create(CapitalItemCreateDto dto) {
        CapitalItem item = CapitalItem.builder()
                .kind(dto.kind())
                .name(dto.name())
                .description(dto.description())
                .build();
        item = itemRepo.save(item);

        LocalDate valuedAt = dto.initialValuedAt() != null ? dto.initialValuedAt() : LocalDate.now();
        CapitalRevaluation rev = CapitalRevaluation.builder()
                .itemId(item.getId())
                .value(dto.initialValue())
                .valuedAt(valuedAt)
                .build();
        revRepo.save(rev);

        return toItemDto(item, rev);
    }

    public List<CapitalItemDto> list(CapitalItemKind kind, boolean includeArchived) {
        List<CapitalItem> items = itemRepo.findAllActive(kind);
        return items.stream()
                .map(this::loadAndMap)
                .filter(dto -> includeArchived || !dto.isArchived())
                .toList();
    }

    public CapitalItemDto get(UUID id) {
        CapitalItem item = itemRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", id));
        return loadAndMap(item);
    }

    @Transactional
    public CapitalItemDto update(UUID id, CapitalItemUpdateDto dto) {
        CapitalItem item = itemRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", id));
        if (dto.name() != null) item.setName(dto.name());
        if (dto.description() != null) item.setDescription(dto.description());
        return loadAndMap(itemRepo.save(item));
    }

    @Transactional
    public void delete(UUID id) {
        CapitalItem item = itemRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", id));
        item.setDeleted(true);
        itemRepo.save(item);
    }

    // === CRUD: revaluations ===

    @Transactional
    public CapitalRevaluationDto addRevaluation(UUID itemId, CapitalRevaluationCreateDto dto) {
        CapitalItem item = itemRepo.findActiveById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", itemId));

        LocalDate valuedAt = dto.valuedAt() != null ? dto.valuedAt() : LocalDate.now();
        CapitalRevaluation rev = CapitalRevaluation.builder()
                .itemId(item.getId())
                .value(dto.value())
                .valuedAt(valuedAt)
                .note(dto.note())
                .build();
        return toRevDto(revRepo.save(rev));
    }

    public List<CapitalRevaluationDto> getHistory(UUID itemId) {
        // не проверяем itemRepo — пользователь может смотреть историю архивных
        return revRepo.findHistoryByItemId(itemId).stream().map(this::toRevDto).toList();
    }

    @Transactional
    public CapitalRevaluationDto updateRevaluation(UUID id, CapitalRevaluationUpdateDto dto) {
        CapitalRevaluation rev = revRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalRevaluation", id));
        if (dto.value() != null) rev.setValue(dto.value());
        if (dto.valuedAt() != null) rev.setValuedAt(dto.valuedAt());
        if (dto.note() != null) rev.setNote(dto.note());
        return toRevDto(revRepo.save(rev));
    }

    @Transactional
    public void deleteRevaluation(UUID id) {
        CapitalRevaluation rev = revRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalRevaluation", id));
        rev.setDeleted(true);
        revRepo.save(rev);
    }

    // === aggregates: summary & trajectory ===

    public CapitalSummaryDto summary() {
        // Implemented in Task 11.
        throw new UnsupportedOperationException("not yet");
    }

    public CapitalTrajectoryDto trajectory(LocalDate from, LocalDate to) {
        // Implemented in Task 11.
        throw new UnsupportedOperationException("not yet");
    }

    // === helpers ===

    private CapitalItemDto loadAndMap(CapitalItem item) {
        List<CapitalRevaluation> history = revRepo.findHistoryByItemId(item.getId());
        CapitalRevaluation last = history.isEmpty() ? null : history.get(0);
        return toItemDto(item, last);
    }

    private CapitalItemDto toItemDto(CapitalItem item, CapitalRevaluation last) {
        BigDecimal currentValue = last != null ? last.getValue() : BigDecimal.ZERO;
        LocalDate lastValuedAt = last != null ? last.getValuedAt() : null;
        boolean isArchived = currentValue.signum() == 0;
        return new CapitalItemDto(
                item.getId(), item.getKind(), item.getName(), item.getDescription(),
                item.getCreatedAt().toInstant(ZoneOffset.UTC),
                currentValue, lastValuedAt, isArchived);
    }

    private CapitalRevaluationDto toRevDto(CapitalRevaluation r) {
        return new CapitalRevaluationDto(
                r.getId(), r.getItemId(), r.getValue(), r.getValuedAt(), r.getNote(),
                r.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
```

- [ ] **Step 4: Запустить тесты CRUD — должны пройти**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=CapitalServiceTest`

Ожидаемый результат: PASS.

- [ ] **Step 5: Дописать остальные тесты CRUD**

Добавить к `CapitalServiceTest`:

```java
@Test
void delete_marksItemAsDeleted() {
    UUID id = UUID.randomUUID();
    CapitalItem item = CapitalItem.builder().kind(CapitalItemKind.ASSET).name("X").build();
    item.setId(id);
    when(itemRepo.findActiveById(id)).thenReturn(Optional.of(item));
    when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.delete(id);

    assertThat(item.isDeleted()).isTrue();
}

@Test
void delete_unknownId_throws404() {
    UUID id = UUID.randomUUID();
    when(itemRepo.findActiveById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(id))
            .isInstanceOf(ResourceNotFoundException.class);
}

@Test
void addRevaluation_unknownItem_throws404() {
    UUID itemId = UUID.randomUUID();
    when(itemRepo.findActiveById(itemId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.addRevaluation(itemId,
            new CapitalRevaluationCreateDto(BigDecimal.ONE, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
}

@Test
void update_modifiesNameAndDescription() {
    UUID id = UUID.randomUUID();
    CapitalItem item = CapitalItem.builder().kind(CapitalItemKind.ASSET).name("Old").description("d1").build();
    item.setId(id);
    when(itemRepo.findActiveById(id)).thenReturn(Optional.of(item));
    when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(revRepo.findHistoryByItemId(id)).thenReturn(List.of());

    CapitalItemDto result = service.update(id,
            new CapitalItemUpdateDto("New", "d2"));

    assertThat(result.name()).isEqualTo("New");
    assertThat(result.description()).isEqualTo("d2");
}

@Test
void list_withIncludeArchivedFalse_filtersZeroValueItems() {
    UUID liveId = UUID.randomUUID();
    UUID archivedId = UUID.randomUUID();
    CapitalItem live = itemBuilder(liveId, "Live");
    CapitalItem archived = itemBuilder(archivedId, "Archived");

    when(itemRepo.findAllActive(null)).thenReturn(List.of(live, archived));
    when(revRepo.findHistoryByItemId(liveId)).thenReturn(List.of(
            CapitalRevaluation.builder().itemId(liveId).value(new BigDecimal("100")).valuedAt(LocalDate.now()).build()));
    when(revRepo.findHistoryByItemId(archivedId)).thenReturn(List.of(
            CapitalRevaluation.builder().itemId(archivedId).value(BigDecimal.ZERO).valuedAt(LocalDate.now()).build()));

    List<CapitalItemDto> result = service.list(null, false);

    assertThat(result).extracting(CapitalItemDto::id).containsExactly(liveId);
}

private CapitalItem itemBuilder(UUID id, String name) {
    CapitalItem i = CapitalItem.builder().kind(CapitalItemKind.ASSET).name(name).build();
    i.setId(id);
    return i;
}
```

- [ ] **Step 6: Запустить и убедиться что все тесты проходят**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=CapitalServiceTest`

Ожидаемый результат: PASS (≥ 7 тестов).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/CapitalService.java \
        backend/src/test/java/ru/selfin/backend/service/CapitalServiceTest.java
git commit -m "feat(capital): CapitalService CRUD with TDD"
```

### Task 11: Сервис — `summary` и `trajectory`

Реализуем в TDD. Сложность — корректно вычислить `liquidAt(t)` и сложить с активами/обязательствами на каждой точке. `summary` — частный случай `trajectory` для одной точки `today` плюс дельты.

**Формула `liquidAt(t)` — безопасная (без зависимости от 1:1 пары FUND_TRANSFER↔FundTransaction):**

```
liquidAt(t) = AccountBalance(t) + Σ FundBalance(t)

AccountBalance(t) = checkpoint(≤ t).amount
                  + Σ INCOME факт events       где (checkpoint.date, t]
                  − Σ EXPENSE факт events      где (checkpoint.date, t]
                  − Σ FUND_TRANSFER факт events где (checkpoint.date, t]

Σ FundBalance(t) = Σ FundTransaction.amount где transaction_date ≤ t, deleted=false
```

Это прямая транскрипция формулы из spec §«Жидкая часть». Алгебраически она равна короткому варианту `checkpoint + INCOME − EXPENSE` **только если** для каждого FUND_TRANSFER event существует ровно один FundTransaction; чтобы не зависеть от этого инварианта продакшен-кода, вычисляем оба слагаемых явно.

**Если checkpoint'а нет** — стартовый баланс = 0, нижняя граница событий = `LocalDate.of(1970, 1, 1)` (sentinel, безопасный для JPA date binding; `LocalDate.MIN` нельзя — даёт год -999999999).

**Новые репозиторные методы (которые нужно будет добавить):**
- `BalanceCheckpointRepository.findTopByDateLessThanEqualOrderByDateDesc(date)` — кастомный @Query с `LIMIT 1`.
- `FinancialEventRepository.sumFactByTypeBetween(type, from, to)` — сумма факт-сумм по типу за `[from..to]` включительно.
- `FundTransactionRepository.sumByTransactionDateLessThanEqual(date)` — сумма pocket-балансов на дату.

- [ ] **Step 1: Добавить отсутствующие repo-методы**

В `BalanceCheckpointRepository`:

```java
@Query("SELECT cp FROM BalanceCheckpoint cp WHERE cp.date <= :date ORDER BY cp.date DESC LIMIT 1")
Optional<BalanceCheckpoint> findTopByDateLessThanEqualOrderByDateDesc(@Param("date") LocalDate date);
```

В `FinancialEventRepository`:

```java
@Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.deleted = false
          AND e.type = :type
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.factAmount IS NOT NULL
          AND e.date >= :from
          AND e.date <= :to
        """)
BigDecimal sumFactByTypeBetween(@Param("type") EventType type,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);
```

В `FundTransactionRepository`:

```java
@Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM FundTransaction t
        WHERE t.deleted = false
          AND t.transactionDate <= :date
        """)
BigDecimal sumByTransactionDateLessThanEqual(@Param("date") LocalDate date);
```

Commit отдельным коммитом:

```bash
git add backend/src/main/java/ru/selfin/backend/repository/BalanceCheckpointRepository.java \
        backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java \
        backend/src/main/java/ru/selfin/backend/repository/FundTransactionRepository.java
git commit -m "feat(capital): add 'as of date' query helpers to existing repositories"
```

- [ ] **Step 2: Написать тесты для `summary` (через них покрываем `liquidAt` косвенно)**

Добавить в `CapitalServiceTest`:

```java
@Test
void summary_emptyDb_returnsZero() {
    when(itemRepo.findAllActive(null)).thenReturn(List.of());
    when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.empty());
    when(eventRepo.sumFactByTypeBetween(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
    when(revRepo.snapshotAt(any())).thenReturn(List.of());

    CapitalSummaryDto s = service.summary();

    assertThat(s.total()).isEqualByComparingTo("0");
    assertThat(s.liquid()).isEqualByComparingTo("0");
    assertThat(s.assetsTotal()).isEqualByComparingTo("0");
    assertThat(s.liabilitiesTotal()).isEqualByComparingTo("0");
    assertThat(s.items()).isEmpty();
}

@Test
void summary_withCheckpointAndItems_computesTotal() {
    // checkpoint 100к на сегодня, без событий, без копилок, актив 8.5М, обязательство 4.8М
    // ожидаемое: liquid = 100к, assets = 8.5М, liabilities = 4.8М, total = 3.8М
    BalanceCheckpoint cp = BalanceCheckpoint.builder()
            .date(LocalDate.now()).amount(new BigDecimal("100000")).build();
    when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.of(cp));
    when(eventRepo.sumFactByTypeBetween(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);

    UUID assetId = UUID.randomUUID();
    UUID liabilityId = UUID.randomUUID();
    when(revRepo.snapshotAt(any())).thenReturn(List.of(
            mockProj(assetId, CapitalItemKind.ASSET, new BigDecimal("8500000")),
            mockProj(liabilityId, CapitalItemKind.LIABILITY, new BigDecimal("4800000"))));
    when(itemRepo.findAllActive(null)).thenReturn(List.of(
            itemBuilder(assetId, "Квартира"),
            itemBuilderKind(liabilityId, "Ипотека", CapitalItemKind.LIABILITY)));
    when(revRepo.findHistoryByItemId(any())).thenReturn(List.of(
            CapitalRevaluation.builder().value(new BigDecimal("8500000")).valuedAt(LocalDate.now()).build()));

    CapitalSummaryDto s = service.summary();

    assertThat(s.liquid()).isEqualByComparingTo("100000");
    assertThat(s.assetsTotal()).isEqualByComparingTo("8500000");
    assertThat(s.liabilitiesTotal()).isEqualByComparingTo("4800000");
    assertThat(s.total()).isEqualByComparingTo("3800000");
}

@Test
void liquidAt_includesPocketBalances_andSubtractsFundTransfersFromAccount() {
    // Проверяем: AccountBalance вычитает FUND_TRANSFER, а pocketBalance их обратно добавляет.
    // checkpoint=200к, income=50к, expense=10к, fund_transfer=30к, pockets=30к
    // expected liquid: 200 + 50 − 10 − 30 + 30 = 240к
    BalanceCheckpoint cp = BalanceCheckpoint.builder()
            .date(LocalDate.now().minusDays(30)).amount(new BigDecimal("200000")).build();
    when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.of(cp));
    when(eventRepo.sumFactByTypeBetween(eq(EventType.INCOME), any(), any())).thenReturn(new BigDecimal("50000"));
    when(eventRepo.sumFactByTypeBetween(eq(EventType.EXPENSE), any(), any())).thenReturn(new BigDecimal("10000"));
    when(eventRepo.sumFactByTypeBetween(eq(EventType.FUND_TRANSFER), any(), any())).thenReturn(new BigDecimal("30000"));
    when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(new BigDecimal("30000"));
    when(revRepo.snapshotAt(any())).thenReturn(List.of());
    when(itemRepo.findAllActive(null)).thenReturn(List.of());

    CapitalSummaryDto s = service.summary();

    assertThat(s.liquid()).isEqualByComparingTo("240000");
}

@Test
void liquidAt_noCheckpoint_usesSentinelDate() {
    when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.empty());
    // Если код корректно использует sentinel LocalDate.of(1970,1,1), то sumFactByTypeBetween не падает:
    when(eventRepo.sumFactByTypeBetween(any(),
            eq(LocalDate.of(1970, 1, 1)), any())).thenReturn(new BigDecimal("12345"));
    when(eventRepo.sumFactByTypeBetween(eq(EventType.EXPENSE), any(), any())).thenReturn(BigDecimal.ZERO);
    when(eventRepo.sumFactByTypeBetween(eq(EventType.FUND_TRANSFER), any(), any())).thenReturn(BigDecimal.ZERO);
    when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
    when(revRepo.snapshotAt(any())).thenReturn(List.of());
    when(itemRepo.findAllActive(null)).thenReturn(List.of());

    CapitalSummaryDto s = service.summary();

    // 12345 пришло только потому, что мы попали в sentinel-вызов.
    assertThat(s.liquid()).isEqualByComparingTo("12345");
}

@Test
void trajectory_rejectsFromGreaterThanTo() {
    assertThatThrownBy(() -> service.trajectory(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 1, 1)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
}

private CapitalSnapshotProjection mockProj(UUID id, CapitalItemKind kind, BigDecimal value) {
    CapitalSnapshotProjection p = org.mockito.Mockito.mock(CapitalSnapshotProjection.class);
    when(p.getItemId()).thenReturn(id);
    when(p.getKind()).thenReturn(kind);
    when(p.getValue()).thenReturn(value);
    return p;
}

private CapitalItem itemBuilderKind(UUID id, String name, CapitalItemKind kind) {
    CapitalItem i = CapitalItem.builder().kind(kind).name(name).build();
    i.setId(id);
    return i;
}
```

- [ ] **Step 3: Реализовать `summary` и `trajectory` в `CapitalService`**

Добавить (заменяя `throw new UnsupportedOperationException`):

```java
public CapitalSummaryDto summary() {
    LocalDate today = LocalDate.now();

    BigDecimal liquid = liquidAt(today);
    Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(today);
    BigDecimal assetsTotal = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
    BigDecimal liabilitiesTotal = sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO);
    BigDecimal total = liquid.add(assetsTotal).subtract(liabilitiesTotal);

    List<CapitalItemDto> items = list(null, true); // все, включая архивные — UI решит

    BigDecimal capitalMonthAgo   = capitalAt(today.minusMonths(1));
    BigDecimal capitalQuarterAgo = capitalAt(today.minusMonths(3));
    BigDecimal capitalYearAgo    = capitalAt(today.minusYears(1));

    return new CapitalSummaryDto(
            total, liquid, assetsTotal, liabilitiesTotal, items,
            new CapitalSummaryDto.Deltas(
                    total.subtract(capitalMonthAgo),
                    total.subtract(capitalQuarterAgo),
                    total.subtract(capitalYearAgo)));
}

public CapitalTrajectoryDto trajectory(LocalDate from, LocalDate to) {
    LocalDate today = LocalDate.now();
    LocalDate effectiveTo = to != null ? to : today;
    LocalDate effectiveFrom = from != null
            ? from
            : revRepo.findEarliestValuedAt()
                .orElseGet(() -> checkpointRepo.findTopByOrderByDateAsc()
                    .map(cp -> cp.getDate())
                    .orElse(today));
    if (effectiveFrom.isAfter(effectiveTo)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be <= to");
    }

    List<LocalDate> points = buildMonthEndPoints(effectiveFrom, effectiveTo);
    if (points.isEmpty() || !points.get(points.size() - 1).equals(today)) {
        points.add(today);
    }

    List<CapitalTrajectoryDto.Point> result = new ArrayList<>();
    for (LocalDate t : points) {
        BigDecimal liquid = liquidAt(t);
        Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(t);
        BigDecimal assets = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
        BigDecimal liabilities = sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO);
        result.add(new CapitalTrajectoryDto.Point(t, liquid.add(assets).subtract(liabilities), liquid, assets, liabilities));
    }
    return new CapitalTrajectoryDto(result);
}

private BigDecimal capitalAt(LocalDate t) {
    BigDecimal liquid = liquidAt(t);
    Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(t);
    BigDecimal assets = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
    BigDecimal liabilities = sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO);
    return liquid.add(assets).subtract(liabilities);
}

private Map<CapitalItemKind, BigDecimal> sumByKindAt(LocalDate t) {
    return revRepo.snapshotAt(t).stream()
            .collect(Collectors.groupingBy(
                    CapitalSnapshotProjection::getKind,
                    Collectors.reducing(BigDecimal.ZERO,
                            CapitalSnapshotProjection::getValue, BigDecimal::add)));
}

/** Sentinel: безопасная нижняя граница для JPA date binding, когда checkpoint'а нет. */
private static final LocalDate EPOCH_SENTINEL = LocalDate.of(1970, 1, 1);

private BigDecimal liquidAt(LocalDate t) {
    Optional<BalanceCheckpoint> latest = checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(t);
    BigDecimal start = latest.map(BalanceCheckpoint::getAmount).orElse(BigDecimal.ZERO);
    LocalDate fromDate = latest.map(cp -> cp.getDate().plusDays(1)).orElse(EPOCH_SENTINEL);

    BigDecimal income       = eventRepo.sumFactByTypeBetween(EventType.INCOME,        fromDate, t);
    BigDecimal expense      = eventRepo.sumFactByTypeBetween(EventType.EXPENSE,       fromDate, t);
    BigDecimal fundTransfer = eventRepo.sumFactByTypeBetween(EventType.FUND_TRANSFER, fromDate, t);
    BigDecimal accountBalance = start.add(income).subtract(expense).subtract(fundTransfer);

    BigDecimal pocketBalance = fundTxRepo.sumByTransactionDateLessThanEqual(t);

    return accountBalance.add(pocketBalance);
}

private List<LocalDate> buildMonthEndPoints(LocalDate from, LocalDate to) {
    List<LocalDate> result = new ArrayList<>();
    YearMonth ym = YearMonth.from(from);
    YearMonth toYm = YearMonth.from(to);
    while (!ym.isAfter(toYm)) {
        LocalDate eom = ym.atEndOfMonth();
        if (!eom.isBefore(from) && !eom.isAfter(to)) result.add(eom);
        ym = ym.plusMonths(1);
    }
    return result;
}
```

**Дополнительно для trajectory:** `BalanceCheckpointRepository.findTopByOrderByDateAsc()` — Spring Data сгенерирует метод по имени (используется для определения нижней границы графика). Добавить в репозиторий:

```java
Optional<BalanceCheckpoint> findTopByOrderByDateAsc();
```

- [ ] **Step 4: Запустить все тесты сервиса**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=CapitalServiceTest`

Ожидаемый результат: все тесты PASS.

- [ ] **Step 5: Commit**

(Репозиторные методы уже закоммичены в Step 1. Здесь — только сервис + тесты.)

```bash
git add backend/src/main/java/ru/selfin/backend/service/CapitalService.java \
        backend/src/main/java/ru/selfin/backend/repository/BalanceCheckpointRepository.java \
        backend/src/test/java/ru/selfin/backend/service/CapitalServiceTest.java
git commit -m "feat(capital): summary and trajectory aggregates with liquidAt helper"
```

### Task 12: Контроллер `CapitalController`

**Файл:** `backend/src/main/java/ru/selfin/backend/controller/CapitalController.java`

Все endpoints под `/api/v1/capital/*`. Возвращает 200/201/204 как принято в проекте. `@Valid` на body, `@PreAuthorize` не ставим (проект пока без аутентификации).

- [ ] **Step 1: Создать контроллер**

```java
package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.capital.*;
import ru.selfin.backend.model.enums.CapitalItemKind;
import ru.selfin.backend.service.CapitalService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/capital")
@RequiredArgsConstructor
public class CapitalController {

    private final CapitalService service;

    // --- items ---

    @GetMapping("/items")
    public List<CapitalItemDto> list(
            @RequestParam(required = false) CapitalItemKind kind,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(kind, includeArchived);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CapitalItemDto create(@Valid @RequestBody CapitalItemCreateDto dto) {
        return service.create(dto);
    }

    @GetMapping("/items/{id}")
    public CapitalItemDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/items/{id}")
    public CapitalItemDto update(@PathVariable UUID id, @Valid @RequestBody CapitalItemUpdateDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // --- revaluations ---

    @GetMapping("/items/{itemId}/revaluations")
    public List<CapitalRevaluationDto> history(@PathVariable UUID itemId) {
        return service.getHistory(itemId);
    }

    @PostMapping("/items/{itemId}/revaluations")
    @ResponseStatus(HttpStatus.CREATED)
    public CapitalRevaluationDto addRevaluation(@PathVariable UUID itemId,
                                                @Valid @RequestBody CapitalRevaluationCreateDto dto) {
        return service.addRevaluation(itemId, dto);
    }

    @PutMapping("/revaluations/{id}")
    public CapitalRevaluationDto updateRevaluation(@PathVariable UUID id,
                                                   @Valid @RequestBody CapitalRevaluationUpdateDto dto) {
        return service.updateRevaluation(id, dto);
    }

    @DeleteMapping("/revaluations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRevaluation(@PathVariable UUID id) {
        service.deleteRevaluation(id);
    }

    // --- aggregates ---

    @GetMapping("/summary")
    public CapitalSummaryDto summary() {
        return service.summary();
    }

    @GetMapping("/trajectory")
    public CapitalTrajectoryDto trajectory(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return service.trajectory(from, to);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/CapitalController.java
git commit -m "feat(capital): CapitalController with all REST endpoints"
```

### Task 13: Integration тест контроллера

**Файл:** `backend/src/test/java/ru/selfin/backend/CapitalControllerIT.java`

Покрываем полный стек на репрезентативных сценариях. Образец — `BalanceCheckpointControllerIT.java`.

- [ ] **Step 1: Написать тест**

```java
package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CapitalControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;

    @Test
    void createItem_thenAppearsInList() throws Exception {
        String body = """
                {"kind":"ASSET","name":"Квартира","initialValue":8500000}
                """;
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.kind").value("ASSET"))
                .andExpect(jsonPath("$.currentValue").value(8500000))
                .andExpect(jsonPath("$.isArchived").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/v1/capital/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
    }

    @Test
    void createItem_negativeInitialValue_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":-1}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createItem_futureValuedAt_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":1000,"initialValuedAt":"2099-01-01"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addRevaluation_thenItemCurrentValueUpdates() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Volvo","initialValue":1400000}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":1200000,"note":"Авито"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capital/items/" + id))
                .andExpect(jsonPath("$.currentValue").value(1200000));
    }

    @Test
    void addRevaluation_zeroValue_archivesItem() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Old car","initialValue":500000}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":0}
                        """))
                .andExpect(status().isCreated());

        // По умолчанию includeArchived=false → item не виден
        mockMvc.perform(get("/api/v1/capital/items"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());

        // С includeArchived=true → виден
        mockMvc.perform(get("/api/v1/capital/items?includeArchived=true"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].isArchived").value(true));
    }

    @Test
    void deleteItem_returns404OnSubsequentGet() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Test","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(delete("/api/v1/capital/items/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/capital/items/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void summary_emptyDb_returnsZeroes() throws Exception {
        mockMvc.perform(get("/api/v1/capital/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.assetsTotal").value(0))
                .andExpect(jsonPath("$.liabilitiesTotal").value(0))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void summary_withAssetAndLiability_computesTotal() throws Exception {
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Квартира","initialValue":8500000}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"LIABILITY","name":"Ипотека","initialValue":4800000}
                        """)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capital/summary"))
                .andExpect(jsonPath("$.assetsTotal").value(8500000))
                .andExpect(jsonPath("$.liabilitiesTotal").value(4800000))
                .andExpect(jsonPath("$.total").value(3700000)); // liquid=0
    }

    @Test
    void getItem_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/capital/items/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void addRevaluation_onSoftDeletedItem_returns404() throws Exception {
        // I4: после hard-delete нельзя добавлять переоценки
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(delete("/api/v1/capital/items/" + id)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":200}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void addRevaluation_futureDate_returns400() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":200,"valuedAt":"2099-01-01"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRevaluation_softDeletes_excludesFromHistory() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String itemId = om.readTree(created).get("id").asText();

        String revBody = mockMvc.perform(post("/api/v1/capital/items/" + itemId + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":200}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String revId = om.readTree(revBody).get("id").asText();

        mockMvc.perform(delete("/api/v1/capital/revaluations/" + revId)).andExpect(status().isNoContent());

        // история должна содержать только первую (initial) запись
        mockMvc.perform(get("/api/v1/capital/items/" + itemId + "/revaluations"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value(100));
    }

    @Test
    void trajectory_fromGreaterThanTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/capital/trajectory?from=2026-05-01&to=2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trajectory_withRetroactiveRevaluation_buildsHistory() throws Exception {
        // создаём актив с переоценкой год назад
        String pastDate = java.time.LocalDate.now().minusMonths(6).withDayOfMonth(1).toString();
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"kind":"ASSET","name":"Backfill","initialValue":1000000,"initialValuedAt":"%s"}
                        """, pastDate)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/capital/trajectory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()").value(org.hamcrest.Matchers.greaterThan(1)));
    }
}
```

- [ ] **Step 2: Запустить тесты**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test -Dtest=CapitalControllerIT`

Ожидаемый результат: все тесты PASS.

- [ ] **Step 3: Запустить полный suite — проверить что не сломали соседние фичи**

Команда: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test`

Ожидаемый результат: все тесты PASS (старые + новые).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/ru/selfin/backend/CapitalControllerIT.java
git commit -m "test(capital): integration tests for CapitalController"
```

---

## Chunk 3: Frontend — типы, API-клиент, page skeleton, навигация

**Цель чанка:** дать рабочую страницу `/capital` с hero-сводкой и двумя колонками, читающую `/api/v1/capital/summary`. Без модалки — она в Chunk 4.

**Files to create/modify:**
- Modify: `frontend/src/types/api.ts` — добавить новые типы
- Modify: `frontend/src/api/index.ts` — добавить функции
- Create: `frontend/src/pages/Capital.tsx`
- Create: `frontend/src/components/CapitalSummaryCard.tsx`
- Create: `frontend/src/components/CapitalItemList.tsx`
- Modify: `frontend/src/App.tsx` — добавить route
- Modify: `frontend/src/components/BottomNav.tsx` — добавить пункт навигации

### Task 14: Типы TypeScript

**Файл:** `frontend/src/types/api.ts`

- [ ] **Step 1: Добавить типы в конец `api.ts`**

```typescript
// --- Capital ---

export type CapitalItemKind = 'ASSET' | 'LIABILITY';

export interface CapitalItem {
    id: string;
    kind: CapitalItemKind;
    name: string;
    description: string | null;
    createdAt: string;       // ISO instant
    currentValue: number;
    lastValuedAt: string | null;  // ISO date
    isArchived: boolean;
}

export interface CapitalItemCreateDto {
    kind: CapitalItemKind;
    name: string;
    description?: string;
    initialValue: number;
    initialValuedAt?: string;
}

export interface CapitalItemUpdateDto {
    name?: string;
    description?: string;
}

export interface CapitalRevaluation {
    id: string;
    itemId: string;
    value: number;
    valuedAt: string;
    note: string | null;
    createdAt: string;
}

export interface CapitalRevaluationCreateDto {
    value: number;
    valuedAt?: string;
    note?: string;
}

export interface CapitalRevaluationUpdateDto {
    value?: number;
    valuedAt?: string;
    note?: string;
}

export interface CapitalSummary {
    total: number;
    liquid: number;
    assetsTotal: number;
    liabilitiesTotal: number;
    items: CapitalItem[];
    deltas: {
        month: number;
        quarter: number;
        year: number;
    };
}

export interface CapitalTrajectory {
    points: Array<{
        date: string;          // ISO date
        capital: number;
        liquid: number;
        assets: number;
        liabilities: number;
    }>;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/types/api.ts
git commit -m "feat(capital): TypeScript types for capital DTOs"
```

### Task 15: API-функции

**Файл:** `frontend/src/api/index.ts`

- [ ] **Step 1: Добавить функции (импорты сверху файла, экспорты в конец)**

В блок `import` в начале файла добавить новые типы (рядом с существующими `import type { ... }`):

```typescript
import type {
    // ... существующие импорты ...
    CapitalItem, CapitalItemCreateDto, CapitalItemUpdateDto,
    CapitalRevaluation, CapitalRevaluationCreateDto, CapitalRevaluationUpdateDto,
    CapitalSummary, CapitalTrajectory, CapitalItemKind,
} from '../types/api';
```

В конец файла добавить экспорты:

```typescript
// --- Capital ---

export const fetchCapitalItems = (kind?: CapitalItemKind, includeArchived = false) => {
    const params = new URLSearchParams();
    if (kind) params.set('kind', kind);
    if (includeArchived) params.set('includeArchived', 'true');
    const qs = params.toString();
    return get<CapitalItem[]>(`/capital/items${qs ? '?' + qs : ''}`);
};

export const fetchCapitalItem = (id: string) =>
    get<CapitalItem>(`/capital/items/${id}`);

export const createCapitalItem = (dto: CapitalItemCreateDto) =>
    post<CapitalItem>('/capital/items', dto);

export const updateCapitalItem = (id: string, dto: CapitalItemUpdateDto) =>
    put<CapitalItem>(`/capital/items/${id}`, dto);

export const deleteCapitalItem = (id: string) =>
    del(`/capital/items/${id}`);

export const fetchCapitalHistory = (itemId: string) =>
    get<CapitalRevaluation[]>(`/capital/items/${itemId}/revaluations`);

export const addCapitalRevaluation = (itemId: string, dto: CapitalRevaluationCreateDto) =>
    post<CapitalRevaluation>(`/capital/items/${itemId}/revaluations`, dto);

export const updateCapitalRevaluation = (id: string, dto: CapitalRevaluationUpdateDto) =>
    put<CapitalRevaluation>(`/capital/revaluations/${id}`, dto);

export const deleteCapitalRevaluation = (id: string) =>
    del(`/capital/revaluations/${id}`);

export const fetchCapitalSummary = () =>
    get<CapitalSummary>('/capital/summary');

export const fetchCapitalTrajectory = (from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const qs = params.toString();
    return get<CapitalTrajectory>(`/capital/trajectory${qs ? '?' + qs : ''}`);
};
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/api/index.ts
git commit -m "feat(capital): API client functions"
```

### Task 16: Компонент `CapitalSummaryCard`

**Файл:** `frontend/src/components/CapitalSummaryCard.tsx`

Hero-карточка: текущее значение + табы дельт. Использует `Tabs` если он есть в shadcn (можно проверить); иначе три кнопки.

- [ ] **Step 1: Проверить наличие `Tabs` компонента**

Команда: `ls frontend/src/components/ui/ | grep -i tab`

Если есть — используем `Tabs`. Если нет — три кнопки-переключателя.

- [ ] **Step 2: Создать компонент**

```tsx
import { useState } from 'react';
import { TrendingUp, TrendingDown } from 'lucide-react';
import type { CapitalSummary } from '../types/api';
import { Button } from './ui/button';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtDelta = (n: number) => {
    const sign = n >= 0 ? '+' : '';
    return `${sign}${fmt(n)}`;
};

const fmtPercent = (delta: number, base: number) => {
    if (base === 0) return '';
    const pct = (delta / base) * 100;
    const sign = pct >= 0 ? '+' : '';
    return `(${sign}${pct.toFixed(1)}%)`;
};

type Period = 'month' | 'quarter' | 'year';
const periodLabels: Record<Period, string> = {
    month: 'Месяц',
    quarter: 'Квартал',
    year: 'Год',
};

interface Props {
    summary: CapitalSummary;
}

export default function CapitalSummaryCard({ summary }: Props) {
    const [period, setPeriod] = useState<Period>('month');
    const delta = summary.deltas[period];
    const baseForPercent = summary.total - delta;

    return (
        <div className="rounded-lg p-5"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="text-xs uppercase tracking-wide" style={{ color: 'var(--color-text-muted)' }}>
                Капитал на сегодня
            </div>
            <div className="text-3xl font-semibold mt-1">{fmt(summary.total)}</div>

            <div className="flex gap-2 mt-3">
                {(Object.keys(periodLabels) as Period[]).map(p => (
                    <Button
                        key={p}
                        size="sm"
                        variant={period === p ? 'default' : 'outline'}
                        onClick={() => setPeriod(p)}>
                        {periodLabels[p]}
                    </Button>
                ))}
            </div>

            <div className="mt-2 flex items-center gap-1 text-sm"
                 style={{ color: delta >= 0 ? 'var(--color-success, #7ec699)' : 'var(--color-danger, #e88a8a)' }}>
                {delta >= 0 ? <TrendingUp size={16} /> : <TrendingDown size={16} />}
                {fmtDelta(delta)} {fmtPercent(delta, baseForPercent)}
            </div>
        </div>
    );
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/CapitalSummaryCard.tsx
git commit -m "feat(capital): CapitalSummaryCard with period delta tabs"
```

### Task 17: Компонент `CapitalItemList`

**Файл:** `frontend/src/components/CapitalItemList.tsx`

Колонка: заголовок `АКТИВЫ · 10.15М` или `ОБЯЗАТЕЛЬСТВА · 4.91М`, список строк, кнопка `+ Добавить`. Внизу свёрнутый аккордеон «Архивные».

- [ ] **Step 1: Создать компонент**

```tsx
import { useState } from 'react';
import { Plus, ChevronDown } from 'lucide-react';
import type { CapitalItem, CapitalItemKind } from '../types/api';
import { Button } from './ui/button';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtCompact = (n: number) => {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(2).replace(/\.?0+$/, '') + 'М ₽';
    if (n >= 1_000) return Math.round(n / 1000) + 'к ₽';
    return fmt(n);
};

interface Props {
    kind: CapitalItemKind;
    items: CapitalItem[];
    onItemClick: (item: CapitalItem) => void;
    onCreate: () => void;
}

export default function CapitalItemList({ kind, items, onItemClick, onCreate }: Props) {
    const active = items.filter(i => !i.isArchived);
    const archived = items.filter(i => i.isArchived);
    const total = active.reduce((s, i) => s + i.currentValue, 0);
    const isAsset = kind === 'ASSET';
    const accent = isAsset ? 'var(--color-success, #7ec699)' : 'var(--color-danger, #e88a8a)';
    const heading = isAsset ? 'АКТИВЫ' : 'ОБЯЗАТЕЛЬСТВА';
    const addLabel = isAsset ? '+ Добавить актив' : '+ Добавить обязательство';

    const [archivedOpen, setArchivedOpen] = useState(false);

    return (
        <div className="rounded-lg p-4"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="text-xs font-semibold tracking-wide mb-3" style={{ color: accent }}>
                {heading} · {fmtCompact(total)}
            </div>

            <ul className="space-y-1">
                {active.length === 0 && (
                    <li className="text-sm py-2" style={{ color: 'var(--color-text-muted)' }}>
                        Пока пусто
                    </li>
                )}
                {active.map(item => (
                    <li key={item.id}>
                        <button
                            type="button"
                            onClick={() => onItemClick(item)}
                            className="w-full flex justify-between items-center py-2 px-2 rounded hover:bg-white/5 transition text-left">
                            <span className="truncate">{item.name}</span>
                            <span className="font-medium ml-2">{fmt(item.currentValue)}</span>
                        </button>
                    </li>
                ))}
            </ul>

            <Button variant="ghost" size="sm" onClick={onCreate} className="mt-2 w-full justify-start gap-2">
                <Plus size={16} /> {addLabel}
            </Button>

            {archived.length > 0 && (
                <div className="mt-3 border-t pt-2" style={{ borderColor: 'var(--color-border)' }}>
                    <button
                        type="button"
                        onClick={() => setArchivedOpen(o => !o)}
                        className="w-full flex items-center gap-1 text-xs"
                        style={{ color: 'var(--color-text-muted)' }}>
                        <ChevronDown size={14} className={archivedOpen ? '' : '-rotate-90'} />
                        Архивные ({archived.length})
                    </button>
                    {archivedOpen && (
                        <ul className="mt-1 space-y-1">
                            {archived.map(item => (
                                <li key={item.id}>
                                    <button
                                        type="button"
                                        onClick={() => onItemClick(item)}
                                        className="w-full flex justify-between items-center py-1.5 px-2 rounded hover:bg-white/5 text-left text-sm opacity-70">
                                        <span className="truncate">{item.name}</span>
                                        <span>—</span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/CapitalItemList.tsx
git commit -m "feat(capital): CapitalItemList component"
```

### Task 18: Страница `Capital`

**Файл:** `frontend/src/pages/Capital.tsx`

Контейнер. На MVP-версии этого чанка: загружает summary, рендерит hero и две колонки. Sheet и chart — заглушки в Chunk 4.

- [ ] **Step 1: Создать страницу**

```tsx
import { useEffect, useState, useCallback } from 'react';
import { HelpCircle } from 'lucide-react';
import { fetchCapitalSummary } from '../api';
import type { CapitalSummary, CapitalItem, CapitalItemKind } from '../types/api';
import CapitalSummaryCard from '../components/CapitalSummaryCard';
import CapitalItemList from '../components/CapitalItemList';
import { Button } from '../components/ui/button';

interface Props {
    refreshSignal?: number;
}

export default function Capital({ refreshSignal }: Props) {
    const [summary, setSummary] = useState<CapitalSummary | null>(null);
    const [error, setError] = useState<string | null>(null);

    const reload = useCallback(() => {
        fetchCapitalSummary()
            .then(setSummary)
            .catch(e => setError(String(e)));
    }, []);

    useEffect(() => { reload(); }, [reload, refreshSignal]);

    const handleItemClick = (item: CapitalItem) => {
        // Подключим в Task 24 (Chunk 4) — сейчас заглушка, чтобы страница рендерилась.
        console.log('open sheet for', item.id);
    };

    const handleCreate = (kind: CapitalItemKind) => {
        // Подключим в Task 24 (Chunk 4).
        console.log('open create sheet for', kind);
    };

    const handleTheory = () => {
        // Подключим в Task 24 (Chunk 4).
        console.log('open theory dialog');
    };

    if (error) {
        return <div className="p-4 text-sm text-red-400">Ошибка загрузки: {error}</div>;
    }
    if (!summary) {
        return <div className="p-4 text-sm" style={{ color: 'var(--color-text-muted)' }}>Загрузка…</div>;
    }

    const isEmpty = summary.items.length === 0;

    return (
        <div className="max-w-2xl mx-auto p-4 space-y-4 pb-24">
            <header className="flex items-center justify-between">
                <h1 className="text-xl font-semibold">Капитал</h1>
                <Button variant="ghost" size="icon" aria-label="Что такое капитал?">
                    <HelpCircle size={20} />
                </Button>
            </header>

            {isEmpty ? (
                <EmptyState onCreate={handleCreate} onTheory={handleTheory} />
            ) : (
                <>
                    <CapitalSummaryCard summary={summary} />
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <CapitalItemList
                            kind="ASSET"
                            items={summary.items.filter(i => i.kind === 'ASSET')}
                            onItemClick={handleItemClick}
                            onCreate={() => handleCreate('ASSET')} />
                        <CapitalItemList
                            kind="LIABILITY"
                            items={summary.items.filter(i => i.kind === 'LIABILITY')}
                            onItemClick={handleItemClick}
                            onCreate={() => handleCreate('LIABILITY')} />
                    </div>
                    {/* CapitalTrajectoryChart подключается в Task 24 (Chunk 4) */}
                </>
            )}
        </div>
    );
}

function EmptyState({ onCreate, onTheory }: { onCreate: (k: CapitalItemKind) => void; onTheory: () => void }) {
    return (
        <div className="text-center py-16 px-4">
            <h2 className="text-lg font-semibold mb-2">Здесь будет ваш капитал</h2>
            <p className="text-sm mb-6 max-w-md mx-auto" style={{ color: 'var(--color-text-muted)' }}>
                Добавьте первый актив (квартира, авто, валюта, ценное имущество) или обязательство (ипотека, кредит).
                Деньги на счетах и копилки уже считаются автоматически.
            </p>
            <div className="flex gap-2 justify-center flex-wrap">
                <Button onClick={() => onCreate('ASSET')}>+ Добавить актив</Button>
                <Button variant="outline" onClick={() => onCreate('LIABILITY')}>+ Добавить обязательство</Button>
                <Button variant="ghost" onClick={onTheory}>Что такое капитал? →</Button>
            </div>
        </div>
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/Capital.tsx
git commit -m "feat(capital): Capital page skeleton with summary fetch"
```

### Task 19: Маршрут и навигация

**Файлы:** `frontend/src/App.tsx`, `frontend/src/components/BottomNav.tsx`.

- [ ] **Step 1: Добавить маршрут в `App.tsx`**

Найти существующий блок `<Routes>` и добавить:

```tsx
<Route path="/capital" element={<Capital refreshSignal={refreshKey} />} />
```

И импорт `import Capital from './pages/Capital';` сверху.

- [ ] **Step 2: Добавить пункт в `BottomNav.tsx`**

**Замечание о spec'е:** spec упоминает порядок `Dashboard | Budget | Analytics | Капитал`, но реальное приложение содержит ещё `Funds` («Цели») и `Settings` (5 пунктов). Spec не предписывает удаление существующих пунктов — он описывает только относительное расположение «Капитала». Финальный порядок (6 пунктов): `Dashboard | Budget | Funds | Analytics | Capital | Settings`.

В существующий массив `navItems` добавить элемент Capital:

```tsx
import { LayoutDashboard, CalendarDays, PiggyBank, BarChart2, Landmark, Settings } from 'lucide-react';

const navItems = [
    { to: '/', icon: LayoutDashboard, label: 'Дашборд' },
    { to: '/budget', icon: CalendarDays, label: 'Журнал' },
    { to: '/funds', icon: PiggyBank, label: 'Цели' },
    { to: '/analytics', icon: BarChart2, label: 'Аналитика' },
    { to: '/capital', icon: Landmark, label: 'Капитал' },
    { to: '/settings', icon: Settings, label: 'Настройки' },
];
```

**Проблема ширины на мобильных:** при 6 пунктах на узком экране (<360px) подписи могут не помещаться. Текущая разметка использует `flex-1` на каждом пункте — 6 равных колонок будут по ~60px на типичном телефоне. Подпись «Аналитика» (9 символов) при font-size 12px примерно укладывается, но впритык.

Если в smoke-test ниже визуально видно перетекание/обрезание подписей — применить адаптацию: на экранах ≤375px скрывать подписи (только иконки) через Tailwind responsive-класс. Добавить `className="... hidden sm:inline-block"` к `<label>`. Заодно прибавить чуть больше высоты, чтобы иконка не «прилипала» к границам.

- [ ] **Step 3: Smoke-test в браузере с проверкой адаптации**

Запустить dev server:

```bash
cd frontend && npm run dev
```

Сценарии (через preview-tools):

1. Открыть `http://localhost:5173/capital` на десктопе — empty-state «Здесь будет ваш капитал» должен рендериться. Никаких consol-ошибок.
2. Кликнуть на пункт «Капитал» в нижней навигации с другой страницы (например, с `/` Дашборда) — переход должен сработать, активный пункт подсветиться.
3. **Преимущественно — проверить на ширине 360-414px** (через preview_resize): убедиться что 6 пунктов навигации помещаются без переноса/обрезания. Если подписи обрезаются — применить адаптацию из Step 2.
4. Проверить что на пути `/capital` активный пункт — именно «Капитал», а не другие (NavLink `end` semantic).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.tsx frontend/src/components/BottomNav.tsx
git commit -m "feat(capital): wire up /capital route and bottom nav entry"
```

---

## Chunk 4: Frontend — Sheet, история, график, теория, polish

**Цель чанка:** довести страницу до production-готовности — Sheet с создание/редактирование/история/выбытие/удаление, график траектории, диалог теории, все edge cases.

**Files to create:**
- `frontend/src/components/CapitalSheet.tsx`
- `frontend/src/components/CapitalRevaluationHistory.tsx`
- `frontend/src/components/CapitalTrajectoryChart.tsx`
- `frontend/src/components/CapitalTheoryDialog.tsx`

**Files to modify:**
- `frontend/src/pages/Capital.tsx` — подключить Sheet, chart, dialog

### Task 20: `CapitalRevaluationHistory` — список переоценок

**Файл:** `frontend/src/components/CapitalRevaluationHistory.tsx`

- [ ] **Step 1: Создать компонент**

```tsx
import { useState, useEffect } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { fetchCapitalHistory, updateCapitalRevaluation, deleteCapitalRevaluation } from '../api';
import type { CapitalRevaluation } from '../types/api';
import { Button } from './ui/button';
import { Input } from './ui/input';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtDate = (iso: string) =>
    new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });

interface Props {
    itemId: string;
    refreshSignal: number;
    onChanged: () => void;
}

export default function CapitalRevaluationHistory({ itemId, refreshSignal, onChanged }: Props) {
    const [history, setHistory] = useState<CapitalRevaluation[]>([]);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editValue, setEditValue] = useState('');
    const [editDate, setEditDate] = useState('');

    useEffect(() => {
        fetchCapitalHistory(itemId).then(setHistory).catch(console.error);
    }, [itemId, refreshSignal]);

    const startEdit = (r: CapitalRevaluation) => {
        setEditingId(r.id);
        setEditValue(String(r.value));
        setEditDate(r.valuedAt);
    };

    const saveEdit = async () => {
        if (!editingId) return;
        await updateCapitalRevaluation(editingId, {
            value: Number(editValue),
            valuedAt: editDate,
        });
        setEditingId(null);
        onChanged();
    };

    const remove = async (id: string) => {
        if (!confirm('Удалить эту запись из истории?')) return;
        await deleteCapitalRevaluation(id);
        onChanged();
    };

    if (history.length === 0) {
        return <div className="text-xs py-2" style={{ color: 'var(--color-text-muted)' }}>История пуста</div>;
    }

    return (
        <ul className="space-y-1">
            {history.map(r => (
                <li key={r.id} className="text-sm py-2 px-2 rounded hover:bg-white/5">
                    {editingId === r.id ? (
                        <div className="flex gap-1 items-center">
                            <Input value={editValue} onChange={e => setEditValue(e.target.value)} type="number" className="w-32 h-7" />
                            <Input value={editDate} onChange={e => setEditDate(e.target.value)} type="date" className="w-36 h-7" />
                            <Button size="sm" onClick={saveEdit}>OK</Button>
                            <Button size="sm" variant="ghost" onClick={() => setEditingId(null)}>×</Button>
                        </div>
                    ) : (
                        <div className="flex items-center justify-between">
                            <div className="flex flex-col">
                                <span>{fmtDate(r.valuedAt)} · {fmt(r.value)}</span>
                                {r.note && <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{r.note}</span>}
                            </div>
                            <div className="flex gap-1">
                                <Button size="sm" variant="ghost" onClick={() => startEdit(r)}><Pencil size={14} /></Button>
                                <Button size="sm" variant="ghost" onClick={() => remove(r.id)}><Trash2 size={14} /></Button>
                            </div>
                        </div>
                    )}
                </li>
            ))}
        </ul>
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/CapitalRevaluationHistory.tsx
git commit -m "feat(capital): CapitalRevaluationHistory component"
```

### Task 21: `CapitalSheet` — основная панель

**Файл:** `frontend/src/components/CapitalSheet.tsx`

Большой компонент: режимы create/view-edit, форма переоценки, история, опасные операции (выбытие/hard-delete).

**Зависимости из shadcn:** `Sheet`, `Input`, `Button`, `Badge` (уже есть), `AlertDialog` (для подтверждения hard-delete; нужно проверить наличие).

- [ ] **Step 1: Проверить наличие `AlertDialog`. Если нет — установить.**

Команда: `ls frontend/src/components/ui/ | grep -i alert-dialog`

Если файла нет: `cd frontend && npx shadcn@latest add alert-dialog`. Закоммитить добавленный файл `ui/alert-dialog.tsx` отдельным коммитом перед основным:

```bash
git add frontend/src/components/ui/alert-dialog.tsx
git commit -m "chore(capital): add shadcn AlertDialog for destructive confirmations"
```

- [ ] **Step 2: Создать компонент**

```tsx
import { useState, useEffect } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import {
    AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
    AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from './ui/alert-dialog';
import {
    createCapitalItem, updateCapitalItem, deleteCapitalItem,
    addCapitalRevaluation, fetchCapitalItem,
} from '../api';
import type { CapitalItem, CapitalItemKind } from '../types/api';
import CapitalRevaluationHistory from './CapitalRevaluationHistory';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

interface Props {
    open: boolean;
    mode: { type: 'create'; kind: CapitalItemKind } | { type: 'view'; itemId: string } | null;
    onClose: () => void;
    onChanged: () => void;
}

export default function CapitalSheet({ open, mode, onClose, onChanged }: Props) {
    const [item, setItem] = useState<CapitalItem | null>(null);
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [revValue, setRevValue] = useState('');
    const [revDate, setRevDate] = useState(() => new Date().toISOString().slice(0, 10));
    const [revNote, setRevNote] = useState('');
    const [historyTick, setHistoryTick] = useState(0);
    const [loading, setLoading] = useState(false);
    const [hardDeleteOpen, setHardDeleteOpen] = useState(false);
    const [disposalOpen, setDisposalOpen] = useState(false);
    const [disposalDate, setDisposalDate] = useState(() => new Date().toISOString().slice(0, 10));

    // load item when viewing
    useEffect(() => {
        if (mode?.type === 'view') {
            fetchCapitalItem(mode.itemId).then(i => {
                setItem(i);
                setName(i.name);
                setDescription(i.description ?? '');
            });
        } else {
            setItem(null);
            setName('');
            setDescription('');
        }
        setRevValue('');
        setRevNote('');
        setRevDate(new Date().toISOString().slice(0, 10));
    }, [mode]);

    if (!mode) return null;

    const isCreate = mode.type === 'create';
    const kind: CapitalItemKind = isCreate ? mode.kind : item?.kind ?? 'ASSET';
    const kindLabel = kind === 'ASSET' ? 'Актив' : 'Обязательство';

    const handleSaveCreate = async () => {
        if (!name.trim() || !revValue) return;
        setLoading(true);
        try {
            await createCapitalItem({
                kind,
                name: name.trim(),
                description: description || undefined,
                initialValue: Number(revValue),
                initialValuedAt: revDate,
            });
            onChanged();
            onClose();
        } finally { setLoading(false); }
    };

    const handleSaveMetadata = async () => {
        if (!item) return;
        setLoading(true);
        try {
            await updateCapitalItem(item.id, { name: name.trim(), description: description || undefined });
            onChanged();
        } finally { setLoading(false); }
    };

    const handleAddRevaluation = async () => {
        if (!item || !revValue) return;
        setLoading(true);
        try {
            await addCapitalRevaluation(item.id, {
                value: Number(revValue),
                valuedAt: revDate,
                note: revNote || undefined,
            });
            setRevValue('');
            setRevNote('');
            setHistoryTick(t => t + 1);
            // reload current item to show new currentValue
            const fresh = await fetchCapitalItem(item.id);
            setItem(fresh);
            onChanged();
        } finally { setLoading(false); }
    };

    const handleDisposeConfirm = async () => {
        if (!item || !disposalDate) return;
        setLoading(true);
        try {
            await addCapitalRevaluation(item.id, { value: 0, valuedAt: disposalDate });
            setDisposalOpen(false);
            onChanged();
            onClose();
        } finally { setLoading(false); }
    };

    const handleHardDeleteConfirm = async () => {
        if (!item) return;
        setLoading(true);
        try {
            await deleteCapitalItem(item.id);
            setHardDeleteOpen(false);
            onChanged();
            onClose();
        } finally { setLoading(false); }
    };

    return (
        <Sheet open={open} onOpenChange={o => !o && onClose()}>
            <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
                <SheetHeader>
                    <SheetTitle>
                        {isCreate ? `Новый ${kindLabel.toLowerCase()}` : item?.name}
                        <Badge variant="outline" className="ml-2">{kindLabel}</Badge>
                    </SheetTitle>
                </SheetHeader>

                <div className="mt-4 space-y-4">
                    {/* Имя и описание — редактируемые */}
                    <div className="space-y-2">
                        <Input
                            placeholder="Название (например, Квартира на Ленинской)"
                            value={name}
                            onChange={e => setName(e.target.value)}
                        />
                        <Input
                            placeholder="Заметка (необязательно)"
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                        {!isCreate && item && (
                            <Button size="sm" variant="outline" onClick={handleSaveMetadata} disabled={loading}>
                                Сохранить заголовок
                            </Button>
                        )}
                    </div>

                    {/* Текущая стоимость */}
                    {!isCreate && item && (
                        <div className="rounded p-3" style={{ background: 'var(--color-bg)' }}>
                            <div className="text-xs uppercase tracking-wide" style={{ color: 'var(--color-text-muted)' }}>
                                Текущая стоимость
                            </div>
                            <div className="text-2xl font-semibold mt-1">{fmt(item.currentValue)}</div>
                            {item.lastValuedAt && (
                                <div className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                    {new Date(item.lastValuedAt).toLocaleDateString('ru-RU')}
                                </div>
                            )}
                        </div>
                    )}

                    {/* Форма переоценки / создания */}
                    <div className="space-y-2">
                        <h3 className="text-sm font-medium">{isCreate ? 'Стоимость' : 'Переоценить'}</h3>
                        <Input
                            type="number"
                            placeholder="Сумма, ₽"
                            value={revValue}
                            onChange={e => setRevValue(e.target.value)}
                        />
                        <Input
                            type="date"
                            value={revDate}
                            max={new Date().toISOString().slice(0, 10)}
                            onChange={e => setRevDate(e.target.value)}
                        />
                        {!isCreate && (
                            <Input
                                placeholder="Заметка (например, по объявлениям ЦИАН)"
                                value={revNote}
                                onChange={e => setRevNote(e.target.value)}
                            />
                        )}
                        <Button
                            onClick={isCreate ? handleSaveCreate : handleAddRevaluation}
                            disabled={loading || !revValue || (isCreate && !name.trim())}
                            className="w-full">
                            {isCreate ? 'Создать' : 'Сохранить переоценку'}
                        </Button>
                    </div>

                    {/* История */}
                    {!isCreate && item && (
                        <div>
                            <h3 className="text-sm font-medium mb-2">История переоценок</h3>
                            <CapitalRevaluationHistory
                                itemId={item.id}
                                refreshSignal={historyTick}
                                onChanged={() => { setHistoryTick(t => t + 1); onChanged(); }}
                            />
                        </div>
                    )}

                    {/* Опасные операции */}
                    {!isCreate && item && (
                        <div className="pt-4 border-t space-y-2" style={{ borderColor: 'var(--color-border)' }}>
                            <h3 className="text-sm font-medium" style={{ color: 'var(--color-text-muted)' }}>
                                Опасные операции
                            </h3>
                            {disposalOpen ? (
                                <div className="rounded p-3 space-y-2" style={{ background: 'var(--color-bg)' }}>
                                    <div className="text-xs">Дата выбытия:</div>
                                    <Input
                                        type="date"
                                        value={disposalDate}
                                        max={new Date().toISOString().slice(0, 10)}
                                        onChange={e => setDisposalDate(e.target.value)} />
                                    <div className="flex gap-2">
                                        <Button size="sm" onClick={handleDisposeConfirm} disabled={loading}>
                                            Подтвердить
                                        </Button>
                                        <Button size="sm" variant="ghost" onClick={() => setDisposalOpen(false)}>
                                            Отмена
                                        </Button>
                                    </div>
                                </div>
                            ) : (
                                <Button variant="outline" size="sm" onClick={() => setDisposalOpen(true)} disabled={loading} className="w-full">
                                    {kind === 'ASSET' ? 'Отметить выбытие (продан / утерян)' : 'Отметить закрытие (кредит погашен)'}
                                </Button>
                            )}
                            <Button variant="destructive" size="sm" onClick={() => setHardDeleteOpen(true)} disabled={loading} className="w-full">
                                Удалить безвозвратно
                            </Button>
                        </div>
                    )}
                </div>

                <AlertDialog open={hardDeleteOpen} onOpenChange={setHardDeleteOpen}>
                    <AlertDialogContent>
                        <AlertDialogHeader>
                            <AlertDialogTitle>Удалить «{item?.name}» безвозвратно?</AlertDialogTitle>
                            <AlertDialogDescription>
                                История переоценок и весь вклад в траекторию капитала исчезнут. Это нельзя отменить.
                                Используйте это, если запись создана по ошибке. Если же актив продан или кредит закрыт —
                                лучше «Отметить выбытие», тогда история останется.
                            </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                            <AlertDialogCancel>Отмена</AlertDialogCancel>
                            <AlertDialogAction onClick={handleHardDeleteConfirm}
                                               className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
                                Удалить
                            </AlertDialogAction>
                        </AlertDialogFooter>
                    </AlertDialogContent>
                </AlertDialog>
            </SheetContent>
        </Sheet>
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/CapitalSheet.tsx
git commit -m "feat(capital): CapitalSheet for create / revalue / history / disposal / delete"
```

### Task 22: `CapitalTrajectoryChart`

**Файл:** `frontend/src/components/CapitalTrajectoryChart.tsx`

Recharts LineChart с одной линией. Переключатель периода.

- [ ] **Step 1: Проверить, что recharts уже в зависимостях фронта**

Команда: `grep '"recharts"' frontend/package.json`

Если нет — добавить: `cd frontend && npm install recharts` и закоммитить package.json.

- [ ] **Step 2: Создать компонент**

```tsx
import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { fetchCapitalTrajectory } from '../api';
import type { CapitalTrajectory } from '../types/api';
import { Button } from './ui/button';

type Range = '6m' | '1y' | '3y' | 'all';
const labels: Record<Range, string> = { '6m': '6 мес.', '1y': '1 год', '3y': '3 года', 'all': 'Всё' };

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtCompact = (n: number) => {
    if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'М';
    if (Math.abs(n) >= 1_000) return Math.round(n / 1000) + 'к';
    return String(n);
};

interface Props {
    refreshSignal: number;
}

export default function CapitalTrajectoryChart({ refreshSignal }: Props) {
    const [range, setRange] = useState<Range>('1y');
    const [trajectory, setTrajectory] = useState<CapitalTrajectory | null>(null);

    useEffect(() => {
        const today = new Date();
        let from: string | undefined;
        if (range === '6m') from = isoMinusMonths(today, 6);
        else if (range === '1y') from = isoMinusMonths(today, 12);
        else if (range === '3y') from = isoMinusMonths(today, 36);
        // all: undefined → backend выберет earliest

        fetchCapitalTrajectory(from).then(setTrajectory).catch(console.error);
    }, [range, refreshSignal]);

    if (!trajectory) return null;
    // Считаем дельту от предыдущей точки — нужно в tooltip.
    const data = trajectory.points.map((p, idx, arr) => ({
        ...p,
        dateLabel: shortDate(p.date),
        deltaFromPrev: idx === 0 ? null : p.capital - arr[idx - 1].capital,
    }));

    return (
        <div className="rounded-lg p-4"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-medium">Динамика капитала</h3>
                <div className="flex gap-1">
                    {(Object.keys(labels) as Range[]).map(r => (
                        <Button key={r} size="sm" variant={range === r ? 'default' : 'ghost'} onClick={() => setRange(r)}>
                            {labels[r]}
                        </Button>
                    ))}
                </div>
            </div>

            {data.length <= 1 ? (
                <div className="py-8 text-center space-y-3">
                    <svg width="80" height="40" className="inline-block" viewBox="0 0 80 40">
                        <circle cx="70" cy="20" r="4" fill="var(--color-accent, #6c63ff)" />
                    </svg>
                    <div className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        История появится по мере переоценок. Хочешь увидеть прошлое — добавь оценку задним числом из Sheet любого актива.
                    </div>
                </div>
            ) : (
                <ResponsiveContainer width="100%" height={220}>
                    <LineChart data={data}>
                        <XAxis dataKey="dateLabel" tick={{ fontSize: 11, fill: 'var(--color-text-muted)' }} />
                        <YAxis tickFormatter={fmtCompact} tick={{ fontSize: 11, fill: 'var(--color-text-muted)' }} />
                        <Tooltip content={<CustomTooltip />} />
                        <Line type="monotone" dataKey="capital" stroke="var(--color-accent, #6c63ff)" strokeWidth={2} dot={{ r: 3 }} />
                    </LineChart>
                </ResponsiveContainer>
            )}
        </div>
    );
}

function CustomTooltip({ active, payload }: any) {
    if (!active || !payload?.length) return null;
    const p = payload[0].payload;
    const deltaSign = p.deltaFromPrev != null && p.deltaFromPrev >= 0 ? '+' : '';
    return (
        <div className="rounded p-2 text-xs" style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="font-medium">{shortDate(p.date)}</div>
            <div>{fmt(p.capital)}</div>
            <div className="opacity-70 mt-1">cash {fmtCompact(p.liquid)} + активы {fmtCompact(p.assets)} − долги {fmtCompact(p.liabilities)}</div>
            {p.deltaFromPrev != null && (
                <div className="mt-1" style={{ color: p.deltaFromPrev >= 0 ? 'var(--color-success, #7ec699)' : 'var(--color-danger, #e88a8a)' }}>
                    {deltaSign}{fmtCompact(p.deltaFromPrev)} от прошлой точки
                </div>
            )}
        </div>
    );
}

function shortDate(iso: string): string {
    return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: '2-digit' });
}

function isoMinusMonths(d: Date, months: number): string {
    const r = new Date(d);
    r.setMonth(r.getMonth() - months);
    return r.toISOString().slice(0, 10);
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/CapitalTrajectoryChart.tsx
git commit -m "feat(capital): CapitalTrajectoryChart with recharts and range selector"
```

### Task 23: `CapitalTheoryDialog`

**Файл:** `frontend/src/components/CapitalTheoryDialog.tsx`

shadcn `Dialog` с текстом ~250 слов на русском.

- [ ] **Step 1: Проверить наличие `Dialog` в shadcn-юнах**

Команда: `ls frontend/src/components/ui/ | grep -i dialog`

Если есть — используем. Если нет — установить через shadcn CLI (детали в их docs) или использовать Sheet с `side="bottom"`.

- [ ] **Step 2: Создать компонент**

```tsx
import { Dialog, DialogContent, DialogHeader, DialogTitle } from './ui/dialog';

interface Props {
    open: boolean;
    onClose: () => void;
}

export default function CapitalTheoryDialog({ open, onClose }: Props) {
    return (
        <Dialog open={open} onOpenChange={o => !o && onClose()}>
            <DialogContent className="max-w-md">
                <DialogHeader>
                    <DialogTitle>Что такое капитал</DialogTitle>
                </DialogHeader>
                {/* Не оборачиваем в DialogDescription — он рендерится как <p>, а внутри нужны несколько абзацев. */}
                <div className="space-y-3 text-sm leading-relaxed">
                    <p>
                        <strong>Капитал</strong> — это всё, чем вы владеете, минус всё, что вы должны.
                        Формула: <code>активы − обязательства + деньги в наличии</code>.
                    </p>
                    <p>
                        Учёт капитала — отдельная медитация раз в 1-3 месяца, не чаще. Зашли, обновили
                        стоимость активов и остатки долгов, посмотрели динамику, ушли. Это не ежедневный
                        журнал — для него у вас есть Дашборд и Журнал.
                    </p>
                    <p>
                        <strong>Что считать активом:</strong> квартира и другая недвижимость, автомобиль,
                        ценное имущество (техника, ювелирка), наличная валюта по текущему курсу.
                        Деньги на банковских счетах и копилки уже считаются — отдельно их вводить не нужно.
                    </p>
                    <p>
                        <strong>Что считать обязательством:</strong> остаток тела долга по ипотеке, потребительскому
                        кредиту, кредитной карте. Не сам ежемесячный платёж, а именно сколько ещё нужно вернуть.
                    </p>
                    <p>
                        <strong>Связь со стратегическим планированием:</strong> покупка автомобиля
                        влияет и на ежемесячные расходы (видно в Бюджете), и на капитал (минус 1.5М в
                        момент покупки, плюс долг по кредиту). График капитала покажет, движетесь ли
                        вы туда, куда хотите — растёт ли ваше состояние во времени и как крупные
                        решения отражаются на нём.
                    </p>
                </div>
            </DialogContent>
        </Dialog>
    );
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/CapitalTheoryDialog.tsx
git commit -m "feat(capital): CapitalTheoryDialog with theory text"
```

### Task 24: Подключить всё в `Capital.tsx`

**Файл:** `frontend/src/pages/Capital.tsx`

Заменяем заглушки на реальные компоненты.

- [ ] **Step 1: Обновить страницу**

Изменения относительно версии из Task 18:
1. Импорты `CapitalSheet`, `CapitalTrajectoryChart`, `CapitalTheoryDialog`.
2. State: `sheetMode`, `theoryOpen`, `refreshTick`.
3. Хэндлеры `handleItemClick` и `handleCreate` теперь устанавливают `sheetMode`.
4. Кнопка-«?» в header открывает `theoryOpen`.
5. Под колонками рендерим `CapitalTrajectoryChart`.
6. После Sheet — рендерим компонент с `mode={sheetMode}`.

```tsx
// frontend/src/pages/Capital.tsx (полная версия)
import { useEffect, useState, useCallback } from 'react';
import { HelpCircle } from 'lucide-react';
import { fetchCapitalSummary } from '../api';
import type { CapitalSummary, CapitalItem, CapitalItemKind } from '../types/api';
import CapitalSummaryCard from '../components/CapitalSummaryCard';
import CapitalItemList from '../components/CapitalItemList';
import CapitalSheet from '../components/CapitalSheet';
import CapitalTrajectoryChart from '../components/CapitalTrajectoryChart';
import CapitalTheoryDialog from '../components/CapitalTheoryDialog';
import { Button } from '../components/ui/button';

interface Props {
    refreshSignal?: number;
}

type SheetMode =
    | { type: 'create'; kind: CapitalItemKind }
    | { type: 'view'; itemId: string }
    | null;

export default function Capital({ refreshSignal }: Props) {
    const [summary, setSummary] = useState<CapitalSummary | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [sheetMode, setSheetMode] = useState<SheetMode>(null);
    const [theoryOpen, setTheoryOpen] = useState(false);
    const [refreshTick, setRefreshTick] = useState(0);

    const reload = useCallback(() => {
        fetchCapitalSummary()
            .then(setSummary)
            .catch(e => setError(String(e)));
    }, []);

    useEffect(() => { reload(); }, [reload, refreshSignal, refreshTick]);

    const onChanged = () => setRefreshTick(t => t + 1);

    if (error) return <div className="p-4 text-sm text-red-400">Ошибка: {error}</div>;
    if (!summary) return <div className="p-4 text-sm" style={{ color: 'var(--color-text-muted)' }}>Загрузка…</div>;

    const isEmpty = summary.items.length === 0;

    return (
        <div className="max-w-2xl mx-auto p-4 space-y-4 pb-24">
            <header className="flex items-center justify-between">
                <h1 className="text-xl font-semibold">Капитал</h1>
                <Button variant="ghost" size="icon" aria-label="Что такое капитал?" onClick={() => setTheoryOpen(true)}>
                    <HelpCircle size={20} />
                </Button>
            </header>

            {isEmpty ? (
                <EmptyState
                    onCreate={k => setSheetMode({ type: 'create', kind: k })}
                    onTheory={() => setTheoryOpen(true)}
                />
            ) : (
                <>
                    <CapitalSummaryCard summary={summary} />
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <CapitalItemList
                            kind="ASSET"
                            items={summary.items.filter(i => i.kind === 'ASSET')}
                            onItemClick={item => setSheetMode({ type: 'view', itemId: item.id })}
                            onCreate={() => setSheetMode({ type: 'create', kind: 'ASSET' })} />
                        <CapitalItemList
                            kind="LIABILITY"
                            items={summary.items.filter(i => i.kind === 'LIABILITY')}
                            onItemClick={item => setSheetMode({ type: 'view', itemId: item.id })}
                            onCreate={() => setSheetMode({ type: 'create', kind: 'LIABILITY' })} />
                    </div>
                    <CapitalTrajectoryChart refreshSignal={refreshTick} />
                </>
            )}

            <CapitalSheet
                open={sheetMode !== null}
                mode={sheetMode}
                onClose={() => setSheetMode(null)}
                onChanged={onChanged}
            />
            <CapitalTheoryDialog open={theoryOpen} onClose={() => setTheoryOpen(false)} />
        </div>
    );
}

function EmptyState({ onCreate, onTheory }: { onCreate: (k: CapitalItemKind) => void; onTheory: () => void }) {
    return (
        <div className="text-center py-16 px-4">
            <h2 className="text-lg font-semibold mb-2">Здесь будет ваш капитал</h2>
            <p className="text-sm mb-6 max-w-md mx-auto" style={{ color: 'var(--color-text-muted)' }}>
                Добавьте первый актив (квартира, авто, валюта, ценное имущество) или обязательство (ипотека, кредит).
                Деньги на счетах и копилки уже считаются автоматически.
            </p>
            <div className="flex gap-2 justify-center flex-wrap">
                <Button onClick={() => onCreate('ASSET')}>+ Добавить актив</Button>
                <Button variant="outline" onClick={() => onCreate('LIABILITY')}>+ Добавить обязательство</Button>
                <Button variant="ghost" onClick={onTheory}>Что такое капитал? →</Button>
            </div>
        </div>
    );
}
```

- [ ] **Step 2: End-to-end smoke-test**

Запустить dev server и пройти сценарий:
1. Открыть `/capital` — увидеть empty state.
2. Нажать «+ Добавить актив» — открывается Sheet.
3. Ввести «Квартира», 8500000, дата сегодня → сохранить.
4. Sheet закрылся, на странице видны hero-сводка («Капитал = 8 500 000 ₽»), колонка «АКТИВЫ», график.
5. Кликнуть на «Квартира» → Sheet с историей открывается.
6. Добавить переоценку 8700000 на сегодняшнюю дату → currentValue обновляется.
7. Нажать «Удалить безвозвратно», подтвердить → item исчезает, страница возвращается к empty state.
8. Открыть «?» — увидеть текст теории.

Все шаги должны работать без консольных ошибок.

- [ ] **Step 3: Запустить frontend lint и сборку**

Команда: `cd frontend && npm run build`

Ожидаемый результат: build проходит без ошибок TypeScript.

- [ ] **Step 4: Финальный commit**

```bash
git add frontend/src/pages/Capital.tsx
git commit -m "feat(capital): wire Sheet, chart, theory into Capital page"
```

---

## Финальная верификация

После завершения всех задач:

- [ ] **Полный backend тест**: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test` — все тесты PASS.
- [ ] **Frontend сборка**: `cd frontend && npm run build` — проходит.
- [ ] **End-to-end smoke**: пройти полный сценарий из Task 24 Step 2.
- [ ] **Запуск приложения целиком** (docker-compose или вручную): `/capital` доступен, навигация работает, все CRUD-операции работают.
- [ ] Если есть `git diff main` — посмотреть глазами, нет ли мусора (TODO, console.log, закомментированный код).

## Открытые вопросы для будущих PR (НЕ делать в этом)

- Финальный текст `CapitalTheoryDialog` — текст в Task 23 черновой, его можно ещё отполировать.
- Связь recurring-платежей по ипотеке с обязательством (авто-уменьшение тела долга) — отдельная фича, спецы пока нет.
- Авто-конвертация `TargetFund(CREDIT)` в asset+liability при покупке — отдельная фича.
- Линия капитала на стратегическом графике — ждёт редизайна стратегического планирования.
