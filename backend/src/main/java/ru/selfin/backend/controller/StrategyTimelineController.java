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
        // Гарантируем безопасный максимум
        int safeHorizon = Math.min(Math.max(horizonMonths, 1), 60);
        return service.getTimeline(safeHorizon, withBreakdown);
    }
}
