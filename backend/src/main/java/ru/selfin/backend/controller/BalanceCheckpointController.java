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
import ru.selfin.backend.dto.BalanceSheetDto;
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

    @Operation(summary = "Лист «Обновить остатки»", description = "Карты с остатком: «по записям» и дата обновления")
    @GetMapping("/sheet")
    public BalanceSheetDto sheet() {
        return service.sheet();
    }

    @Operation(summary = "Пакет обновлений", description = "Все числа листа одной транзакцией, пустые пропущены")
    @WritesData
    @PostMapping("/batch")
    public BalanceSheetDto.Result batch(@Valid @RequestBody BalanceSheetDto.Batch dto) {
        return service.batch(dto);
    }

    @Operation(summary = "Предпросмотр пакета", description = "То же, что пакет, без записи")
    @WritesData(false)
    @PostMapping("/preview")
    public BalanceSheetDto.Result preview(@Valid @RequestBody BalanceSheetDto.Batch dto) {
        return service.preview(dto);
    }

    @Operation(summary = "Создать чекпоинт", description = "Фиксирует реальный остаток на счёте на указанную дату")
    @WritesData
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceCheckpointDto create(@Valid @RequestBody BalanceCheckpointCreateDto dto) {
        return service.create(dto);
    }

    @Operation(summary = "Обновить чекпоинт", description = "Исправляет дату или сумму существующей записи")
    @WritesData
    @PutMapping("/{id}")
    public BalanceCheckpointDto update(@PathVariable UUID id,
                                       @Valid @RequestBody BalanceCheckpointCreateDto dto) {
        return service.update(id, dto);
    }

    @Operation(summary = "Удалить чекпоинт")
    @WritesData
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
