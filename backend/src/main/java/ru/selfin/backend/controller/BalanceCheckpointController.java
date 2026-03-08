package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.BalanceCheckpointDto;
import ru.selfin.backend.service.BalanceCheckpointService;

import java.util.List;
import java.util.UUID;

@Tag(name = "Баланс счёта", description = "Управление чекпоинтами баланса — точками отсчёта для расчётов")
@Validated
@RestController
@RequestMapping("/api/v1/balance-checkpoints")
@RequiredArgsConstructor
public class BalanceCheckpointController {

    private final BalanceCheckpointService service;

    @Operation(summary = "История чекпоинтов баланса", description = "Возвращает все записи, от самой свежей к старой")
    @GetMapping
    public List<BalanceCheckpointDto> getAll() {
        return service.findAll();
    }

    @Operation(summary = "Создать чекпоинт", description = "Фиксирует реальный остаток на счёте на указанную дату")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceCheckpointDto create(@Valid @RequestBody BalanceCheckpointCreateDto dto) {
        return service.create(dto);
    }

    @Operation(summary = "Обновить чекпоинт", description = "Исправляет дату или сумму существующей записи")
    @PutMapping("/{id}")
    public BalanceCheckpointDto update(@PathVariable UUID id,
                                       @Valid @RequestBody BalanceCheckpointCreateDto dto) {
        return service.update(id, dto);
    }

    @Operation(summary = "Удалить чекпоинт")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
