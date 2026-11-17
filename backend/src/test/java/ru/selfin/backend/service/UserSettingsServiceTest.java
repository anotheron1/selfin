package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.HintsSettingsDto;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.model.UserSettings;
import ru.selfin.backend.repository.UserSettingsRepository;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    // ── подсказки первого дня (срез 2, п. 12): ключ «hints», { off, closed[] } ──────────────

    @Test
    void getHints_firstCall_onAndNoneClosed() {
        when(repo.findBySettingsKey("hints")).thenReturn(Optional.empty());
        assertThat(service.getHints()).isEqualTo(new HintsSettingsDto(false, List.of()));
    }

    @Test
    void updateHints_savesUnderOwnKey_andReadsBack() {
        when(repo.findBySettingsKey("hints")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service.updateHints(new HintsSettingsDto(true, List.of("number", "plus")));

        ArgumentCaptor<UserSettings> saved = ArgumentCaptor.forClass(UserSettings.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getSettingsKey()).isEqualTo("hints");

        when(repo.findBySettingsKey("hints")).thenReturn(Optional.of(saved.getValue()));
        assertThat(service.getHints()).isEqualTo(new HintsSettingsDto(true, List.of("number", "plus")));
    }

    @Test
    void updateHints_closedMissing_throws() {
        // null — не «ничего не закрыто»: тело без списка — ошибка клиента, а не сброс закрытых.
        assertThatThrownBy(() -> service.updateHints(new HintsSettingsDto(true, null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void updateHints_strangeName_throws() {
        // Имя подсказки — короткое слово из кода экрана: в jsonb человека не пишется что угодно.
        for (List<String> closed : List.of(List.of("Склон"), List.of(""), List.of("x".repeat(33)),
                Collections.nCopies(33, "number"), Arrays.asList("number", null))) {
            assertThatThrownBy(() -> service.updateHints(new HintsSettingsDto(false, closed)))
                    .as("%s", closed).isInstanceOf(ResponseStatusException.class);
        }
        verify(repo, never()).save(any());
    }
}
