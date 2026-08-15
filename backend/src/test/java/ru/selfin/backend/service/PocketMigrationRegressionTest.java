package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.FallbackKind;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ЭТАЛОН поведения {@link PocketEngine} ДО перехода на многосчётность (ANO-9, Task 1.3).
 *
 * <p><b>Это не TDD-тест.</b> Он обязан быть зелёным на сегодняшнем коде без единой правки —
 * он фиксирует, а не разрабатывает. Условие приёмки чанка 2 («движок переезжает с одного
 * чекпоинта на сумму остатков по счетам»): этот файл остаётся зелёным без единой правки.
 * Если после переезда на счета число здесь не сходится — поехала формула, чинить нужно
 * ПРОДАКШН-код (PocketEngine/сборку входа), а не подгонять ожидания теста под новый результат.
 *
 * <p>Все ожидаемые числа посчитаны вручную (см. комментарии по ходу) и НЕ выведены из прогона
 * кода — арифметику можно проверить в столбик, не запуская тест.
 */
class PocketMigrationRegressionTest {

    // asOfDate зафиксирован явной константой (Clock ещё не внедрён, ANO-39) — движок чистый
    // и берёт дату только из входа, поэтому ни одного LocalDate.now() в этом файле.
    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 10);         // март — 31 день,
    private static final LocalDate CHECKPOINT_DATE = LocalDate.of(2026, 3, 8); // все даты сценария
    private static final LocalDate HORIZON_END = LocalDate.of(2026, 3, 28);    // укладываются в март

    // ── хелперы построения входа (тот же подход, что в PocketEngineTest) ──────

    private static BigDecimal dec(long v) { return BigDecimal.valueOf(v); }

    private static EventSnapshot planExpense(LocalDate date, long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.MEDIUM, dec(amount), null, null, false, description);
    }

    private static EventSnapshot planIncome(LocalDate date, long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.INCOME, EventKind.PLAN,
                EventStatus.PLANNED, Priority.HIGH, dec(amount), null, null, false, description);
    }

    private static EventSnapshot fact(EventType type, LocalDate date, long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, description);
    }

    private static EventSnapshot contribution(LocalDate date, long amount, String fundName) {
        return new EventSnapshot(null, date, EventType.EXPENSE, EventKind.PLAN, EventStatus.PLANNED,
                Priority.MEDIUM, dec(amount), null, null, false, fundName,
                SyntheticKind.SAVINGS_CONTRIBUTION);
    }

    private static EventSnapshot overdueExpense(LocalDate date, long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.HIGH, dec(amount), null, null, false, description);
    }

    private static EventSnapshot wishlistOpen(long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), null, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.LOW, dec(amount), null, WishlistStatus.OPEN, false, description);
    }

    /**
     * Сценарий: один счёт (обычный {@link PocketInput}, как сегодня), содержательный —
     * задействует все 13 полей входа и все 10 типов строк breakdown.
     *
     * <pre>
     * Чекпоинт   08.03  50 000
     * Факт EXP   09.03  -3 000  Продукты   (после чекпоинта, до asOf)
     * Факт INC   10.03  +8 000  Возврат    (= asOf, после чекпоинта — не задвоен с чекпоинтом)
     * asOf       10.03
     * Просрочка  20.02  -4 000  Связь (просрочен)     — резерв дня 0
     * План EXP   10.03  -1 000  Такси                 — расход СЕГОДНЯ (считается)
     * План INC   12.03 +15 000  Аванс
     * План EXP   14.03 -10 000  Аренда
     * План EXP   16.03  -6 000  ЖКХ
     * Взнос      18.03  -4 000  Египет (копилка)
     * План EXP   22.03  -3 000  Кафе
     * План INC   25.03 +45 000  Зарплата
     * Прогноз незапланир. 1 800 на окно 11.03..28.03 (18 дней = min(конец марта 31.03, горизонт 28.03))
     *                     -> 1 800 / 18 = 100/день ровно (без остатка копеек)
     * Буфер       5 000
     * Хотелка OPEN 12 000  Ноутбук (кандидат, НЕ вычитается)
     * Горизонт    28.03 (> asOf+7=17.03, хвост не включается — весь расчёт внутри горизонта)
     * </pre>
     */
    private static PocketInput referenceInput() {
        List<EventSnapshot> events = List.of(
                fact(EventType.EXPENSE, LocalDate.of(2026, 3, 9), 3_000, "Продукты"),
                fact(EventType.INCOME, AS_OF, 8_000, "Возврат"),
                planExpense(AS_OF, 1_000, "Такси"),
                planIncome(LocalDate.of(2026, 3, 12), 15_000, "Аванс"),
                planExpense(LocalDate.of(2026, 3, 14), 10_000, "Аренда"),
                planExpense(LocalDate.of(2026, 3, 16), 6_000, "ЖКХ"),
                contribution(LocalDate.of(2026, 3, 18), 4_000, "Египет"),
                planExpense(LocalDate.of(2026, 3, 22), 3_000, "Кафе"),
                planIncome(LocalDate.of(2026, 3, 25), 45_000, "Зарплата")
        );
        List<EventSnapshot> wishlistEvents = List.of(wishlistOpen(12_000, "Ноутбук"));
        List<EventSnapshot> overdueEvents = List.of(overdueExpense(LocalDate.of(2026, 2, 20), 4_000, "Связь (просрочен)"));

        return new PocketInput(
                AS_OF, dec(50_000), CHECKPOINT_DATE,
                events, wishlistEvents, overdueEvents,
                new PocketScope(PocketScope.Type.NEXT_INCOME, null, null),
                HORIZON_END, FallbackKind.NONE,
                dec(5_000),
                dec(1_800), List.of("Продукты"),
                java.util.Map.of());
    }

    private static PocketResultDto.BreakdownLine line(PocketResultDto r, BreakdownType t) {
        return r.breakdown().stream().filter(l -> l.type() == t).findFirst()
                .orElseThrow(() -> new AssertionError("Строка breakdown отсутствует: " + t));
    }

    // ── эталон: точные числа, посчитанные вручную ──────────────────────────

    @Test
    @DisplayName("ЭТАЛОН: currentBalance, pocket, минимум и breakdown одного счёта — не трогать без причины")
    void singleAccountBaseline_fixedNumbersMatchManualCalculation() {
        PocketResultDto r = PocketEngine.calculate(referenceInput());

        // ── 1. currentBalance = checkpoint + факты СТРОГО ПОСЛЕ чекпоинта, ≤ asOf ──
        // 50 000 (чекпоинт 08.03) − 3 000 (факт 09.03) + 8 000 (факт 10.03=asOf) = 55 000
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(55_000));

        // ── 2. День 0 (asOf=10.03): − просрочка − расход сегодня ──
        // 55 000 − 4 000 (просрочка) − 1 000 (Такси) = 50 000
        assertThat(r.trajectory().get(0).balance()).isEqualByComparingTo(dec(50_000));

        // ── 3. Траектория 11.03..28.03 (день за днём, форекаст 100/день на всём окне) ──
        // 11.03: 50 000 − 100                              = 49 900
        // 12.03: 49 900 + 15 000(Аванс) − 100               = 64 800
        // 13.03: 64 800 − 100                               = 64 700
        // 14.03: 64 700 − 10 000(Аренда) − 100               = 54 600
        // 15.03: 54 600 − 100                               = 54 500
        // 16.03: 54 500 − 6 000(ЖКХ) − 100                  = 48 400
        // 17.03: 48 400 − 100                               = 48 300
        // 18.03: 48 300 − 4 000(взнос Египет) − 100         = 44 200
        // 19.03: 44 200 − 100                               = 44 100
        // 20.03: 44 100 − 100                               = 44 000
        // 21.03: 44 000 − 100                               = 43 900
        // 22.03: 43 900 − 3 000(Кафе) − 100                 = 40 800
        // 23.03: 40 800 − 100                               = 40 700
        // 24.03: 40 700 − 100                               = 40 600   <- минимум (дальше только рост)
        // 25.03: 40 600 + 45 000(Зарплата) − 100            = 85 500
        // ...остаток горизонта монотонно растёт (только −100/день форекаста)
        // Минимум траектории = 40 600 на 24.03 — строго ниже дня 0 (50 000), т.е. провал НЕ в нулевом дне.
        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 24));
        assertThat(r.minPoint().balance()).isEqualByComparingTo(dec(40_600));
        // На 24.03 у дня нет собственного события (только размазанный форекаст) — drivenBy пуст,
        // типовой продакшен-случай (см. PocketEngineTest.minPointDrivenBy, "smeared" ветка).
        assertThat(r.minPoint().drivenBy()).isNull();

        // ── 4. pocket = min − буфер = 40 600 − 5 000 = 35 600 ──
        assertThat(r.pocket()).isEqualByComparingTo(dec(35_600));

        // ── 5. Полный список типов строк breakdown, порядок = порядок рендера (BreakdownType) ──
        assertThat(r.breakdown()).extracting(PocketResultDto.BreakdownLine::type).containsExactly(
                BreakdownType.STARTING_BALANCE,
                BreakdownType.OVERDUE_RESERVE,
                BreakdownType.PLANNED_EXPENSES,
                BreakdownType.SAVINGS_CONTRIBUTIONS,
                BreakdownType.PLANNED_INCOME,
                BreakdownType.UNPLANNED_FORECAST,
                BreakdownType.TRAJECTORY_MIN,
                BreakdownType.BUFFER,
                BreakdownType.POCKET,
                BreakdownType.WISHLIST_INFO);

        // ── 6. Суммы всех строк breakdown (суммы-ДО-минимума, т.е. только события ≤ 24.03) ──
        // STARTING_BALANCE = currentBalance = 55 000
        assertThat(line(r, BreakdownType.STARTING_BALANCE).amount()).isEqualByComparingTo(dec(55_000));
        // OVERDUE_RESERVE = −overdue = −4 000 (единственная просрочка)
        assertThat(line(r, BreakdownType.OVERDUE_RESERVE).amount()).isEqualByComparingTo(dec(-4_000));
        // PLANNED_EXPENSES = −(Такси 1 000 + Аренда 10 000 + ЖКХ 6 000 + Кафе 3 000) = −20 000
        // (все четыре расхода ≤ 24.03; взнос в копилку сюда НЕ входит — своя строка)
        assertThat(line(r, BreakdownType.PLANNED_EXPENSES).amount()).isEqualByComparingTo(dec(-20_000));
        // SAVINGS_CONTRIBUTIONS = −4 000 (взнос "Египет" 18.03, ≤ 24.03)
        assertThat(line(r, BreakdownType.SAVINGS_CONTRIBUTIONS).amount()).isEqualByComparingTo(dec(-4_000));
        // PLANNED_INCOME = +15 000 (Аванс 12.03 ≤ 24.03; Зарплата 25.03 ПОСЛЕ минимума — не входит)
        assertThat(line(r, BreakdownType.PLANNED_INCOME).amount()).isEqualByComparingTo(dec(15_000));
        // UNPLANNED_FORECAST = −(100 × 14 дней с 11.03 по 24.03 включительно) = −1 400
        assertThat(line(r, BreakdownType.UNPLANNED_FORECAST).amount()).isEqualByComparingTo(dec(-1_400));
        // TRAJECTORY_MIN = 40 600
        assertThat(line(r, BreakdownType.TRAJECTORY_MIN).amount()).isEqualByComparingTo(dec(40_600));
        // BUFFER = −5 000
        assertThat(line(r, BreakdownType.BUFFER).amount()).isEqualByComparingTo(dec(-5_000));
        // POCKET = 35 600
        assertThat(line(r, BreakdownType.POCKET).amount()).isEqualByComparingTo(dec(35_600));
        // WISHLIST_INFO = 12 000 (хотелка "Ноутбук", информационная строка, не вычтена)
        assertThat(line(r, BreakdownType.WISHLIST_INFO).amount()).isEqualByComparingTo(dec(12_000));

        // Хотелка-кандидат согласована со строкой WISHLIST_INFO
        assertThat(r.wishlistCandidates()).hasSize(1);
        assertThat(r.wishlistCandidates().get(0).plannedAmount()).isEqualByComparingTo(dec(12_000));
        assertThat(r.wishlistCandidates().get(0).fixed()).isFalse();
    }

    // ── инвариант: вычислен ПО СТРОКАМ breakdown, не хардкодом ─────────────

    /**
     * Возвращает сумму строки breakdown или ZERO, если строка отсутствует (строки с нулевой
     * суммой в {@link PocketEngine} опускаются — см. buildBreakdown). Это делает инвариант
     * устойчивым к легальным изменениям сценария (например, если минимум сместится и какая-то
     * сумма-до-минимума станет нулевой), но не устойчивым к порче самой формулы движка.
     */
    private static BigDecimal amountOrZero(PocketResultDto r, BreakdownType t) {
        return r.breakdown().stream().filter(l -> l.type() == t)
                .map(PocketResultDto.BreakdownLine::amount).filter(Objects::nonNull)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("ИНВАРИАНТ: STARTING−OVERDUE−EXPENSES−CONTRIB+INCOME−FORECAST=MIN, MIN−BUFFER=POCKET")
    void breakdownInvariant_holdsAlgebraically() {
        PocketResultDto r = PocketEngine.calculate(referenceInput());

        // OVERDUE_RESERVE/PLANNED_EXPENSES/SAVINGS_CONTRIBUTIONS/UNPLANNED_FORECAST/BUFFER уже
        // хранятся в breakdown с отрицательным знаком (вычитание закодировано в самой строке,
        // см. PocketEngine.buildBreakdown: overdue.negate(), expensesAtMin.negate() и т.д.),
        // а PLANNED_INCOME — с положительным. Поэтому «STARTING − OVERDUE − EXPENSES − CONTRIB
        // + INCOME − FORECAST» алгебраически равно ПРОСТОЙ СУММЕ этих строк как они лежат
        // в breakdown — ту же идею использует существующий PocketEngineTest
        // (breakdownArithmetic_sumsToMin, savingsContributions_breakdownInvariantHolds).
        BigDecimal starting = amountOrZero(r, BreakdownType.STARTING_BALANCE);
        BigDecimal overdue = amountOrZero(r, BreakdownType.OVERDUE_RESERVE);
        BigDecimal expenses = amountOrZero(r, BreakdownType.PLANNED_EXPENSES);
        BigDecimal contrib = amountOrZero(r, BreakdownType.SAVINGS_CONTRIBUTIONS);
        BigDecimal income = amountOrZero(r, BreakdownType.PLANNED_INCOME);
        BigDecimal forecast = amountOrZero(r, BreakdownType.UNPLANNED_FORECAST);
        BigDecimal buffer = amountOrZero(r, BreakdownType.BUFFER);

        BigDecimal computedMin = starting.add(overdue).add(expenses).add(contrib).add(income).add(forecast);
        assertThat(computedMin)
                .as("STARTING − OVERDUE − EXPENSES − CONTRIB + INCOME − FORECAST = MIN")
                .isEqualByComparingTo(r.minPoint().balance());

        BigDecimal computedPocket = r.minPoint().balance().add(buffer);
        assertThat(computedPocket)
                .as("MIN − BUFFER = POCKET")
                .isEqualByComparingTo(r.pocket());

        // На данном сценарии инвариант нетривиален: все шесть слагаемых ненулевые.
        assertThat(overdue.signum()).isNotZero();
        assertThat(expenses.signum()).isNotZero();
        assertThat(contrib.signum()).isNotZero();
        assertThat(income.signum()).isNotZero();
        assertThat(forecast.signum()).isNotZero();
        assertThat(buffer.signum()).isNotZero();
    }
}
