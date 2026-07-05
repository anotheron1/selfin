package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.service.PocketService;

import java.time.LocalDate;

/** Кармашек — единый ответ «сколько свободно и почему» (спека §6). */
@RestController
@RequestMapping("/api/v1/pocket")
@RequiredArgsConstructor
public class PocketController {

    private final PocketService pocketService;

    @GetMapping
    public PocketResultDto get(@RequestParam(required = false) String scope) {
        return pocketService.getPocket(scope, LocalDate.now());
    }
}
