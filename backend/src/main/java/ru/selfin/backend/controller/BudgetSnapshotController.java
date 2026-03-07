package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.BudgetSnapshotDto;
import ru.selfin.backend.service.BudgetSnapshotService;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Снимки бюджета", description = "Фиксация состояния плана на начало месяца для сравнения план/факт без «ползущего бюджета»")
@RestController
@RequestMapping("/api/v1/snapshots")
@RequiredArgsConstructor
public class BudgetSnapshotController {

    private final BudgetSnapshotService snapshotService;

    @Operation(summary = "Создать снимок бюджета", description = "Фиксирует все плановые события указанного месяца. " +
            "Идемпотентен: повторный вызов для одного месяца вернёт существующий снимок.")
    @PostMapping
    public BudgetSnapshotDto create(
            @Parameter(description = "Дата в месяце для снимка (по умолчанию — сегодня)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return snapshotService.createSnapshot(date != null ? date : LocalDate.now());
    }

    @Operation(summary = "Получить список снимков", description = "Возвращает снимки за последние 12 месяцев, отсортированные по дате создания (новые первые)")
    @GetMapping
    public List<BudgetSnapshotDto> getAll() {
        return snapshotService.getSnapshots();
    }
}
