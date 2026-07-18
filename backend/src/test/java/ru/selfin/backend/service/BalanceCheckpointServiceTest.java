package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.BalanceCheckpointDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Дрейф цепочки чекпоинтов (ANO-15 §4): computedBalance = prev.amount + факты (prev.date, cur.date],
 * drift = amount − computedBalance. Правило фактов — как в PocketEngine.currentBalance.
 */
class BalanceCheckpointServiceTest {

    private BalanceCheckpointRepository repository;
    private FinancialEventRepository eventRepository;
    private BalanceCheckpointService service;

    @BeforeEach
    void setUp() {
        repository = mock(BalanceCheckpointRepository.class);
        eventRepository = mock(FinancialEventRepository.class);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        service = new BalanceCheckpointService(repository, eventRepository);
    }

    private static BalanceCheckpoint cp(LocalDate date, long amount, LocalDateTime createdAt) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(BigDecimal.valueOf(amount))
                .createdAt(createdAt).updatedAt(createdAt)
                .build();
    }

    private static FinancialEvent fact(LocalDate date, EventType type, long amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).date(date).type(type)
                .eventKind(EventKind.FACT).factAmount(BigDecimal.valueOf(amount))
                .status(EventStatus.EXECUTED).priority(Priority.MEDIUM).deleted(false)
                .build();
    }

    private static FinancialEvent wishlistFact(LocalDate date, long amount) {
        FinancialEvent e = fact(date, EventType.EXPENSE, amount);
        e.setWishlistStatus(WishlistStatus.FIXED);
        return e;
    }

    @Test
    @DisplayName("Цепочка из 3: дрейф каждого интервала; день prev исключён, день cur включён, wishlist игнор")
    void driftChain() {
        LocalDateTime t = LocalDateTime.of(2026, 4, 1, 12, 0);
        BalanceCheckpoint c1 = cp(LocalDate.of(2026, 3, 1), 10_000, t.minusDays(40));
        BalanceCheckpoint c2 = cp(LocalDate.of(2026, 3, 15), 12_000, t.minusDays(17));
        BalanceCheckpoint c3 = cp(LocalDate.of(2026, 4, 1), 9_000, t);
        when(repository.findAllByOrderByDateDesc()).thenReturn(List.of(c3, c2, c1));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of(
                        fact(LocalDate.of(2026, 3, 1), EventType.INCOME, 999),    // день c1 — исключён
                        fact(LocalDate.of(2026, 3, 5), EventType.INCOME, 5_000),  // интервал c2
                        fact(LocalDate.of(2026, 3, 15), EventType.EXPENSE, 1_000),// день c2 — включён в c2
                        wishlistFact(LocalDate.of(2026, 3, 10), 7_777),           // игнор
                        fact(LocalDate.of(2026, 3, 20), EventType.EXPENSE, 2_000) // интервал c3
                ));

        List<BalanceCheckpointDto> dtos = service.findAll();

        assertThat(dtos).hasSize(3);
        BalanceCheckpointDto d3 = dtos.get(0); // 01.04
        BalanceCheckpointDto d2 = dtos.get(1); // 15.03
        BalanceCheckpointDto d1 = dtos.get(2); // 01.03 — самый ранний

        assertThat(d2.computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(14_000)); // 10k +5k −1k
        assertThat(d2.drift()).isEqualByComparingTo(BigDecimal.valueOf(-2_000));
        assertThat(d3.computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000)); // 12k −2k
        assertThat(d3.drift()).isEqualByComparingTo(BigDecimal.valueOf(-1_000));
        assertThat(d1.computedBalance()).isNull();
        assertThat(d1.drift()).isNull();
    }

    @Test
    @DisplayName("Дубль дня (исправление опечатки): пустой интервал, дрейф = разница правок")
    void sameDayDuplicate_driftIsCorrectionDiff() {
        LocalDateTime t = LocalDateTime.of(2026, 4, 1, 12, 0);
        BalanceCheckpoint first = cp(LocalDate.of(2026, 4, 1), 12_000, t);
        BalanceCheckpoint fixed = cp(LocalDate.of(2026, 4, 1), 12_500, t.plusMinutes(5));
        // Порядок репозитория: date DESC, createdAt DESC — поздняя правка первой
        when(repository.findAllByOrderByDateDesc()).thenReturn(List.of(fixed, first));

        List<BalanceCheckpointDto> dtos = service.findAll();

        assertThat(dtos.get(0).computedBalance()).isEqualByComparingTo(BigDecimal.valueOf(12_000));
        assertThat(dtos.get(0).drift()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(dtos.get(1).drift()).isNull();
    }

    @Test
    @DisplayName("Один чекпоинт: полей дрейфа нет, события не запрашиваются")
    void singleCheckpoint_noDriftNoEventQuery() {
        when(repository.findAllByOrderByDateDesc())
                .thenReturn(List.of(cp(LocalDate.of(2026, 4, 1), 12_000, LocalDateTime.now())));

        List<BalanceCheckpointDto> dtos = service.findAll();

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).computedBalance()).isNull();
        assertThat(dtos.get(0).drift()).isNull();
        verifyNoInteractions(eventRepository);
    }
}
