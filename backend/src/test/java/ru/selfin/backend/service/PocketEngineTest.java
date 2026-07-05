package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.EventSnapshot;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Табличные тесты формулы кармашка. Чистый движок, ни одного мока (спека §9). */
class PocketEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    // ── хелперы ──────────────────────────────────────────────────────────────

    private static EventSnapshot plan(EventType type, LocalDate date, long amount, Priority prio) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.PLANNED,
                prio, dec(amount), null, null, false, "plan");
    }

    private static EventSnapshot fact(EventType type, LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, "fact");
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

    private static EventSnapshot wishlist(WishlistStatus st, LocalDate date, long amount, boolean converted) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.LOW, dec(amount), null, st, converted, "хотелка");
    }

    private static BigDecimal dec(long v) { return BigDecimal.valueOf(v); }

    private static PocketInputBuilder base() { return PocketInputBuilder.create(); }

    /** Билдер входа с дефолтами: чекпоинт 10 000 на TODAY, горизонт NEXT_INCOME до 15.03, буфер 0. */
    static class PocketInputBuilder {
        LocalDate asOf = TODAY;
        BigDecimal checkpoint = dec(10_000);
        LocalDate checkpointDate = TODAY;
        List<EventSnapshot> events = List.of();
        List<EventSnapshot> wishlistEvents = List.of();
        List<EventSnapshot> overdue = List.of();
        PocketScope scope = new PocketScope(PocketScope.Type.NEXT_INCOME, null, null);
        LocalDate horizonEnd = LocalDate.of(2026, 3, 15);
        boolean fallback = false;
        BigDecimal buffer = BigDecimal.ZERO;
        BigDecimal forecast = BigDecimal.ZERO;
        List<String> contributors = List.of();

        static PocketInputBuilder create() { return new PocketInputBuilder(); }
        PocketInputBuilder events(EventSnapshot... e) { this.events = List.of(e); return this; }
        PocketInputBuilder wishlist(EventSnapshot... e) { this.wishlistEvents = List.of(e); return this; }
        PocketInputBuilder overdue(EventSnapshot... e) { this.overdue = List.of(e); return this; }
        PocketInputBuilder buffer(long b) { this.buffer = dec(b); return this; }
        PocketInputBuilder forecast(long f, String... names) {
            this.forecast = dec(f); this.contributors = List.of(names); return this;
        }
        PocketInputBuilder horizon(LocalDate end) { this.horizonEnd = end; return this; }
        PocketInputBuilder monthsScope(int n, LocalDate end) {
            this.scope = new PocketScope(PocketScope.Type.MONTHS, n, null); this.horizonEnd = end; return this;
        }
        PocketInputBuilder noCheckpoint() { this.checkpoint = BigDecimal.ZERO; this.checkpointDate = null; return this; }
        PocketInputBuilder fallback() { this.fallback = true; return this; }

        PocketInput build() {
            return new PocketInput(asOf, checkpoint, checkpointDate, events, wishlistEvents, overdue,
                    scope, horizonEnd, fallback, buffer, forecast, contributors);
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

    @Test
    @DisplayName("Факт вытесняет план: PLAN(EXECUTED) пропущен, FACT посчитан")
    void factDisplacesPlan() {
        PocketInput in = base()
                .events(executedPlan(EventType.INCOME, LocalDate.of(2026, 3, 1), 100_000),
                        fact(EventType.INCOME, LocalDate.of(2026, 3, 1), 95_000))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(105_000)); // 10 000 + 95 000, не 205 000
    }

    @Test
    @DisplayName("Легаси FUND_TRANSFER (PLAN + factAmount) учтён как факт")
    void legacyFundTransferCountedAsFact() {
        PocketInput in = base()
                .events(legacyTransfer(LocalDate.of(2026, 3, 1), 3_000))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance()).isEqualByComparingTo(dec(7_000));
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
        assertThat(r.pocket()).isEqualByComparingTo(dec(8_600)); // min в конце: 10 000 − 1 400
        PocketResultDto.BreakdownLine f = line(r, BreakdownType.UNPLANNED_FORECAST);
        assertThat(f.amount()).isEqualByComparingTo(dec(-1_400));
        assertThat(f.details()).containsExactly("Продукты");
    }

    @Test
    @DisplayName("asOfDate = последний день месяца → окно прогноза пусто, строка опущена")
    void unplannedForecast_emptyWindow() {
        LocalDate eom = LocalDate.of(2026, 3, 31);
        PocketInput in = base().forecast(5_000, "Продукты").build();
        in = new PocketInput(eom, in.checkpointAmount(), eom, in.events(), in.wishlistEvents(),
                in.overdueEvents(), in.scope(), LocalDate.of(2026, 4, 5), false,
                in.bufferAmount(), in.unplannedForecast(), in.forecastContributors());
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
        assertThat(mar5.expense()).isEqualByComparingTo(dec(3_100)); // 3 000 + 100 прогноза

        // Инвариант: balance(i) = balance(i-1) + income(i) − expense(i) — на каждой точке после нулевой
        for (int i = 1; i < r.trajectory().size(); i++) {
            PocketResultDto.TrajectoryPoint prev = r.trajectory().get(i - 1);
            PocketResultDto.TrajectoryPoint cur = r.trajectory().get(i);
            assertThat(prev.balance().add(cur.income()).subtract(cur.expense()))
                    .isEqualByComparingTo(cur.balance());
        }
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
}
