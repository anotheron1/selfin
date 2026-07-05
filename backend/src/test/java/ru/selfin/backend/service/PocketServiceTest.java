package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Тесты сборки входа: разрешение горизонта, кап 92 дня, маппинг ошибок на 400. */
class PocketServiceTest {

    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private UserSettingsService settingsService;
    private PredictionService predictionService;
    private PocketService pocketService;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        settingsService = mock(UserSettingsService.class);
        predictionService = mock(PredictionService.class);

        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(eventRepository.findOverdueMandatoryExpenses(any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findNextPlannedIncomeDate(any(), any())).thenReturn(Optional.empty());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));

        pocketService = new PocketService(eventRepository, checkpointRepository,
                settingsService, predictionService);
    }

    @Test
    @DisplayName("NEXT_INCOME: доход найден в пределах 92 дней — горизонт до него")
    void nextIncome_found() {
        when(eventRepository.findNextPlannedIncomeDate(TODAY, TODAY.plusDays(92)))
                .thenReturn(Optional.of(LocalDate.of(2026, 3, 15)));
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(r.horizon().fallback()).isFalse();
        assertThat(r.horizon().type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    @DisplayName("NEXT_INCOME: дохода нет — фолбэк +30 дней с флагом")
    void nextIncome_fallback() {
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(r.horizon().fallback()).isTrue();
    }

    @Test
    @DisplayName("MONTHS:3 — горизонт +3 месяца")
    void monthsScope() {
        PocketResultDto r = pocketService.getPocket("MONTHS:3", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusMonths(3));
    }

    @Test
    @DisplayName("DATE в прошлом или дальше 36 мес → 400")
    void dateScope_validation() {
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2026-02-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2030-01-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Мусорный скоуп → 400 (ResponseStatusException)")
    void garbageScope_400() {
        assertThatThrownBy(() -> pocketService.getPocket("GARBAGE", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Отрицательная netPredictionDelta зажимается в 0")
    void negativeForecast_clamped() {
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), new BigDecimal("-500")));
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.breakdown()).noneMatch(l ->
                l.type() == ru.selfin.backend.dto.pocket.BreakdownType.UNPLANNED_FORECAST);
    }
}
