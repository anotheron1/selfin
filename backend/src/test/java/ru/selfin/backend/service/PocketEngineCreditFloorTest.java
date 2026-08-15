package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Второе и третье числа кармашка (спека 2026-08-12-accounts-skeleton-design.md §4.2, §4.3;
 * план Task 4.1).
 *
 * <p>Порядок на экране: свободно (крупно) → свободно после возврата карт к планке (обычным)
 * → свободно, если распечатать вклад (мелким). Второе и третье — не равноправные ответы, а
 * оговорки к первому: карты возвращают к планке, потому что так меряется обязательство
 * (принцип №4 подхода), а вклад распечатывают в трудный момент, а не планируют им жить.
 *
 * <p><b>Оба числа информационные.</b> Ни одно не меняет {@code pocket} и не входит в
 * инвариант breakdown {@code STARTING − OVERDUE − EXPENSES − CONTRIB + INCOME − FORECAST = MIN},
 * {@code MIN − BUFFER = POCKET}. Строка {@code CREDIT_RESTORE} стоит ПОСЛЕ {@code POCKET},
 * рядом с {@code WISHLIST_INFO} — порядок отражает «это сноска, а не часть вычитания».
 */
class PocketEngineCreditFloorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    private static BigDecimal dec(long v) { return BigDecimal.valueOf(v); }

    /** База: чекпоинт 10 000, трат нет → кармашек равен 10 000. Числа считаются от него. */
    private static PocketEngineTest.PocketInputBuilder base() {
        return PocketEngineTest.PocketInputBuilder.create();
    }

    private static PocketResultDto.BreakdownLine lineOrNull(PocketResultDto r, BreakdownType t) {
        return r.breakdown().stream().filter(l -> l.type() == t).findFirst().orElse(null);
    }

    // ── второе число: свободно, если вернуть карты к планке (§4.2) ───────────

    @Test
    @DisplayName("§4.2: планка задана и доступное ниже — второе число = кармашек − резерв, "
            + "в breakdown появляется CREDIT_RESTORE")
    void creditReserve_subtractsFromSecondNumber() {
        PocketInput in = base().creditRestoreReserve(4_000).build();

        PocketResultDto r = PocketEngine.calculate(in);

        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocketAfterCreditRestore()).isEqualByComparingTo(dec(6_000));
        assertThat(lineOrNull(r, BreakdownType.CREDIT_RESTORE)).isNotNull();
        assertThat(lineOrNull(r, BreakdownType.CREDIT_RESTORE).amount())
                .isEqualByComparingTo(dec(-4_000));
    }

    @Test
    @DisplayName("§4.2: планок нет — второго числа нет и строки нет (приложение планку не выдумывает)")
    void noCreditReserve_secondNumberIsNull() {
        PocketResultDto r = PocketEngine.calculate(base().build());

        assertThat(r.pocketAfterCreditRestore()).isNull();
        assertThat(lineOrNull(r, BreakdownType.CREDIT_RESTORE)).isNull();
    }

    @Test
    @DisplayName("§4.2: доступное уже выше планки — резерв ноль, второго числа нет: "
            + "возвращать нечего, и «свободно после возврата» повторяло бы первое число")
    void zeroCreditReserve_secondNumberIsNull() {
        PocketResultDto r = PocketEngine.calculate(base().creditRestoreReserve(0).build());

        assertThat(r.pocketAfterCreditRestore()).isNull();
        assertThat(lineOrNull(r, BreakdownType.CREDIT_RESTORE)).isNull();
    }

    @Test
    @DisplayName("Второе число может уйти в минус: резерв больше кармашка — значит вернуть карты "
            + "к планке прямо сейчас не из чего, и прятать это нельзя")
    void creditReserveAbovePocket_secondNumberGoesNegative() {
        PocketResultDto r = PocketEngine.calculate(base().creditRestoreReserve(25_000).build());

        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocketAfterCreditRestore()).isEqualByComparingTo(dec(-15_000));
    }

    // ── третье число: свободно, если распечатать вклад (§4.3) ────────────────

    @Test
    @DisplayName("§4.3: вклад есть — третье число = кармашек + полу-ликвид")
    void semiLiquid_addsToThirdNumber() {
        PocketResultDto r = PocketEngine.calculate(base().semiLiquidBalance(300_000).build());

        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocketWithDeposits()).isEqualByComparingTo(dec(310_000));
    }

    @Test
    @DisplayName("§4.3: вкладов нет — третьего числа нет")
    void noSemiLiquid_thirdNumberIsNull() {
        assertThat(PocketEngine.calculate(base().build()).pocketWithDeposits()).isNull();
        assertThat(PocketEngine.calculate(base().semiLiquidBalance(0).build())
                .pocketWithDeposits()).isNull();
    }

    @Test
    @DisplayName("§4.3: вклад НЕ входит в основное число и не двигает траекторию — он сноска")
    void semiLiquid_doesNotTouchPocketOrTrajectory() {
        PocketResultDto without = PocketEngine.calculate(base().build());
        PocketResultDto with = PocketEngine.calculate(base().semiLiquidBalance(300_000).build());

        assertThat(with.pocket()).isEqualByComparingTo(without.pocket());
        assertThat(with.currentBalance()).isEqualByComparingTo(without.currentBalance());
        assertThat(with.minPoint().balance()).isEqualByComparingTo(without.minPoint().balance());
        assertThat(with.trajectory()).hasSameSizeAs(without.trajectory());
    }

    // ── оба числа сразу, на непустой траектории ──────────────────────────────

    @Test
    @DisplayName("Инвариант breakdown не изменился: CREDIT_RESTORE информационная, как WISHLIST_INFO, "
            + "и в сумму строк не входит")
    void breakdownInvariantHolds_withCreditRestoreLine() {
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(PocketEngineTest.plan(EventType.EXPENSE, LocalDate.of(2026, 3, 12),
                                9_000, Priority.HIGH),
                        PocketEngineTest.plan(EventType.INCOME, LocalDate.of(2026, 3, 15),
                                100_000, Priority.HIGH))
                .buffer(500)
                .creditRestoreReserve(4_000)
                .semiLiquidBalance(300_000)
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        BigDecimal starting = lineOrNull(r, BreakdownType.STARTING_BALANCE).amount();
        BigDecimal expenses = lineOrNull(r, BreakdownType.PLANNED_EXPENSES).amount();
        BigDecimal min = lineOrNull(r, BreakdownType.TRAJECTORY_MIN).amount();
        BigDecimal buffer = lineOrNull(r, BreakdownType.BUFFER).amount();
        BigDecimal pocket = lineOrNull(r, BreakdownType.POCKET).amount();

        assertThat(starting.add(expenses)).isEqualByComparingTo(min);
        assertThat(min.add(buffer)).isEqualByComparingTo(pocket);
        // Резерв в вычитание не вошёл: кармашек тот же, что без него
        assertThat(pocket).isEqualByComparingTo(dec(500));
        assertThat(r.pocketAfterCreditRestore()).isEqualByComparingTo(dec(-3_500));
        assertThat(r.pocketWithDeposits()).isEqualByComparingTo(dec(300_500));
    }

    @Test
    @DisplayName("CREDIT_RESTORE стоит ПОСЛЕ POCKET — порядок строк говорит «это сноска»")
    void creditRestoreLine_comesAfterPocketLine() {
        PocketResultDto r = PocketEngine.calculate(base().creditRestoreReserve(4_000).build());

        List<BreakdownType> types = r.breakdown().stream()
                .map(PocketResultDto.BreakdownLine::type).toList();
        assertThat(types.indexOf(BreakdownType.CREDIT_RESTORE))
                .isGreaterThan(types.indexOf(BreakdownType.POCKET));
    }
}
