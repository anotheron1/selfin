package ru.selfin.backend.config;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.selfin.backend.dto.ErrorResponse;
import ru.selfin.backend.exception.ResourceNotFoundException;

import java.util.List;

/**
 * Централизованная обработка исключений для всех REST-контроллеров.
 * Преобразует исключения в структурированный {@link ErrorResponse} с корректным HTTP-статусом
 * вместо 500 Internal Server Error с трейсом.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Обрабатывает запросы к несуществующим ресурсам (категория, событие, фонд).
     * Возвращает HTTP 404.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, ex.getMessage()));
    }

    /**
     * Обрабатывает ошибки валидации DTO (аннотации {@code @Valid}, {@code @NotNull},
     * {@code @PositiveOrZero} и др. на {@code @RequestBody}).
     * Возвращает HTTP 400 с перечнем нарушенных полей.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        log.warn("Validation failed: {}", details);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "Validation failed", details));
    }

    /**
     * Обрабатывает ошибки валидации параметров метода контроллера (аннотации
     * {@code @Validated} + {@code @Positive} на path/query-параметрах и inline-рекордах).
     * Возвращает HTTP 400.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .toList();
        log.warn("Constraint violation: {}", details);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "Validation failed", details));
    }

    /**
     * Обрабатывает нечитаемое тело запроса (неверный JSON, несовместимые типы).
     * Возвращает HTTP 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "Malformed request body"));
    }

    /**
     * Обрабатывает отсутствие обязательного заголовка (например, {@code Idempotency-Key}).
     * Возвращает HTTP 400 с указанием имени заголовка.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("Missing required header: {}", ex.getHeaderName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "Required header missing: " + ex.getHeaderName()));
    }

    /**
     * Fallback-обработчик для всех непредвиденных исключений.
     * Логирует полный стек, возвращает HTTP 500 без деталей реализации клиенту.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "Internal server error"));
    }
}
