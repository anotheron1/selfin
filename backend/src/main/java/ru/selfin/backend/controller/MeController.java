package ru.selfin.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.service.OwnerContext;

import java.util.Map;
import java.util.UUID;

/**
 * Чей это запрос (ANO-195): id владельца из {@code users}. До ограничителя (шаг 5 плана спринта 5)
 * по экрану не отличить, связан ли вход с синтетическим владельцем, — отличает этот ответ. С ANO-144 — тот
 * владелец, с которым запрос ходит в базу: его ставит {@code OwnerFilter}.
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    @GetMapping("/me")
    public Map<String, UUID> me() {
        return Map.of("id", OwnerContext.get());
    }
}
