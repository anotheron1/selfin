package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.service.TargetFundService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * REST-контроллер целевых фондов (копилок) и кармашка.
 * Операция пополнения фонда идемпотентна через заголовок {@code Idempotency-Key}.
 *
 * @see ru.selfin.backend.service.TargetFundService
 */
@Tag(name = "Целевые фонды", description = "Управление фондами накоплений и кармашком")
@Validated
@RestController
@RequestMapping("/api/v1/funds")
@RequiredArgsConstructor
public class TargetFundController {

    private final TargetFundService fundService;

    @Operation(summary = "Обзор всех фондов", description = "Возвращает баланс кармашка и список всех активных целевых фондов с прогрессом")
    @GetMapping
    public FundsOverviewDto getOverview() {
        return fundService.getOverview();
    }

    @Operation(summary = "Создать новый целевой фонд")
    @PostMapping
    public TargetFundDto create(@Valid @RequestBody TargetFundCreateDto dto) {
        return fundService.create(dto);
    }

    @Operation(summary = "Обновить целевой фонд", description = "Изменяет название, целевую сумму, срок достижения и приоритет фонда")
    @PutMapping("/{id}")
    public TargetFundDto update(
            @Parameter(description = "ID фонда") @PathVariable UUID id,
            @Valid @RequestBody TargetFundCreateDto dto) {
        return fundService.update(id, dto);
    }

    @Operation(summary = "Удалить целевой фонд", description = "Soft delete: фонд скрывается из UI, транзакции сохраняются")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "ID фонда") @PathVariable UUID id) {
        fundService.delete(id);
    }

    @Operation(summary = "Перевести средства в фонд", description = "Добавляет указанную сумму на баланс фонда (пополнение из кармашка). "
            +
            "Требует Idempotency-Key для защиты от двойного зачисления.")
    @PostMapping("/{id}/transfer")
    public TargetFundDto transfer(
            @Parameter(description = "ID фонда") @PathVariable UUID id,
            @Parameter(description = "UUID для идемпотентности", required = true) @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return fundService.transferToPocket(id, idempotencyKey, request.amount());
    }

    /**
     * Тело запроса на пополнение фонда.
     *
     * @param amount сумма перевода; должна быть строго положительной
     */
    record TransferRequest(@Positive BigDecimal amount) {
    }
}
