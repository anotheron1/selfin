package ru.selfin.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Стандартное тело ответа при ошибке.
 * Возвращается всеми обработчиками {@link ru.selfin.backend.config.GlobalExceptionHandler}.
 *
 * @param status  HTTP-статус код
 * @param message человекочитаемое описание ошибки
 * @param details список деталей (используется при ошибках валидации: поле → сообщение)
 * @param timestamp момент возникновения ошибки
 */
public record ErrorResponse(
        int status,
        String message,
        List<String> details,
        LocalDateTime timestamp) {

    /** Фабричный метод для ошибок без деталей. */
    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, List.of(), LocalDateTime.now());
    }

    /** Фабричный метод для ошибок валидации с деталями по полям. */
    public static ErrorResponse of(int status, String message, List<String> details) {
        return new ErrorResponse(status, message, details, LocalDateTime.now());
    }
}
