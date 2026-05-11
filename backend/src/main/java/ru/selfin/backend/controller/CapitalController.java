package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.capital.*;
import ru.selfin.backend.model.enums.CapitalItemKind;
import ru.selfin.backend.service.CapitalService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/capital")
@RequiredArgsConstructor
public class CapitalController {

    private final CapitalService service;

    // --- items ---

    @GetMapping("/items")
    public List<CapitalItemDto> list(
            @RequestParam(required = false) CapitalItemKind kind,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(kind, includeArchived);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CapitalItemDto create(@Valid @RequestBody CapitalItemCreateDto dto) {
        return service.create(dto);
    }

    @GetMapping("/items/{id}")
    public CapitalItemDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/items/{id}")
    public CapitalItemDto update(@PathVariable UUID id, @Valid @RequestBody CapitalItemUpdateDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // --- revaluations ---

    @GetMapping("/items/{itemId}/revaluations")
    public List<CapitalRevaluationDto> history(@PathVariable UUID itemId) {
        return service.getHistory(itemId);
    }

    @PostMapping("/items/{itemId}/revaluations")
    @ResponseStatus(HttpStatus.CREATED)
    public CapitalRevaluationDto addRevaluation(@PathVariable UUID itemId,
                                                @Valid @RequestBody CapitalRevaluationCreateDto dto) {
        return service.addRevaluation(itemId, dto);
    }

    @PutMapping("/revaluations/{id}")
    public CapitalRevaluationDto updateRevaluation(@PathVariable UUID id,
                                                   @Valid @RequestBody CapitalRevaluationUpdateDto dto) {
        return service.updateRevaluation(id, dto);
    }

    @DeleteMapping("/revaluations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRevaluation(@PathVariable UUID id) {
        service.deleteRevaluation(id);
    }

    // --- aggregates ---

    @GetMapping("/summary")
    public CapitalSummaryDto summary() {
        return service.summary();
    }

    @GetMapping("/trajectory")
    public CapitalTrajectoryDto trajectory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.trajectory(from, to);
    }
}
