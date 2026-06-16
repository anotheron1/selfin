package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.wishlist.*;
import ru.selfin.backend.service.WishlistConversionService;
import ru.selfin.backend.service.WishlistSimulationService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistSimulationService simulationService;
    private final WishlistConversionService conversionService;

    @GetMapping("/simulation")
    public WishlistSimulationDto getSimulation(
            @RequestParam(defaultValue = "36") int horizonMonths) {
        int safe = Math.min(Math.max(horizonMonths, 1), 60);
        return simulationService.getSimulation(safe);
    }

    @PostMapping("/simulation/recompute")
    public RecomputeResponseDto recompute(@Valid @RequestBody RecomputeRequestDto req) {
        return simulationService.recomputeItemDelta(req);
    }

    @PostMapping("/items/{itemId}/convert")
    public ConvertWishlistResponseDto convert(
            @PathVariable UUID itemId,
            @Valid @RequestBody ConvertWishlistRequestDto req) {
        return conversionService.convertItem(itemId, req);
    }
}
