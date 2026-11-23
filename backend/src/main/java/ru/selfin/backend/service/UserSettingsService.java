package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.HintsSettingsDto;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.model.UserSettings;
import ru.selfin.backend.repository.UserSettingsRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private static final String KEY = "wishlist";
    private static final String HINTS_KEY = "hints";
    private static final Pattern HINT_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private final UserSettingsRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WishlistThresholdsDto getWishlistSettings() {
        return repo.findBySettingsKey(KEY)
                .map(this::parse)
                .orElse(new WishlistThresholdsDto(null));
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
        if (dto.capitalThresholdRub() != null
                && dto.capitalThresholdRub().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capitalThresholdRub must be >= 0");
        }
    }

    /** Подсказки первого дня (срез 2, п. 12): включены, ни одна не закрыта, пока человек их не тронул. */
    @Transactional(readOnly = true)
    public HintsSettingsDto getHints() {
        return repo.findBySettingsKey(HINTS_KEY)
                .map(s -> {
                    try {
                        return objectMapper.readValue(s.getSettingsValue(), HintsSettingsDto.class);
                    } catch (Exception e) {
                        return null;   // не разобралась — умолчание, как у прочих ключей
                    }
                })
                .orElse(new HintsSettingsDto(false, List.of()));
    }

    @Transactional
    public HintsSettingsDto updateHints(HintsSettingsDto dto) {
        // null — не «ничего не закрыто», а тело без списка; имена — короткие слова из кода экрана.
        if (dto.closed() == null || dto.closed().size() > 32
                || !dto.closed().stream().allMatch(id -> id != null && HINT_ID.matcher(id).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "closed must be a list of hint ids");
        }
        UserSettings entity = repo.findBySettingsKey(HINTS_KEY).orElseGet(() ->
                UserSettings.builder().settingsKey(HINTS_KEY).build());
        entity.setSettingsValue(serialize(dto));
        repo.save(entity);
        return dto;
    }

    private WishlistThresholdsDto parse(UserSettings s) {
        try {
            return objectMapper.readValue(s.getSettingsValue(), WishlistThresholdsDto.class);
        } catch (Exception e) {
            return new WishlistThresholdsDto(null);
        }
    }

    private String serialize(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "serialize settings");
        }
    }
}
