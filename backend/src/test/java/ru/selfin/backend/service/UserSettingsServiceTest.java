package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.model.UserSettings;
import ru.selfin.backend.repository.UserSettingsRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSettingsServiceTest {

    private final UserSettingsRepository repo = mock(UserSettingsRepository.class);
    private final UserSettingsService service = new UserSettingsService(repo, new ObjectMapper());

    @Test
    void getWishlistSettings_firstCall_returnsDefaults() {
        when(repo.findBySettingsKey("wishlist")).thenReturn(Optional.empty());
        WishlistThresholdsDto dto = service.getWishlistSettings();
        assertThat(dto.capitalThresholdRub()).isNull();
    }

    @Test
    void getWishlistSettings_storedWithOldCushionField_keepsCapitalThreshold() {
        // Р5 (ANO-93): порог «Подушка, мес.» ушёл, а записи настроек на базах его хранят. Разбор
        // здесь строгий, как у ObjectMapper по умолчанию: без пропуска лишнего поля он падает, и
        // сервис молча отдаёт умолчание — «Мин. капитал» пропал бы.
        when(repo.findBySettingsKey("wishlist")).thenReturn(Optional.of(UserSettings.builder()
                .settingsKey("wishlist")
                .settingsValue("{\"capitalThresholdRub\":500000,\"cashBufferMonths\":2}")
                .build()));
        assertThat(service.getWishlistSettings().capitalThresholdRub()).isEqualByComparingTo("500000");
    }

    @Test
    void updateWishlistSettings_negativeCapitalThreshold_throws() {
        assertThatThrownBy(() -> service.updateWishlistSettings(new WishlistThresholdsDto(new BigDecimal("-1"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void updateWishlistSettings_nullCapitalThreshold_isAllowed() {
        when(repo.findBySettingsKey("wishlist")).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        var saved = service.updateWishlistSettings(
                new WishlistThresholdsDto(null));
        assertThat(saved.capitalThresholdRub()).isNull();   // null = capital criterion disabled
    }
}
