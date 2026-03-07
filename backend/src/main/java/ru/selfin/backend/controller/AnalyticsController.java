package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.service.DashboardService;

import java.time.LocalDate;

@Tag(name = "Аналитика", description = "Агрегированные данные дашборда: баланс, прогноз, кассовый разрыв")
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final DashboardService dashboardService;

    @Operation(summary = "Данные для главного дашборда", description = "Возвращает текущий баланс, прогноз конца месяца, первый день потенциального "
            +
            "кассового разрыва и прогресс-бары категорий план/факт за текущий месяц.")
    @GetMapping("/dashboard")
    public DashboardDto getDashboard(
            @Parameter(description = "Дата расчёта (по умолчанию — сегодня), формат YYYY-MM-DD") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.getDashboard(date != null ? date : LocalDate.now());
    }
}
