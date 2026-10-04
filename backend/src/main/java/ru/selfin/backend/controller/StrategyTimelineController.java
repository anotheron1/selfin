package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.service.StrategyTimelineService;

@RestController
@RequestMapping("/api/v1/strategy")
@RequiredArgsConstructor
public class StrategyTimelineController {

    private final StrategyTimelineService service;

    @GetMapping("/timeline")
    public StrategyTimelineDto getTimeline(
            @RequestParam(defaultValue = "36") int horizonMonths,
            @RequestParam(defaultValue = "true") boolean withBreakdown
    ) {
        // Р1: остаток по месяцам берётся из ядра, а оно дальше 36 месяцев не считает.
        int safeHorizon = Math.min(Math.max(horizonMonths, 1), ru.selfin.backend.dto.pocket.PocketScope.MAX_MONTHS);
        return service.getTimeline(safeHorizon, withBreakdown);
    }
}
