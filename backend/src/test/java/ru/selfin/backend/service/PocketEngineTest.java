package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.FallbackKind;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Табличные тесты формулы кармашка. Чистый движок, ни одного мока (спека §9). */
class PocketEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    // ── хелперы ──────────────────────────────────────────────────────────────

    static EventSnapshot plan(EventType type, LocalDate date, long amount, Priority prio) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.PLANNED,
                prio, dec(amount), null, null, false, "plan");
    }

    private static EventSnapshot fact(EventType type, LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, "fact");
    }

    /**
     * Факт с явным временем ЗАПИСИ (ANO-82). Значимо только в день чекпоинта: там решает
     * оно, а не дата. Перегрузка без него ставит {@code createdAt = null} — тогда день
     * чекпоинта решается по-старому, и остальные тесты класса не затронуты.
     */
    private static EventSnapshot fact(EventType type, LocalDate date, long amount,
                                      LocalDateTime createdAt) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, "fact", null, createdAt);
    }

    private static EventSnapshot executedPlan(EventType type, LocalDate date, long planned) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.EXECUTED,
                Priority.MEDIUM, dec(planned), null, null, false, "executed plan");
    }

    /** Легаси-строка FUND_TRANSFER: eventKind=PLAN, но factAmount заполнен (спека §3.2). */
    private static EventSnapshot legacyTransfer(LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.FUND_TRANSFER, EventKind.PLAN,
                EventStatus.EXECUTED, Priority.MEDIUM, null, dec(amount), null, false, "transfer");
    }

    private static EventSnapshot planNamed(EventType type, LocalDate date, long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.PLANNED,
                Priority.MEDIUM, dec(amount), null, null, false, description);
    }

    private static EventSnapshot wishlist(WishlistStatus st, LocalDate date, long amount, boolean converted) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.LOW, dec(amount), null, st, converted, "хотелка");
    }

    /** Синтетический взнос в копилку (ANO-16 §6): id null, syntheticKind задан. */
    private static EventSnapshot contribution(LocalDate date, long amount, String fundName) {
        return new EventSnapshot(null, date, EventType.EXPENSE, EventKind.PLAN, EventStatus.PLANNED,
                Priority.MEDIUM, dec(amount), null, null, false, fundName,
                ru.selfin.backend.dto.pocket.SyntheticKind.SAVINGS_CONTRIBUTION);
    }

    /** План с известным id — чтобы к нему можно было привязать факт (ANO-155). */
    private static EventSnapshot planWithId(UUID id, EventType type, LocalDate date, long amount) {
        return new EventSnapshot(id, date, type, EventKind.PLAN, EventStatus.PLANNED,
                Priority.MEDIUM, dec(amount), null, null, false, "plan", null, null, null);
    }

    /** Факт, привязанный к плану: гасит обязательство на свою сумму (ANO-155). */
    private static EventSnapshot factFor(UUID planId, EventType type, LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, "fact", null, null, planId);
    }

    private static BigDecimal dec(long v) { return BigDecimal.valueOf(v); }

    private static PocketInputBuilder base() { return PocketInputBuilder.create(); }

    /** Билдер входа с дефолтами: чекпоинт 10 000 на TODAY, горизонт NEXT_INCOME до 15.03, буфер 0. */
    static class PocketInputBuilder {
        LocalDate asOf = TODAY;
        BigDecimal checkpoint = dec(10_000);
        LocalDate checkpointDate = TODAY;
        LocalDateTime checkpointCreatedAt = null;
        List<EventSnapshot> events = List.of();
        List<EventSnapshot> wishlistEvents = List.of();
        List<EventSnapshot> overdue = List.of();
        List<EventSnapshot> releasedOverdue = List.of();
        PocketScope scope = new PocketScope(PocketScope.Type.NEXT_INCOME, null, null);
        LocalDate horizonEnd = LocalDate.of(2026, 3, 15);
        FallbackKind fallback = FallbackKind.NONE;
        BigDecimal buffer = BigDecimal.ZERO;
        BigDecimal forecast = BigDecimal.ZERO;
        List<String> contributors = List.of();
        java.util.Map<java.time.YearMonth, BigDecimal> futureForecast = java.util.Map.of();
        BigDecimal otherAccountsBalance = null;
        BigDecimal creditRestoreReserve = null;
        BigDecimal semiLiquidBalance = null;

        static PocketInputBuilder create() { return new PocketInputBuilder(); }
        PocketInputBuilder events(EventSnapshot... e) { this.events = List.of(e); return this; }
        PocketInputBuilder wishlist(EventSnapshot... e) { this.wishlistEvents = List.of(e); return this; }
        PocketInputBuilder overdue(EventSnapshot... e) { this.overdue = List.of(e); return this; }
        /** Просрочка, удержанная якорем вне резерва (ANO-79) — только для объяснения. */
        PocketInputBuilder releasedOverdue(EventSnapshot... e) { this.releasedOverdue = List.of(e); return this; }
        PocketInputBuilder buffer(long b) { this.buffer = dec(b); return this; }
        PocketInputBuilder forecast(long f, String... names) {
            this.forecast = dec(f); this.contributors = List.of(names); return this;
        }
        PocketInputBuilder horizon(LocalDate end) { this.horizonEnd = end; return this; }
        /** Прогноз сверх плана по будущим месяцам (ANO-36). */
        PocketInputBuilder futureForecast(java.time.YearMonth month, long amount) {
            var m = new java.util.LinkedHashMap<>(this.futureForecast);
            m.put(month, dec(amount));
            this.futureForecast = m;
            return this;
        }
        PocketInputBuilder monthsScope(int n, LocalDate end) {
            this.scope = new PocketScope(PocketScope.Type.MONTHS, n, null); this.horizonEnd = end; return this;
        }
        PocketInputBuilder noCheckpoint() { this.checkpoint = BigDecimal.ZERO; this.checkpointDate = null; return this; }
        PocketInputBuilder checkpointDate(LocalDate d) { this.checkpointDate = d; return this; }
        /** Время ВВОДА якоря (ANO-82): для дня якоря решает оно, а не дата. */
        PocketInputBuilder checkpointCreatedAt(LocalDateTime t) { this.checkpointCreatedAt = t; return this; }
        PocketInputBuilder fallback() { this.fallback = FallbackKind.NO_INCOMES; return this; }
        PocketInputBuilder fallback(FallbackKind kind) { this.fallback = kind; return this; }
        PocketInputBuilder secondIncomeScope(LocalDate end) {
            this.scope = new PocketScope(PocketScope.Type.SECOND_INCOME, null, null);
            this.horizonEnd = end; return this;
        }
        /** Свободные деньги прочих счетов (ANO-9 §4.1) — по умолчанию null (счетов, кроме дефолтного, нет). */
        PocketInputBuilder otherAccountsBalance(long v) { this.otherAccountsBalance = dec(v); return this; }
        /** Резерв возврата кредиток к планке (ANO-9 §4.2) — по умолчанию null (планок нет). */
        PocketInputBuilder creditRestoreReserve(long v) { this.creditRestoreReserve = dec(v); return this; }
        /** Полу-ликвид: вклады (ANO-9 §4.3) — по умолчанию null (вкладов нет). */
        PocketInputBuilder semiLiquidBalance(long v) { this.semiLiquidBalance = dec(v); return this; }

        PocketInput build() {
            return new PocketInput(asOf, checkpoint, checkpointDate, checkpointCreatedAt,
                    events, wishlistEvents, overdue, releasedOverdue,
                    scope, horizonEnd, fallback, buffer, forecast, contributors, futureForecast,
                    otherAccountsBalance, creditRestoreReserve, semiLiquidBalance);
        }
    }

    // ── сходимость со старой моделью (мартовский пример спеки free-money) ────

    @Test
    @DisplayName("Мартовский пример: план на месяц, дефолтный скоуп до зп 5.03 — min = конец горизонта")
    void marchExample_defaultScope() {
        // Checkpoint 10 000 на 1.03; зп 100 000 5-го (горизонт), аренда 30 000 10-го — ЗА горизонтом
        PocketInput in = base()
                .events(plan(EventType.INCOME, LocalDate.of(2026, 3, 5), 100_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 10), 30_000, Priority.HIGH))
                .horizon(LocalDate.of(2026, 3, 5))
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        // До зп трат нет: траектория 10 000 → min в день 0 → 5-го +зп. Min = 10 000.
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(10_000));
        assertThat(r.minPoint().balance()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
    }

    // ====== ANO-155: факт гасит план на свою сумму, а не снимает целиком ======

    @Test
    @DisplayName("ANO-155: частичный факт оставляет остаток плана удержанным")
    void partialFact_leavesRemainderReserved() {
        // Продукты 20 000 на 10.03, чек 300 сегодня. Раньше план уходил целиком и
        // кармашек вырастал на 19 700 ровно тогда, когда человек только начал тратить.
        UUID planId = UUID.randomUUID();
        PocketInput in = base().checkpointDate(TODAY.minusDays(1))
                .events(planWithId(planId, EventType.EXPENSE, LocalDate.of(2026, 3, 10), 20_000),
                        factFor(planId, EventType.EXPENSE, TODAY, 300))
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.currentBalance())
                .as("чек уже ушёл со счёта")
                .isEqualByComparingTo(dec(9_700));
        assertThat(r.pocket())
                .as("удерживается 19 700 — не ноль (план снят) и не 20 000 (чек не зачтён)")
                .isEqualByComparingTo(dec(-10_000));
    }

    @Test
    @DisplayName("ANO-155: факт на всю сумму снимает план целиком")
    void fullFact_releasesPlanEntirely() {
        UUID planId = UUID.randomUUID();
        PocketInput in = base().checkpointDate(TODAY.minusDays(1))
                .events(planWithId(planId, EventType.EXPENSE, LocalDate.of(2026, 3, 10), 6_000),
                        factFor(planId, EventType.EXPENSE, TODAY, 6_000))
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.pocket())
                .as("обязательство погашено полностью — удерживать нечего")
                .isEqualByComparingTo(dec(4_000));
    }

    @Test
    @DisplayName("ANO-155: переплата даёт остаток ноль, а не отрицательный")
    void overpay_givesZeroRemainder_notNegative() {
        // Отрицательный остаток ВЕРНУЛ бы деньги в кармашек: потратил больше — стало больше.
        UUID planId = UUID.randomUUID();
        PocketInput in = base().checkpointDate(TODAY.minusDays(1))
                .events(planWithId(planId, EventType.EXPENSE, LocalDate.of(2026, 3, 10), 6_000),
                        factFor(planId, EventType.EXPENSE, TODAY, 7_300))
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.pocket())
                .as("10 000 − 7 300; переплата не возвращается прибавкой")
                .isEqualByComparingTo(dec(2_700));
    }

    @Test
    @DisplayName("ANO-155: легаси-план с собственным factAmount по-прежнему не удерживается")
    void planWithOwnFactAmount_staysExcluded() {
        // Путь PATCH /events/{id}/fact пишет число в сам план и ставит EXECUTED.
        // Такие строки удерживать нельзя — иначе расход посчитается дважды.
        PocketInput in = base().checkpointDate(TODAY.minusDays(1))
                .events(new EventSnapshot(UUID.randomUUID(), LocalDate.of(2026, 3, 10),
                        EventType.EXPENSE, EventKind.PLAN, EventStatus.EXECUTED, Priority.MEDIUM,
                        dec(20_000), dec(5_000), null, false, "легаси", null, null, null))
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
    }

    @Test
    @DisplayName("Факт вытесняет план: PLAN(EXECUTED) пропущен, FACT посчитан")
    void factDisplacesPlan() {
        // Чекпоинт вчера: факты СЕГОДНЯ считаются поверх него (§5 ANO-15)
        PocketInput in = base().checkpointDate(TODAY.minusDays(1))
                .events(executedPlan(EventType.INCOME, LocalDate.of(2026, 3, 1), 100_000),
                        fact(EventType.INCOME, LocalDate.of(2026, 3, 1), 95_000))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(105_000)); // 10 000 + 95 000, не 205 000
    }

    @Test
    @DisplayName("Легаси FUND_TRANSFER (PLAN + factAmount) учтён как факт")
    void legacyFundTransferCountedAsFact() {
        PocketInput in = base().checkpointDate(TODAY.minusDays(1))
                .events(legacyTransfer(LocalDate.of(2026, 3, 1), 3_000))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance()).isEqualByComparingTo(dec(7_000));
    }

    @Test
    @DisplayName("ANO-28: факт дня чекпоинта, записанный ДО сверки, не задваивается")
    void factOnCheckpointDay_recordedBefore_notDoubleCounted() {
        PocketInput in = base()
                .checkpointCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                .events(fact(EventType.EXPENSE, TODAY, 4_000,
                        LocalDateTime.of(2026, 3, 1, 9, 0)))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance())
                .as("трата была на экране, когда вводили число из банка")
                .isEqualByComparingTo(dec(10_000));
    }

    @Test
    @DisplayName("ANO-82: факт дня чекпоинта, записанный ПОСЛЕ сверки, считается")
    void factOnCheckpointDay_recordedAfter_counts() {
        PocketInput in = base()
                .checkpointCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                .events(fact(EventType.EXPENSE, TODAY, 4_000,
                        LocalDateTime.of(2026, 3, 1, 18, 0)))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance())
                .as("сверился утром, записал вечером — ввод не должен пропадать")
                .isEqualByComparingTo(dec(6_000));
    }

    @Test
    @DisplayName("Время записи неизвестно — день чекпоинта решается по-старому (33 теста класса на этом)")
    void factOnCheckpointDay_unknownEntryTime_staysOut() {
        PocketInput in = base()
                .events(fact(EventType.EXPENSE, TODAY, 4_000))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance())
                .as("совместимая перегрузка ставит createdAt = null — прежнее поведение")
                .isEqualByComparingTo(dec(10_000));
    }

    // ── просрочка ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Просрочка через границу месяца резервируется в день 0")
    void overdueAcrossMonthBoundary() {
        PocketInput in = base()
                .overdue(plan(EventType.EXPENSE, LocalDate.of(2026, 2, 27), 6_000, Priority.HIGH))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.trajectory().get(0).balance()).isEqualByComparingTo(dec(4_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(4_000));
        assertThat(r.breakdown()).anySatisfy(l -> {
            assertThat(l.type()).isEqualTo(BreakdownType.OVERDUE_RESERVE);
            assertThat(l.amount()).isEqualByComparingTo(dec(-6_000));
        });
    }

    // ── граница «сегодня» ────────────────────────────────────────────────────

    @Test
    @DisplayName("Плановый доход сегодня НЕ считается (ждём факт), плановый расход сегодня — считается")
    void todayBoundary_conservativeAsymmetry() {
        PocketInput in = base()
                .events(plan(EventType.INCOME, TODAY, 100_000, Priority.HIGH),
                        plan(EventType.EXPENSE, TODAY, 2_000, Priority.MEDIUM))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.trajectory().get(0).balance()).isEqualByComparingTo(dec(8_000)); // 10 000 − 2 000, без +100 000
    }

    // ── провал в середине (dip-aware) ────────────────────────────────────────

    @Test
    @DisplayName("MONTHS:3 с провалом в середине: pocket = min траектории, не конец горизонта")
    void stretchedScope_dipInMiddle() {
        // 12.03 страховка −9 000 (провал до 1 000), 15.03 зп +100 000: конец = 101 000, но min = 1 000
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 9_000, Priority.HIGH),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 15), 100_000, Priority.HIGH))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(r.minPoint().balance()).isEqualByComparingTo(dec(1_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(1_000));
    }

    @Test
    @DisplayName("Breakdown складывается в TRAJECTORY_MIN на растянутом скоупе (суммы до дня минимума)")
    void breakdownArithmetic_sumsToMin() {
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 9_000, Priority.HIGH),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 15), 100_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 4, 20), 50_000, Priority.MEDIUM))
                .buffer(500)
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        BigDecimal starting = line(r, BreakdownType.STARTING_BALANCE).amount();
        BigDecimal expenses = line(r, BreakdownType.PLANNED_EXPENSES).amount(); // только до 12.03 → −9 000
        BigDecimal min = line(r, BreakdownType.TRAJECTORY_MIN).amount();
        assertThat(expenses).isEqualByComparingTo(dec(-9_000)); // расход 20.04 после минимума — не входит
        assertThat(starting.add(expenses)).isEqualByComparingTo(min);
        assertThat(min.add(line(r, BreakdownType.BUFFER).amount()))
                .isEqualByComparingTo(line(r, BreakdownType.POCKET).amount());
        // PLANNED_INCOME (15.03 — после минимума 12.03) в breakdown отсутствует
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.PLANNED_INCOME);
    }

    private static PocketResultDto.BreakdownLine line(PocketResultDto r, BreakdownType t) {
        return r.breakdown().stream().filter(l -> l.type() == t).findFirst().orElseThrow();
    }

    private static int indexOf(PocketResultDto r, BreakdownType t) {
        for (int i = 0; i < r.breakdown().size(); i++) {
            if (r.breakdown().get(i).type() == t) return i;
        }
        throw new AssertionError("нет строки " + t);
    }

    // ── ANO-79: что якорь снял с резерва ─────────────────────────────────────

    @Test
    @DisplayName("ANO-79: просрочка, снятая ре-якорем, объяснена строкой после кармашка")
    void releasedOverdue_explainedAfterPocket() {
        PocketResultDto r = PocketEngine.calculate(base()
                .releasedOverdue(planNamed(EventType.EXPENSE, LocalDate.of(2026, 2, 22), 5_000, "Страховка"),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 2, 24), 16_000, Priority.HIGH))
                .build());

        assertThat(line(r, BreakdownType.OVERDUE_RELEASED).amount())
                .as("ровно то, что перестало бронироваться")
                .isEqualByComparingTo(dec(21_000));
        assertThat(line(r, BreakdownType.OVERDUE_RELEASED).label())
                .as("причина названа действием человека, а не его ошибкой — правила 8 и 12")
                .isEqualTo("Больше не бронируется: остаток обновлён 01.03 (2 шт)");
        assertThat(line(r, BreakdownType.OVERDUE_RELEASED).details())
                .containsExactly("Страховка", "plan");
        assertThat(indexOf(r, BreakdownType.OVERDUE_RELEASED))
                .as("строка информационная — её место после кармашка, а не внутри арифметики")
                .isGreaterThan(indexOf(r, BreakdownType.POCKET));
    }

    @Test
    @DisplayName("ANO-79: снимать нечего — строки нет")
    void releasedOverdue_empty_noLine() {
        assertThat(PocketEngine.calculate(base().build()).breakdown())
                .noneMatch(l -> l.type() == BreakdownType.OVERDUE_RELEASED);
    }

    @Test
    @DisplayName("ANO-79: объяснение не входит в инвариант кармашка")
    void releasedOverdue_staysOutOfInvariant() {
        // Минимум обязан оказаться ПОСЛЕ всех трёх событий: строки разбивки считают суммы до
        // минимума, и всё, что за ним, в них не попадает — тогда инвариант проверять не на чем.
        // 10 000 − 4 000 + 1 000 − 3 000 = 4 000 в день 04.03, ниже стартовых 10 000.
        PocketInputBuilder b = base()
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 2), 4_000, Priority.MEDIUM),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 3), 1_000, Priority.MEDIUM),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 4), 3_000, Priority.MEDIUM));
        PocketResultDto without = PocketEngine.calculate(b.build());
        PocketResultDto with = PocketEngine.calculate(b
                .releasedOverdue(plan(EventType.EXPENSE, LocalDate.of(2026, 2, 20), 21_000, Priority.HIGH))
                .build());

        assertThat(with.pocket())
                .as("объяснение не двигает ответ на вопрос «сколько можно тратить»")
                .isEqualByComparingTo(without.pocket());
        assertThat(with.minPoint().balance()).isEqualByComparingTo(without.minPoint().balance());

        // STARTING − EXPENSES + INCOME = MIN, и новая строка в этой сумме не участвует
        BigDecimal starting = line(with, BreakdownType.STARTING_BALANCE).amount();
        BigDecimal expenses = line(with, BreakdownType.PLANNED_EXPENSES).amount();
        BigDecimal income = line(with, BreakdownType.PLANNED_INCOME).amount();
        assertThat(starting.add(expenses).add(income))
                .isEqualByComparingTo(with.minPoint().balance());
    }

    // ── буфер ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Буфер вычитается из min; буфер 0 → pocket = min, строка BUFFER опущена")
    void buffer() {
        PocketResultDto withBuffer = PocketEngine.calculate(base().buffer(3_000).build());
        assertThat(withBuffer.pocket()).isEqualByComparingTo(dec(7_000));

        PocketResultDto zeroBuffer = PocketEngine.calculate(base().build());
        assertThat(zeroBuffer.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(zeroBuffer.breakdown()).noneMatch(l -> l.type() == BreakdownType.BUFFER);
    }

    // ── хотелки (фильтр §3.2) ────────────────────────────────────────────────

    @Test
    @DisplayName("OPEN → кандидат, не вычитается; DISMISSED — игнор; FIXED-конвертированная — игнор")
    void wishlistFilter_openDismissedConverted() {
        PocketInput in = base()
                .events(wishlist(WishlistStatus.DISMISSED, LocalDate.of(2026, 3, 10), 5_000, false),
                        wishlist(WishlistStatus.FIXED, LocalDate.of(2026, 3, 10), 7_000, true))
                .wishlist(wishlist(WishlistStatus.OPEN, null, 20_000, false))
                .horizon(LocalDate.of(2026, 3, 15))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000)); // ни одна не съела
        assertThat(r.wishlistCandidates()).hasSize(1);
        assertThat(r.wishlistCandidates().get(0).fixed()).isFalse();
        assertThat(line(r, BreakdownType.WISHLIST_INFO).amount()).isEqualByComparingTo(dec(20_000));
    }

    @Test
    @DisplayName("ANO-103: вернувшаяся в OPEN сконвертированная хотелка не становится кандидатом второй раз")
    void wishlistFilter_openButConverted_isNotACandidate() {
        // До V22 это состояние было недостижимо: его запрещало check-ограничение в базе,
        // и фильтр кандидатов пропускал «OPEN любые». V22 ограничение снимает — возврат в
        // обсуждение с сохранением артефакта обещан спекой, — и состояние становится
        // достижимым. Хотелка, которую уже превратили в план, обязана считаться ОДИН раз:
        // её план лежит в траектории, и показывать её ещё и в кандидатах значит рисовать
        // две неотличимые копии одного решения (форма ANO-106) и завышать WISHLIST_INFO.
        PocketInput in = base()
                .wishlist(wishlist(WishlistStatus.OPEN, null, 20_000, true))
                .horizon(LocalDate.of(2026, 3, 15))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.wishlistCandidates()).isEmpty();
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.WISHLIST_INFO);
    }

    @Test
    @DisplayName("ANO-103: OPEN-неконвертированная кандидатом остаётся — фильтр режет по converted, а не по OPEN")
    void wishlistFilter_openNotConverted_staysACandidate() {
        // Парный к предыдущему. Без него правка «не пускать converted» могла бы выродиться
        // в «не пускать OPEN вовсе» и оба теста прошли бы поодиночке.
        PocketInput in = base()
                .wishlist(wishlist(WishlistStatus.OPEN, null, 20_000, false))
                .horizon(LocalDate.of(2026, 3, 15))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.wishlistCandidates()).hasSize(1);
        assertThat(line(r, BreakdownType.WISHLIST_INFO).amount()).isEqualByComparingTo(dec(20_000));
    }

    @Test
    @DisplayName("FIXED-неконвертированная с датой в окне режет траекторию; без даты — кандидат fixed=true")
    void wishlistFilter_fixedUnconverted() {
        PocketInput in = base()
                .events(wishlist(WishlistStatus.FIXED, LocalDate.of(2026, 3, 10), 4_000, false))
                .wishlist(wishlist(WishlistStatus.FIXED, null, 15_000, false))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(6_000)); // 10 000 − 4 000
        assertThat(r.wishlistCandidates()).hasSize(1);
        assertThat(r.wishlistCandidates().get(0).fixed()).isTrue();
    }

    // ── прогноз незапланированных ────────────────────────────────────────────

    @Test
    @DisplayName("Прогноз размазан по дням до конца месяца и виден явной строкой")
    void unplannedForecast_spread() {
        // Окно 2.03..15.03 (горизонт раньше конца месяца) = 14 дней, прогноз 1 400 → 100/день
        PocketInput in = base().forecast(1_400, "Продукты").build();
        PocketResultDto r = PocketEngine.calculate(in);

        // ANO-80: величина и размазка прежние, но живут во ВТОРОМ числе. Главное число
        // прогноза не видит — предположение не входит в сумму, которой распоряжаются.
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocketWithForecast()).isEqualByComparingTo(dec(8_600)); // 10 000 − 1 400
        PocketResultDto.BreakdownLine f = line(r, BreakdownType.UNPLANNED_FORECAST);
        assertThat(f.amount()).isEqualByComparingTo(dec(-1_400));
        assertThat(f.details()).containsExactly("Продукты");
    }

    @Test
    @DisplayName("ANO-36: прогноз будущего месяца гнёт траекторию, а не только текущий месяц")
    void futureForecast_bendsTrajectoryBeyondCurrentMonth() {
        // Ловушка, на которой я сам споткнулся: траектория может РАСТИ и с прогнозом,
        // поэтому проверять надо не «падает ли», а насколько медленнее растёт.
        LocalDate horizon = LocalDate.of(2026, 4, 30);
        java.time.YearMonth april = java.time.YearMonth.of(2026, 4);

        PocketInput without = base().horizon(horizon)
                .monthsScope(3, horizon).build();
        PocketInput with = base().horizon(horizon)
                .monthsScope(3, horizon)
                .futureForecast(april, 30_000).build();

        PocketResultDto rWithout = PocketEngine.calculate(without);
        PocketResultDto rWith = PocketEngine.calculate(with);

        // ANO-80: прогноз гнёт ВТОРУЮ линию. Основная от него не зависит вовсе — иначе
        // главное число и график противоречили бы друг другу на одном экране.
        assertThat(lastBalance(rWith))
                .as("основная линия — только планы, прогноз её не трогает")
                .isEqualByComparingTo(lastBalance(rWithout));
        assertThat(lastBalance(rWith).subtract(lastForecastBalance(rWith)))
                .as("за апрель должно уйти ровно 30 000 сверх плана")
                .isEqualByComparingTo("30000");
    }

    private static BigDecimal lastBalance(ru.selfin.backend.dto.pocket.PocketResultDto r) {
        var t = r.trajectory();
        return t.get(t.size() - 1).balance();
    }

    private static BigDecimal lastForecastBalance(ru.selfin.backend.dto.pocket.PocketResultDto r) {
        var t = r.trajectory();
        return t.get(t.size() - 1).balanceWithForecast();
    }

    @Test
    @DisplayName("asOfDate = последний день месяца → окно прогноза пусто, строка опущена")
    void unplannedForecast_emptyWindow() {
        LocalDate eom = LocalDate.of(2026, 3, 31);
        PocketInput in = base().forecast(5_000, "Продукты").build();
        in = new PocketInput(eom, in.checkpointAmount(), eom, in.checkpointCreatedAt(),
                in.events(), in.wishlistEvents(),
                in.overdueEvents(), in.releasedOverdueEvents(),
                in.scope(), LocalDate.of(2026, 4, 5), FallbackKind.NONE,
                in.bufferAmount(), in.unplannedForecast(), in.forecastContributors(), in.futureForecast(),
                in.otherAccountsBalance(), in.creditRestoreReserve(), in.semiLiquidBalance());
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.UNPLANNED_FORECAST);
    }

    @Test
    @DisplayName("Траектория несёт дневные суммы: income/expense по дням, прогноз входит в expense")
    void trajectoryDailySums() {
        // Чекпоинт 10 000; просрочка 1 000; расход сегодня 500; 5.03 доход 20 000 и расход 3 000;
        // прогноз 1 400 на окно 2.03..15.03 (14 дней → 100/день)
        PocketInput in = base()
                .overdue(plan(EventType.EXPENSE, LocalDate.of(2026, 2, 20), 1_000, Priority.HIGH))
                .events(plan(EventType.EXPENSE, TODAY, 500, Priority.MEDIUM),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 5), 20_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 5), 3_000, Priority.MEDIUM))
                .forecast(1_400, "Продукты")
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        PocketResultDto.TrajectoryPoint day0 = r.trajectory().get(0);
        assertThat(day0.income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(day0.expense()).isEqualByComparingTo(dec(1_500)); // просрочка 1 000 + сегодня 500

        PocketResultDto.TrajectoryPoint mar5 = r.trajectory().stream()
                .filter(p -> p.date().equals(LocalDate.of(2026, 3, 5))).findFirst().orElseThrow();
        assertThat(mar5.income()).isEqualByComparingTo(dec(20_000));
        // ANO-80: прогноз больше не подмешивается в expense — поле означает расход по планам.
        assertThat(mar5.expense()).isEqualByComparingTo(dec(3_000));

        // Инвариант: balance(i) = balance(i-1) + income(i) − expense(i) — на каждой точке после нулевой
        for (int i = 1; i < r.trajectory().size(); i++) {
            PocketResultDto.TrajectoryPoint prev = r.trajectory().get(i - 1);
            PocketResultDto.TrajectoryPoint cur = r.trajectory().get(i);
            assertThat(prev.balance().add(cur.income()).subtract(cur.expense()))
                    .isEqualByComparingTo(cur.balance());
        }
    }

    @Test
    @DisplayName("minPoint.drivenBy = самый крупный плановый расход дня минимума; в день 0 — null")
    void minPointDrivenBy() {
        // Провал 12.03: страховка 9 000 + кафе 2 000; зп 15.03 возвращает вверх
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(planNamed(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 9_000, "Страховка"),
                        planNamed(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 2_000, "Кафе"),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 15), 100_000, Priority.HIGH))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(r.minPoint().drivenBy()).isEqualTo("Страховка");

        // Минимум в день 0 (трат в будущем нет) → drivenBy null
        PocketResultDto flat = PocketEngine.calculate(base().build());
        assertThat(flat.minPoint().drivenBy()).isNull();

        // ANO-80 убрал случай «минимум создан размазкой прогноза, виновника нет»: главный
        // минимум прогноза не видит и потому всегда стоит на конкретном плановом расходе.
        // Размазка двигает только прогнозный минимум — у него виновника действительно нет.
        PocketResultDto smeared = PocketEngine.calculate(base().forecast(1_400, "Продукты").build());
        assertThat(smeared.minPoint().date())
                .as("плановых расходов нет — главный минимум остаётся в дне 0")
                .isEqualTo(TODAY);
        assertThat(smeared.minPoint().drivenBy()).isNull();
        assertThat(smeared.minPointWithForecast().date())
                .as("прогнозный минимум размазка всё же двигает")
                .isNotEqualTo(TODAY);
        assertThat(smeared.minPointWithForecast().drivenBy()).isNull();
    }

    // ── без чекпоинта ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Нет чекпоинта — баланс от нуля по фактам")
    void noCheckpoint() {
        PocketInput in = base().noCheckpoint()
                .events(fact(EventType.INCOME, LocalDate.of(2026, 2, 20), 50_000),
                        fact(EventType.EXPENSE, LocalDate.of(2026, 2, 25), 20_000))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance()).isEqualByComparingTo(dec(30_000));
    }

    // ── горизонт-фолбэк ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Фолбэк-горизонт помечен флагом и label без даты дохода")
    void fallbackHorizonLabel() {
        PocketResultDto r = PocketEngine.calculate(base().fallback().horizon(TODAY.plusDays(30)).build());
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("30 дней вперёд (нет плановых доходов)");
    }

    // ── SECOND_INCOME (ANO-14 §4) ────────────────────────────────────────────

    @Test
    @DisplayName("SECOND_INCOME: label «до 2-го дохода dd.MM», fallback=false")
    void secondIncomeLabel() {
        PocketResultDto r = PocketEngine.calculate(
                base().secondIncomeScope(LocalDate.of(2026, 3, 20)).build());
        assertThat(r.horizon().fallback()).isFalse();
        assertThat(r.horizon().label()).isEqualTo("до 2-го дохода 20.03");
    }

    @Test
    @DisplayName("SECOND_INCOME-фолбэк с известным первым доходом: правдивый label, fallback=true")
    void secondIncomeFallback_knownFirstIncome() {
        PocketResultDto r = PocketEngine.calculate(base()
                .secondIncomeScope(LocalDate.of(2026, 3, 31))
                .fallback(FallbackKind.SECOND_NOT_FOUND).build());
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("до 31.03 (второй доход не найден)");
    }

    // ── информационный хвост календаря (ANO-24, спека §3.9) ─────────────────

    @Test
    @DisplayName("Горизонт завтра → траектория продлена хвостом до asOf+7 (8 точек)")
    void shortHorizon_trajectoryExtendedToMinWindow() {
        // Доход завтра (2.03) — горизонт NEXT_INCOME = завтра; расход в хвосте 5.03 виден
        PocketInput in = base()
                .events(plan(EventType.INCOME, LocalDate.of(2026, 3, 2), 50_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 5), 7_000, Priority.MEDIUM))
                .horizon(LocalDate.of(2026, 3, 2))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.trajectory()).hasSize(8); // сегодня + 7 дней
        assertThat(r.trajectory().get(7).date()).isEqualTo(TODAY.plusDays(7));
        assertThat(r.horizon().endDate()).isEqualTo(LocalDate.of(2026, 3, 2)); // горизонт не тронут
        // Хвост несёт плановые события: 5.03 баланс = 10 000 + 50 000 − 7 000
        assertThat(r.trajectory().get(4).balance()).isEqualByComparingTo(dec(53_000));
    }

    @Test
    @DisplayName("Провал в хвосте НЕ двигает минимум и кармашек — число только внутри горизонта")
    void dipInTail_doesNotAffectPocket() {
        PocketInput in = base()
                .events(plan(EventType.INCOME, LocalDate.of(2026, 3, 2), 50_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 5), 200_000, Priority.HIGH))
                .horizon(LocalDate.of(2026, 3, 2))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        // Внутри горизонта минимум = день 0 (10 000); хвостовой провал −140 000 виден
        // в траектории, но не в числе
        assertThat(r.minPoint().date()).isEqualTo(TODAY);
        assertThat(r.minPoint().balance()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.trajectory().get(4).balance()).isEqualByComparingTo(dec(-140_000));
    }

    @Test
    @DisplayName("Размаз прогноза НЕ протекает в хвост: окно размаза привязано к горизонту")
    void forecastSmear_notAppliedInTail() {
        PocketInput in = base()
                .events(plan(EventType.INCOME, LocalDate.of(2026, 3, 2), 50_000, Priority.HIGH))
                .horizon(LocalDate.of(2026, 3, 2))
                .forecast(1_000, "Продукты")
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        // ANO-80: прогноз ушёл из expense (поле означает расход по планам) и виден расхождением
        // двух линий. Весь размаз (1 день окна) лёг на 2.03; в хвосте расхождение не растёт.
        assertThat(r.trajectory().get(1).expense())
                .as("расход по планам в этот день нулевой — 1 000 были прогнозом")
                .isEqualByComparingTo(dec(0));
        for (int i = 1; i < r.trajectory().size(); i++) {
            PocketResultDto.TrajectoryPoint p = r.trajectory().get(i);
            assertThat(p.balance().subtract(p.balanceWithForecast()))
                    .as("расхождение линий набрано в окне и в хвост не протекает")
                    .isEqualByComparingTo(dec(1_000));
        }
    }

    @Test
    @DisplayName("SECOND_INCOME-фолбэк без доходов вовсе: старый честный label «нет плановых доходов»")
    void secondIncomeFallback_noIncomesAtAll() {
        PocketResultDto r = PocketEngine.calculate(base()
                .secondIncomeScope(TODAY.plusDays(30))
                .fallback(FallbackKind.NO_INCOMES).build());
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("30 дней вперёд (нет плановых доходов)");
    }

    // ── взносы в копилки (ANO-16 §6) ─────────────────────────────────────────

    @Test
    @DisplayName("Взносы в копилки: режут траекторию, отдельная строка breakdown, не в PLANNED_EXPENSES")
    void savingsContributions_separateBreakdownLine() {
        // 10 000 на 1.03; расход 2 000 5.03; взнос «Египет» 5 000 10.03; горизонт 15.03
        PocketResultDto r = PocketEngine.calculate(base()
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 5), 2_000, Priority.MEDIUM),
                        contribution(LocalDate.of(2026, 3, 10), 5_000, "Египет"))
                .build());

        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(r.minPoint().balance()).isEqualByComparingTo("3000");
        assertThat(r.minPoint().drivenBy()).isEqualTo("Египет");

        PocketResultDto.BreakdownLine contrib = line(r, BreakdownType.SAVINGS_CONTRIBUTIONS);
        assertThat(contrib.amount()).isEqualByComparingTo("-5000");
        assertThat(contrib.label()).isEqualTo("Взносы в копилки (1 шт)");
        assertThat(contrib.details()).containsExactly("Египет");

        assertThat(line(r, BreakdownType.PLANNED_EXPENSES).amount()).isEqualByComparingTo("-2000");
        assertThat(r.pocket()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("Взносы: инвариант breakdown с новой строкой сходится в MIN")
    void savingsContributions_breakdownInvariantHolds() {
        PocketResultDto r = PocketEngine.calculate(base()
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 3), 1_500, Priority.MEDIUM),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 4), 4_000, Priority.MEDIUM),
                        contribution(LocalDate.of(2026, 3, 6), 2_000, "Египет"),
                        contribution(LocalDate.of(2026, 3, 12), 3_000, "Горнолыжка"))
                .forecast(900, "Продукты")
                .build());

        // ANO-80: STARTING − EXPENSES − CONTRIB + INCOME = MIN. Прогноза в этой сумме больше
        // нет — он стоит за строкой POCKET как оговорка и в инвариант не входит.
        BigDecimal starting = line(r, BreakdownType.STARTING_BALANCE).amount();
        BigDecimal expenses = line(r, BreakdownType.PLANNED_EXPENSES).amount();
        BigDecimal contrib = line(r, BreakdownType.SAVINGS_CONTRIBUTIONS).amount();
        BigDecimal income = line(r, BreakdownType.PLANNED_INCOME).amount();
        assertThat(starting.add(expenses).add(contrib).add(income))
                .isEqualByComparingTo(r.minPoint().balance());
        assertThat(indexOf(r, BreakdownType.UNPLANNED_FORECAST))
                .as("строка прогноза существует, но стоит после кармашка")
                .isGreaterThan(indexOf(r, BreakdownType.POCKET));
        assertThat(line(r, BreakdownType.SAVINGS_CONTRIBUTIONS).label())
                .isEqualTo("Взносы в копилки (2 шт)");
        assertThat(line(r, BreakdownType.SAVINGS_CONTRIBUTIONS).details())
                .containsExactly("Египет", "Горнолыжка");
    }

    @Test
    @DisplayName("Взносы ПОСЛЕ минимума не входят в строку (суммы-до-минимума)")
    void savingsContributions_afterMinExcludedFromLine() {
        // Минимум создаётся расходом 8 000 5.03; взнос 10.03 идёт ПОСЛЕ дохода 8.03
        PocketResultDto r = PocketEngine.calculate(base()
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 5), 8_000, Priority.MEDIUM),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 8), 50_000, Priority.MEDIUM),
                        contribution(LocalDate.of(2026, 3, 10), 5_000, "Египет"))
                .build());

        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.SAVINGS_CONTRIBUTIONS);
    }

    // ── ANO-80: две кумуляты ───────────────────────────────────────────────────

    @Test
    @DisplayName("ANO-80: кармашек равен минимуму БЕЗ прогноза, хотя прогноз есть")
    void pocket_ignoresForecast_whileSecondNumberCountsIt() {
        PocketResultDto r = PocketEngine.calculate(base()
                .forecast(30_000, "Продукты")
                .build());

        assertThat(r.pocket())
                .as("главное число — только факты и планы: предположение им не распоряжаются")
                .isEqualByComparingTo("10000");
        assertThat(r.pocketWithForecast())
                .as("оговорка — то же число с обычными тратами")
                .isEqualByComparingTo("-20000");
    }

    @Test
    @DisplayName("ANO-80: POCKET + строка прогноза = второе число, даже когда минимумы в разные дни")
    void breakdown_forecastLine_isDifferenceBetweenTwoNumbers() {
        // План 50 000 10.03 топит траекторию раньше, чем это делает размазанный прогноз:
        // минимум без прогноза — 10.03, минимум с прогнозом — в конце горизонта 15.03.
        PocketResultDto r = PocketEngine.calculate(base()
                .events(planNamed(EventType.EXPENSE, LocalDate.of(2026, 3, 10), 50_000, "Шины"))
                .forecast(30_000, "Продукты")
                .build());

        assertThat(r.minPoint().date())
                .as("предпосылка теста: минимумы действительно в разные дни")
                .isNotEqualTo(r.minPointWithForecast().date());

        BigDecimal forecastLine = line(r, BreakdownType.UNPLANNED_FORECAST).amount();
        assertThat(r.pocket().add(forecastLine))
                .as("два числа на экране обязаны отличаться ровно на эту строку")
                .isEqualByComparingTo(r.pocketWithForecast());
    }

    @Test
    @DisplayName("ANO-80: строка прогноза стоит ПОСЛЕ кармашка и в инвариант не входит")
    void breakdown_forecastLine_sitsAfterPocket() {
        PocketResultDto r = PocketEngine.calculate(base()
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 3), 1_500, Priority.MEDIUM))
                .forecast(900, "Продукты")
                .build());

        assertThat(indexOf(r, BreakdownType.UNPLANNED_FORECAST))
                .as("до кармашка — из чего он сложился; после — оговорки")
                .isGreaterThan(indexOf(r, BreakdownType.POCKET));

        BigDecimal starting = line(r, BreakdownType.STARTING_BALANCE).amount();
        BigDecimal expenses = line(r, BreakdownType.PLANNED_EXPENSES).amount();
        assertThat(starting.add(expenses))
                .as("инвариант сходится БЕЗ прогноза: он больше не слагаемое кармашка")
                .isEqualByComparingTo(r.minPoint().balance());
    }

    @Test
    @DisplayName("ANO-80: прогноза нет — три поля null, а не нули")
    void noForecast_secondNumberIsNull() {
        PocketResultDto r = PocketEngine.calculate(base().build());

        assertThat(r.pocketWithForecast()).isNull();
        assertThat(r.minPointWithForecast()).isNull();
        assertThat(r.trajectory()).allSatisfy(p ->
                assertThat(p.balanceWithForecast()).isNull());
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.UNPLANNED_FORECAST);
    }

    @Test
    @DisplayName("ANO-80: прогнозная линия идёт ниже основной всюду, где прогноз накоплен")
    void forecastLine_staysBelowPlainLine() {
        PocketResultDto r = PocketEngine.calculate(base()
                .forecast(30_000, "Продукты")
                .build());

        assertThat(r.trajectory().stream().skip(1))
                .allSatisfy(p -> assertThat(p.balanceWithForecast())
                        .isLessThan(p.balance()));
    }
}
