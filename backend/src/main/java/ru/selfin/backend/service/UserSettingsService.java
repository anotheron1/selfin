package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.model.UserSettings;
import ru.selfin.backend.repository.UserSettingsRepository;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private static final String KEY = "wishlist";
    private final UserSettingsRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WishlistThresholdsDto getWishlistSettings() {
        return repo.findBySettingsKey(KEY)
                .map(this::parse)
                .orElse(new WishlistThresholdsDto(null, new BigDecimal("1.0")));
    }

    @Transactional
    public WishlistThresholdsDto updateWishlistSettings(WishlistThresholdsDto dto) {
        validate(dto);
        UserSettings entity = repo.findBySettingsKey(KEY).orElseGet(() ->
                UserSettings.builder().settingsKey(KEY).build());
        entity.setSettingsValue(serialize(dto));
        repo.save(entity);
        return dto;
    }

    private void validate(WishlistThresholdsDto dto) {
        if (dto.cashBufferMonths() == null
                || dto.cashBufferMonths().compareTo(BigDecimal.ZERO) < 0
                || dto.cashBufferMonths().compareTo(new BigDecimal("36")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cashBufferMonths must be in [0, 36]");
        }
        if (dto.capitalThresholdRub() != null
                && dto.capitalThresholdRub().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capitalThresholdRub must be >= 0");
        }
    }

    private WishlistThresholdsDto parse(UserSettings s) {
        try {
            return objectMapper.readValue(s.getSettingsValue(), WishlistThresholdsDto.class);
        } catch (Exception e) {
            return new WishlistThresholdsDto(null, new BigDecimal("1.0"));
        }
    }

    private String serialize(WishlistThresholdsDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "serialize settings");
        }
    }
}
