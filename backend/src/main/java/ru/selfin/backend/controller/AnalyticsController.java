package ru.selfin.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.service.AnalyticsService;
import ru.selfin.backend.service.DashboardService;
import ru.selfin.backend.service.PredictionService;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * REST-контроллер аналитики.
 * Предоставляет агрегированные данные дашборда и расширенный аналитический отчёт.
 */
@Tag(name = "Аналитика", description = "Агрегированные данные дашборда: баланс, прогноз, кассовый разрыв")
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final DashboardService dashboardService;
    private final AnalyticsService analyticsService;
    private final PredictionService predictionService;

    @Operation(summary = "Данные для главного дашборда", description = "Возвращает текущий баланс, прогноз конца месяца, первый день потенциального "
            +
            "кассового разрыва и прогресс-бары категорий план/факт за текущий месяц.")
    @GetMapping("/dashboard")
    public DashboardDto getDashboard(
            @Parameter(description = "Дата расчёта (по умолчанию — сегодня), формат YYYY-MM-DD") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.getDashboard(date != null ? date : LocalDate.now());
    }

    @Operation(summary = "Расширенный аналитический отчёт",
            description = "Возвращает четыре секции аналитики за месяц опорной даты: "
                    + "кассовый календарь, план-факт по категориям, burn rate обязательных расходов, дефицит доходов.")
    @GetMapping("/report")
    public AnalyticsReportDto getReport(
            @Parameter(description = "Опорная дата (по умолчанию — сегодня), формат YYYY-MM-DD") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return analyticsService.getReport(date != null ? date : LocalDate.now());
    }

    @Operation(summary = "Многомесячный отчёт план-факт",
            description = "Возвращает доходы, расходы и переводы в копилки с разбивкой по категориям "
                    + "и месяцам за указанный период.")
    @GetMapping("/multi-month")
    public MultiMonthReportDto getMultiMonth(
            @Parameter(description = "Начало периода, формат YYYY-MM-DD") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Конец периода, формат YYYY-MM-DD") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return analyticsService.getMultiMonthReport(startDate, endDate);
    }

    @Operation(summary = "Прогноз потока наличности", description = "Возвращает прогноз потока наличности для месяца опорной даты.")
    @GetMapping("/forecast")
    public MonthlyForecastDto getForecast(
            @Parameter(description = "Опорная дата (по умолчанию — сегодня), формат YYYY-MM-DD") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate today = date != null ? date : LocalDate.now();
        YearMonth month = YearMonth.from(today);
        return predictionService.forecastMonth(month, today);
    }
}
