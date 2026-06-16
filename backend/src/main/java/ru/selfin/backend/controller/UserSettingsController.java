package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
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

    @PutMapping("/wishlist")
    public WishlistThresholdsDto update(@Valid @RequestBody WishlistThresholdsDto dto) {
        return service.updateWishlistSettings(dto);
    }
}
