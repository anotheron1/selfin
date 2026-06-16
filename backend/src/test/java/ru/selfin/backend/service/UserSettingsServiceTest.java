package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
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
        assertThat(dto.cashBufferMonths()).isEqualByComparingTo("1.0");
    }

    @Test
    void updateWishlistSettings_negativeBuffer_throws() {
        assertThatThrownBy(() -> service.updateWishlistSettings(
                new WishlistThresholdsDto(BigDecimal.ZERO, new BigDecimal("-1"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void updateWishlistSettings_bufferAbove36_throws() {
        assertThatThrownBy(() -> service.updateWishlistSettings(
                new WishlistThresholdsDto(BigDecimal.ZERO, new BigDecimal("37"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void updateWishlistSettings_nullCapitalThreshold_isAllowed() {
        when(repo.findBySettingsKey("wishlist")).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        var saved = service.updateWishlistSettings(
                new WishlistThresholdsDto(null, new BigDecimal("1.0")));
        assertThat(saved.capitalThresholdRub()).isNull();   // null = capital criterion disabled
    }
}
