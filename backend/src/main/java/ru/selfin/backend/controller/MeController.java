package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.service.OwnerService;

import java.util.Map;
import java.util.UUID;

/**
 * Чей это запрос (ANO-195): id владельца из {@code users}. До ограничителя (шаг 5 плана спринта 5)
 * по экрану не отличить, связан ли вход с синтетическим владельцем, — отличает этот ответ.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeController {

    private final OwnerService owners;

    @GetMapping("/me")
    public Map<String, UUID> me() {
        return Map.of("id", owners.current());
    }
}
