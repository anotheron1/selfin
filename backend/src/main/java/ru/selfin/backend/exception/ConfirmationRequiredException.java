package ru.selfin.backend.exception;

/**
 * Отказ, который человек может отменить осознанным подтверждением (ANO-87 §4.2, ANO-157).
 *
 * <p>Отличается от безусловного отказа на снятие сверх накопленного: там денег физически
 * нет, и подтверждать нечего. Оба отвечают 409, и различить их по статусу нельзя — поэтому
 * подтверждаемый несёт машиночитаемый код в {@code ErrorResponse.details}.
 *
 * <p>Матчинг по тексту сообщения был бы негласным контрактом: правка формулировки молча
 * сломала бы диалог подтверждения на фронте.
 *
 * <p>Не наследует {@code ResponseStatusException} сознательно: его обработчик кладёт
 * {@code details} пустым, и код было бы некуда положить.
 */
public class ConfirmationRequiredException extends RuntimeException {

    /** Код в {@code ErrorResponse.details}. Зеркалится на фронте в {@code lib/transferConfirm.ts}. */
    public static final String CODE = "CONFIRM_REQUIRED";

    /** Подсказки после кода: что будет, если подтвердить (ANO-88: {@code short:…} / {@code nz:…}). */
    private final java.util.List<String> hints;

    public ConfirmationRequiredException(String message) {
        this(message, java.util.List.of());
    }

    public ConfirmationRequiredException(String message, java.util.List<String> hints) {
        super(message);
        this.hints = java.util.List.copyOf(hints);
    }

    public java.util.List<String> hints() {
        return hints;
    }
}
