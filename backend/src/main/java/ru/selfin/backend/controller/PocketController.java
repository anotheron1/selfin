package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.sandbox.SandboxRequestDto;
import ru.selfin.backend.dto.pocket.sandbox.SandboxResponseDto;
import ru.selfin.backend.service.PocketSandboxService;
import ru.selfin.backend.service.PocketService;

import java.time.LocalDate;

/** Кармашек — единый ответ «сколько свободно и почему» (спека §6) + примерка ANO-16. */
@RestController
@RequestMapping("/api/v1/pocket")
@RequiredArgsConstructor
public class PocketController {

    private final PocketService pocketService;
    private final PocketSandboxService sandboxService;

    @GetMapping
    public PocketResultDto get(@RequestParam(required = false) String scope) {
        return pocketService.getPocket(scope, LocalDate.now());
    }

    /** Примерка «что если» (спека sandbox §4): ничего не пишет, движок с подменённым входом. */
    @PostMapping("/sandbox")
    public SandboxResponseDto sandbox(@RequestBody SandboxRequestDto request) {
        return sandboxService.simulate(request, LocalDate.now());
    }
}
