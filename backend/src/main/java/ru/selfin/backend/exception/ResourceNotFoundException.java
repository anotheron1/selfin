package ru.selfin.backend.exception;

/**
 * Выбрасывается, когда запрошенный ресурс не найден в базе данных.
 * Маппируется на HTTP 404 в {@link ru.selfin.backend.config.GlobalExceptionHandler}.
 *
 * @param resourceName название типа ресурса, например {@code "FinancialEvent"}
 * @param id           идентификатор отсутствующего ресурса
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, Object id) {
        super(resourceName + " not found: " + id);
    }
}
