package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.FallbackKind;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Движок кармашка видит несколько счетов (ANO-9 Task 2.2, спека §4.1). Три новых поля входа
 * {@link PocketInput#otherAccountsBalance}/{@code creditRestoreReserve}/{@code semiLiquidBalance}
 * появились этой задачей — второе и третье числа кармашка (§4.2, §4.3) читает движок только
 * в Task 4.1, здесь проверяется исключительно шаг 1 расчёта {@code currentBalance}.
 */
class PocketEngineAccountsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final LocalDate CHECKPOINT_DATE = LocalDate.of(2026, 2, 25);
    private static final PocketScope MONTHS_1 = new PocketScope(PocketScope.Type.MONTHS, 1, null);
    private static final LocalDate HORIZON_END = TODAY.plusMonths(1);

    private static BigDecimal dec(long v) { return BigDecimal.valueOf(v); }

    private static EventSnapshot fact(EventType type, LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, "факт");
    }

    /** Полный вход (16-арный, с явными счетами) с настраиваемым якорем дефолтного счёта и прочими. */
    private static PocketInput input(long checkpointAmount, List<EventSnapshot> events,
                                     BigDecimal otherAccountsBalance) {
        return new PocketInput(TODAY, dec(checkpointAmount), CHECKPOINT_DATE,
                events, List.of(), List.of(),
                MONTHS_1, HORIZON_END, FallbackKind.NONE,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), java.util.Map.of(),
                otherAccountsBalance, null, null);
    }

    @Test
    @DisplayName("Свободные деньги — сумма: дефолтный якорь 50 000 + прочие 20 000 = 70 000")
    void freeMoneyIsSumOfTrackedLiquidAccounts() {
        PocketInput in = input(50_000, List.of(), dec(20_000));

        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.currentBalance()).isEqualByComparingTo(dec(70_000));
    }

    @Test
    @DisplayName("Безадресные факты бьют только по дефолтному счёту: факт −5 000 даёт 65 000, сумма прочих не меняется")
    void addresslessFactsHitOnlyDefaultAccount() {
        // Факт после якоря (25.02) и ≤ asOf (01.03) — считается движком в currentBalance
        // (правило шага 1), но применяется только к дефолтному счёту: otherAccountsBalance
        // уже посчитан сборщиком отдельно и факты внутрь него не примешиваются.
        PocketInput in = input(50_000,
                List.of(fact(EventType.EXPENSE, LocalDate.of(2026, 2, 28), 5_000)),
                dec(20_000));

        PocketResultDto r = PocketEngine.calculate(in);

        // 50 000 (якорь дефолтного) − 5 000 (факт дефолтного) + 20 000 (прочие, не тронуты) = 65 000
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(65_000));
    }

    @Test
    @DisplayName("null в otherAccountsBalance/creditRestoreReserve/semiLiquidBalance эквивалентен нулю")
    void nullNewFieldsBehaveAsZero() {
        // Явный null в трёх новых полях (нет ни прочих счетов, ни резерва, ни вклада) —
        // *OrZero() геттеры обязаны трактовать его как ноль, не кидать NPE (тот же приём,
        // что уже применён для futureForecast, ANO-36).
        PocketInput withNulls = new PocketInput(TODAY, dec(50_000), CHECKPOINT_DATE,
                List.of(), List.of(), List.of(),
                MONTHS_1, HORIZON_END, FallbackKind.NONE,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), java.util.Map.of(),
                null, null, null);

        PocketResultDto r = PocketEngine.calculate(withNulls);

        assertThat(r.currentBalance()).isEqualByComparingTo(dec(50_000));
    }
}
