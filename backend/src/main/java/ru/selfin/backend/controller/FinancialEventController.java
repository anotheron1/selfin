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
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.FactCreateDto;
import ru.selfin.backend.dto.FactPreviewDto;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.dto.FinancialEventUpdateFactDto;
import ru.selfin.backend.dto.JournalSearchDto;
import ru.selfin.backend.dto.StandaloneFactCreateDto;
import ru.selfin.backend.dto.WishlistCreateDto;
import ru.selfin.backend.model.enums.ScopeEnum;
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

    @Operation(summary = "Получить события за период или по приоритету",
            description = "Если передан только priority — возвращает все события с этим приоритетом. "
                    + "Если переданы startDate и endDate — возвращает события за период. "
                    + "Если не передан ни priority, ни даты — 400.")
    @GetMapping
    public List<FinancialEventDto> getByPeriod(
            @Parameter(description = "Начало периода, формат YYYY-MM-DD")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Конец периода, формат YYYY-MM-DD")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Фильтр по приоритету (HIGH, MEDIUM, LOW)")
            @RequestParam(required = false) Priority priority) {
        if (priority != null && startDate == null && endDate == null) {
            return eventService.findByPriority(priority);
        }
        if (startDate == null || endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate and endDate are required when priority is not specified");
        }
        return eventService.findByPeriod(startDate, endDate);
    }

    @Operation(summary = "Месяцы года со строками журнала",
            description = "Номера месяцев 1–12, где журнал покажет хоть одну строку: точки в сетке месяцев.")
    @GetMapping("/months")
    public List<Integer> monthsWithRows(@RequestParam int year) {
        return eventService.monthsWithRows(year);
    }

    @Operation(summary = "Поиск по журналу за все месяцы",
            description = "Число — по сумме строки, слова — по названию и категории. До 200 строк, свежие первыми; "
                    + "сколько нашлось всего и сколько в найденном записано (доход — плюс, расход — минус).")
    @GetMapping("/search")
    public JournalSearchDto search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Priority priority,
            @RequestParam(defaultValue = "false") boolean factsOnly) {
        return eventService.search(q, from, to, categoryId, priority, factsOnly);
    }

    @Operation(summary = "К какой строке ожидания встанет запись",
            description = "Строка по правилу привязки записи без плана (срез 3 п. 1) — имя, дата, сколько записано; "
                    + "не встанет ни к какой — 204 без тела. Форма «+» по ответу говорит, что будет со «Свободно».")
    @GetMapping("/fact-preview")
    public ResponseEntity<FactPreviewDto> factPreview(
            @RequestParam UUID categoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam EventType type,
            @RequestParam Priority priority) {
        return eventService.factPreview(categoryId, date, type, priority)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Создать событие (идемпотентно)", description = "Клиент обязан передать заголовок Idempotency-Key (UUID). "
            +
            "Повторный запрос с тем же ключом вернёт уже созданное событие без дублирования.")
    @WritesData
    @PostMapping
    public FinancialEventDto create(
            @Parameter(description = "Уникальный UUID для идемпотентности", required = true) @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody FinancialEventCreateDto dto) {
        return eventService.createIdempotent(idempotencyKey, dto);
    }

    @Operation(summary = "Обновить событие", description = "Если передан factAmount — статус автоматически меняется на EXECUTED")
    @WritesData
    @PutMapping("/{id}")
    public FinancialEventDto update(
            @Parameter(description = "ID события") @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "THIS") ScopeEnum scope,
            @Valid @RequestBody FinancialEventCreateDto dto) {
        return eventService.update(id, scope, dto);
    }

    @Operation(summary = "Поправить сумму записи-факта (частичное обновление)",
            description = "Только для записей-фактов (eventKind FACT): обновляет factAmount и description, "
                    + "не затрагивая дату, категорию и прочие поля. Плановая строка — 400: факт к ней "
                    + "пишется отдельной записью, POST /events/{planId}/facts (Р3, ANO-25).")
    @WritesData
    @PatchMapping("/{id}/fact")
    public FinancialEventDto updateFact(
            @Parameter(description = "ID события") @PathVariable UUID id,
            @Valid @RequestBody FinancialEventUpdateFactDto dto) {
        return eventService.updateFact(id, dto);
    }

    @Operation(summary = "Циклически сменить приоритет события (HIGH → MEDIUM → LOW → HIGH)")
    @WritesData
    @PatchMapping("/{id}/priority")
    public FinancialEventDto cyclePriority(
            @Parameter(description = "ID события") @PathVariable UUID id) {
        return eventService.cyclePriority(id);
    }

    /** Ручное создание новой хотелки. */
    @Operation(summary = "Создать хотелку вручную")
    @WritesData
    @PostMapping("/wishlist")
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialEventDto createWishlistItem(@RequestBody @Valid WishlistCreateDto dto) {
        return eventService.createWishlistItem(dto);
    }

    @Operation(summary = "Создать внеплановый факт (идемпотентно)",
            description = "Создаёт standalone FACT-запись без родительского PLAN — для внеплановых трат. "
                    + "Клиент обязан передать заголовок Idempotency-Key (UUID): повтор с тем же ключом "
                    + "вернёт уже записанный факт.")
    @WritesData
    @PostMapping("/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialEventDto createStandaloneFact(
            @Parameter(description = "UUID попытки записи", required = true) @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody StandaloneFactCreateDto dto) {
        return eventService.createStandaloneFact(idempotencyKey, dto);
    }

    @Operation(summary = "Создать связанный факт к плану (идемпотентно)",
            description = "Создаёт FACT-событие, привязанное к указанному PLAN-событию. "
                    + "Дата, сумма и описание факта могут отличаться от плана. "
                    + "Клиент обязан передать заголовок Idempotency-Key (UUID): повтор с тем же ключом "
                    + "вернёт уже записанный факт.")
    @WritesData
    @PostMapping("/{planId}/facts")
    public FinancialEventDto createLinkedFact(
            @Parameter(description = "ID планового события") @PathVariable UUID planId,
            @Parameter(description = "UUID попытки записи", required = true) @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody FactCreateDto dto) {
        return eventService.createLinkedFact(planId, idempotencyKey, dto);
    }

    @Operation(summary = "Удалить событие (soft delete)")
    @WritesData
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "ID события") @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "THIS") ScopeEnum scope) {
        eventService.delete(id, scope);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Записать параметры примерки хотелки: сумму и срок (ANO-162)",
            description = "Только их: описание, исходный текст, категорию и характер не трогает. "
                    + "Срок необязателен — без него не меняется. Не хотелка — 404.")
    @WritesData
    @PatchMapping("/{id}/wishlist-params")
    public void applyWishlistParams(
            @Parameter(description = "ID хотелки") @PathVariable UUID id,
            @Valid @RequestBody ru.selfin.backend.dto.wishlist.EventWishlistParamsDto dto) {
        eventService.applyWishlistParams(id, dto);
    }

    @Operation(summary = "Сменить wishlist-статус события (OPEN/FIXED/DISMISSED)",
            description = "Применимо только к LOW-приоритетным событиям (хотелкам).")
    @WritesData
    @PatchMapping("/{id}/wishlist-status")
    public void setWishlistStatus(
            @Parameter(description = "ID события") @PathVariable UUID id,
            @RequestBody ru.selfin.backend.dto.wishlist.WishlistStatusUpdateDto dto) {
        eventService.setWishlistStatus(id,
                ru.selfin.backend.model.enums.WishlistStatus.valueOf(dto.status()),
                dto.deleteArtifactRequested());
    }
}
