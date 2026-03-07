package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.service.TargetFundService;

import java.math.BigDecimal;
import java.util.UUID;

@Tag(name = "Целевые фонды", description = "Управление фондами накоплений и кармашком")
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

    @Operation(summary = "Перевести средства в фонд", description = "Добавляет указанную сумму на баланс фонда (пополнение из кармашка). "
            +
            "Требует Idempotency-Key для защиты от двойного зачисления.")
    @PostMapping("/{id}/transfer")
    public TargetFundDto transfer(
            @Parameter(description = "ID фонда") @PathVariable UUID id,
            @Parameter(description = "UUID для идемпотентности", required = true) @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody TransferRequest request) {
        return fundService.transferToPocket(id, idempotencyKey, request.amount());
    }

    record TransferRequest(BigDecimal amount) {
    }
}
