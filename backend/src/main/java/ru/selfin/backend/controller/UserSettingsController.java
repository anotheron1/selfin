package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.HintsSettingsDto;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.service.UserSettingsService;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService service;

    @GetMapping("/wishlist")
    public WishlistThresholdsDto get() {
        return service.getWishlistSettings();
    }

    @WritesData
    @PutMapping("/wishlist")
    public WishlistThresholdsDto update(@Valid @RequestBody WishlistThresholdsDto dto) {
        return service.updateWishlistSettings(dto);
    }

    /** Подсказки первого дня (срез 2, п. 12): телефон и компьютер видят одно. */
    @GetMapping("/hints")
    public HintsSettingsDto getHints() {
        return service.getHints();
    }

    /** Закрыть подсказку — не запись данных человека: день ввода не засчитывается (ANO-78). */
    @WritesData(false)
    @PutMapping("/hints")
    public HintsSettingsDto updateHints(@RequestBody HintsSettingsDto dto) {
        return service.updateHints(dto);
    }
}
