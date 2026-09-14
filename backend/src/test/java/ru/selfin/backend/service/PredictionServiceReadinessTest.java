package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Готовность прогноза (ANO-80).
 *
 * <p>Случай, который иначе читается как поломка: человек поставил галочку, а ничего не
 * изменилось — месяцев наблюдения ещё мало. Экрану нужно уметь сказать, когда прогноз
 * появится, иначе единственный доступный вывод — «не работает».
 */
class PredictionServiceReadinessTest {

    /** «Сегодня» = 14 сентября 2026, последний полный месяц — август. */
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 9, 14).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    private FinancialEventRepository eventRepo;
    private PredictionService service;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        service = new PredictionService(eventRepo, mock(CategoryRepository.class), FIXED);
    }

    @Test
    @DisplayName("ANO-80: наблюдений хватает — прогноз готов, месяц готовности пуст")
    void readiness_enoughObservation_isReady() {
        when(eventRepo.findFirstSpendingDate()).thenReturn(LocalDate.of(2026, 3, 9));

        // март отброшен как неполный → наблюдение апрель..август = пять месяцев
        assertThat(service.readiness().monthsObserved()).isEqualTo(5);
        assertThat(service.readiness().readyFrom()).isNull();
    }

    @Test
    @DisplayName("ANO-80: наблюдений мало — сказано, с какого месяца прогноз появится")
    void readiness_belowThreshold_namesTheMonth() {
        when(eventRepo.findFirstSpendingDate()).thenReturn(LocalDate.of(2026, 7, 20));

        // наблюдение с августа = один месяц; нужно три, значит ещё два — с ноября
        assertThat(service.readiness().monthsObserved()).isEqualTo(1);
        assertThat(service.readiness().readyFrom()).isEqualTo("2026-11");
    }

    @Test
    @DisplayName("ANO-80: трат нет вовсе — ноль наблюдений и пустой месяц готовности")
    void readiness_noSpending_isEmpty() {
        when(eventRepo.findFirstSpendingDate()).thenReturn(null);

        assertThat(service.readiness().monthsObserved()).isZero();
        assertThat(service.readiness().readyFrom())
                .as("считать не от чего: обещать месяц было бы выдумкой")
                .isNull();
    }

    @Test
    @DisplayName("ANO-80: учёт начат в прошлом месяце — полных месяцев ноль, готовность через три")
    void readiness_startedLastMonth_countsZero() {
        when(eventRepo.findFirstSpendingDate()).thenReturn(LocalDate.of(2026, 8, 3));

        assertThat(service.readiness().monthsObserved()).isZero();
        assertThat(service.readiness().readyFrom()).isEqualTo("2026-12");
    }

    @Test
    @DisplayName("ANO-80: порог берётся из общей константы, а не из своей копии")
    void readiness_reportsTheSharedThreshold() {
        when(eventRepo.findFirstSpendingDate()).thenReturn(LocalDate.of(2026, 7, 20));

        assertThat(service.readiness().monthsRequired())
                .isEqualTo(PredictionService.MIN_HISTORY_MONTHS);
    }
}
