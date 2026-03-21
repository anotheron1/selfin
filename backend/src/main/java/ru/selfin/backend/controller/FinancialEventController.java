package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import ru.selfin.backend.dto.FactCreateDto;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.dto.FinancialEventUpdateFactDto;
import ru.selfin.backend.dto.WishlistCreateDto;
import ru.selfin.backend.service.FinancialEventService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST-контроллер финансовых событий.
 * Все операции создания и обновления факта защищены идемпотентностью
 * или используют облегчённый PATCH для частичных изменений.
 *
 * @see ru.selfin.backend.service.FinancialEventService
 */
@Tag(name = "Финансовые события", description = "Управление событиями плана и факта (доходы/расходы)")
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class FinancialEventController {

    private final FinancialEventService eventService;

    @Operation(summary = "Получить события за период", description = "Возвращает все события между startDate и endDate включительно")
    @GetMapping
    public List<FinancialEventDto> getByPeriod(
            @Parameter(description = "Начало периода, формат YYYY-MM-DD") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Конец периода, формат YYYY-MM-DD") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return eventService.findByPeriod(startDate, endDate);
    }

    @Operation(summary = "Создать событие (идемпотентно)", description = "Клиент обязан передать заголовок Idempotency-Key (UUID). "
            +
            "Повторный запрос с тем же ключом вернёт уже созданное событие без дублирования.")
    @PostMapping
    public FinancialEventDto create(
            @Parameter(description = "Уникальный UUID для идемпотентности", required = true) @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody FinancialEventCreateDto dto) {
        return eventService.createIdempotent(idempotencyKey, dto);
    }

    @Operation(summary = "Обновить событие", description = "Если передан factAmount — статус автоматически меняется на EXECUTED")
    @PutMapping("/{id}")
    public FinancialEventDto update(
            @Parameter(description = "ID события") @PathVariable UUID id,
            @Valid @RequestBody FinancialEventCreateDto dto) {
        return eventService.update(id, dto);
    }

    @Operation(summary = "Ввести фактическую сумму (частичное обновление)",
            description = "Обновляет только factAmount и description, не затрагивая дату, категорию и прочие поля. "
                    + "Статус автоматически меняется: factAmount != null → EXECUTED, null → PLANNED.")
    @PatchMapping("/{id}/fact")
    public FinancialEventDto updateFact(
            @Parameter(description = "ID события") @PathVariable UUID id,
            @Valid @RequestBody FinancialEventUpdateFactDto dto) {
        return eventService.updateFact(id, dto);
    }

    @Operation(summary = "Циклически сменить приоритет события (HIGH → MEDIUM → LOW → HIGH)")
    @PatchMapping("/{id}/priority")
    public FinancialEventDto cyclePriority(
            @Parameter(description = "ID события") @PathVariable UUID id) {
        return eventService.cyclePriority(id);
    }

    @Operation(summary = "Нереализованные хотелки",
            description = "LOW-priority PLANNED события с датой раньше сегодня")
    @GetMapping("/wishlist")
    public List<FinancialEventDto> getWishlist() {
        return eventService.findWishlist();
    }

    /** Ручное создание новой хотелки. */
    @Operation(summary = "Создать хотелку вручную")
    @PostMapping("/wishlist")
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialEventDto createWishlistItem(@RequestBody @Valid WishlistCreateDto dto) {
        return eventService.createWishlistItem(dto);
    }

    @Operation(summary = "Создать связанный факт к плану",
            description = "Создаёт FACT-событие, привязанное к указанному PLAN-событию. "
                    + "Дата, сумма и описание факта могут отличаться от плана.")
    @PostMapping("/{planId}/facts")
    public FinancialEventDto createLinkedFact(
            @Parameter(description = "ID планового события") @PathVariable UUID planId,
            @Valid @RequestBody FactCreateDto dto) {
        return eventService.createLinkedFact(planId, dto);
    }

    @Operation(summary = "Удалить событие (soft delete)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "ID события") @PathVariable UUID id) {
        eventService.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
