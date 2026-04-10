package ru.selfin.backend.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceCheckpointCreateDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void futureDate_failsValidation() {
        var dto = new BalanceCheckpointCreateDto(
                LocalDate.now().plusDays(1),
                BigDecimal.valueOf(10000));

        Set<ConstraintViolation<BalanceCheckpointCreateDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
                .contains("date");
    }

    @Test
    void todayDate_passesValidation() {
        var dto = new BalanceCheckpointCreateDto(
                LocalDate.now(),
                BigDecimal.valueOf(10000));

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void pastDate_passesValidation() {
        var dto = new BalanceCheckpointCreateDto(
                LocalDate.now().minusDays(1),
                BigDecimal.valueOf(10000));

        assertThat(validator.validate(dto)).isEmpty();
    }
}
