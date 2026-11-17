package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.HintsSettingsDto;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
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
    private static final String POCKET_KEY = "pocket";
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

    @Transactional(readOnly = true)
    public PocketSettingsDto getPocketSettings() {
        return repo.findBySettingsKey(POCKET_KEY)
                .map(this::parsePocket)
                .orElse(new PocketSettingsDto(BigDecimal.ZERO));
    }

    @Transactional
    public PocketSettingsDto updatePocketSettings(PocketSettingsDto dto) {
        if (dto.bufferAmount() == null || dto.bufferAmount().compareTo(BigDecimal.ZERO) < 0) {
            // null не означает «сбросить» — сброс = явный PUT с 0 (спека §7)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bufferAmount must be >= 0");
        }
        UserSettings entity = repo.findBySettingsKey(POCKET_KEY).orElseGet(() ->
                UserSettings.builder().settingsKey(POCKET_KEY).build());
        entity.setSettingsValue(serializePocket(dto));
        repo.save(entity);
        return dto;
    }

    /** Подсказки первого дня (срез 2, п. 12): включены, ни одна не закрыта, пока человек их не тронул. */
    @Transactional(readOnly = true)
    public HintsSettingsDto getHints() {
        return repo.findBySettingsKey(HINTS_KEY)
                .map(s -> {
                    try {
                        return objectMapper.readValue(s.getSettingsValue(), HintsSettingsDto.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(h -> h.closed() != null)
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

    private PocketSettingsDto parsePocket(UserSettings s) {
        try {
            return objectMapper.readValue(s.getSettingsValue(), PocketSettingsDto.class);
        } catch (Exception e) {
            return new PocketSettingsDto(BigDecimal.ZERO);
        }
    }

    private String serializePocket(PocketSettingsDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "serialize settings");
        }
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
