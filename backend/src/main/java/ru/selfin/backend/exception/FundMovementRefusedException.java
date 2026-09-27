package ru.selfin.backend.exception;

import java.math.BigDecimal;
import java.util.List;

/**
 * Факт перевода нельзя записать, исправить или удалить: копилка за ним не может сдвинуться
 * вместе с ним (ANO-169, ANO-201). Отказ безусловный — подтверждать нечего.
 *
 * <p>Факт {@code FUND_TRANSFER} и движение копилки с тем же ключом меняются только вместе —
 * иначе ломается закон сохранения капитала. Когда копилка сдвинуться не может, не меняется
 * и факт; причина уходит на экран машиночитаемым кодом в {@code ErrorResponse.details}, как у
 * {@link ConfirmationRequiredException}, — текст сообщения контрактом не служит.
 *
 * <p>Подсказки после кода: {@code fund:ИМЯ} — всегда; {@code holds:СУММА} — у {@link #HOLDS_LESS}.
 * Зеркалится на фронте в {@code lib/fundMovement.ts}.
 */
public class FundMovementRefusedException extends RuntimeException {

    /** Баланс копилки ушёл бы в минус: из неё уже взяли часть этих денег (арифметика ANO-87). */
    public static final String HOLDS_LESS = "FUND_HOLDS_LESS";

    /** Копилка лежит на счёте: её деньги двигаются на самом счёте (§3.3, ANO-158). */
    public static final String ON_ACCOUNT = "FUND_ON_ACCOUNT";

    /** Копилка удалена: её переводы закрыты вместе с ней и остаются историей. */
    public static final String CLOSED = "FUND_CLOSED";

    private final String code;
    private final List<String> hints;

    private FundMovementRefusedException(String code, String message, List<String> hints) {
        super(message);
        this.code = code;
        this.hints = List.copyOf(hints);
    }

    public static FundMovementRefusedException holdsLess(String fundName, BigDecimal holds) {
        return new FundMovementRefusedException(HOLDS_LESS,
                "Fund '" + fundName + "' holds only " + holds.toPlainString(),
                List.of("fund:" + fundName, "holds:" + holds.toPlainString()));
    }

    public static FundMovementRefusedException onAccount(String fundName) {
        return new FundMovementRefusedException(ON_ACCOUNT,
                "Fund '" + fundName + "' lives on an account: move the money on the account itself",
                List.of("fund:" + fundName));
    }

    public static FundMovementRefusedException closed(String fundName) {
        return new FundMovementRefusedException(CLOSED,
                "Fund '" + fundName + "' is deleted: its transfers stay as history",
                List.of("fund:" + fundName));
    }

    public String code() {
        return code;
    }

    public List<String> hints() {
        return hints;
    }
}
