package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.dto.capital.CapitalSummaryDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CapitalItemRepository;
import ru.selfin.backend.repository.CapitalRevaluationRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты на формулу {@code liquidAt(t)} через публичный путь {@code summary()}.
 * Покрывают сценарии:
 * <ul>
 *   <li>пустая БД → liquid = 0;</li>
 *   <li>checkpoint + INCOME/EXPENSE/FUND_TRANSFER + копилки → корректная сумма
 *       без двойного учёта FUND_TRANSFER и FundTransaction;</li>
 *   <li>отсутствие checkpoint'а → используется sentinel LocalDate.of(1970,1,1).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CapitalServiceLiquidTest {

    @Mock CapitalItemRepository itemRepo;
    @Mock CapitalRevaluationRepository revRepo;
    @Mock BalanceCheckpointRepository checkpointRepo;
    @Mock FinancialEventRepository eventRepo;
    @Mock FundTransactionRepository fundTxRepo;

    @InjectMocks CapitalService service;

    @Test
    void summary_emptyDb_liquidIsZero() {
        when(itemRepo.findAllActive(null)).thenReturn(List.of());
        when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.empty());
        when(eventRepo.sumFactByTypeBetween(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        assertThat(s.liquid()).isEqualByComparingTo("0");
        assertThat(s.total()).isEqualByComparingTo("0");
    }

    @Test
    void summary_withCheckpointAndEventsAndPockets_computesLiquidCorrectly() {
        // checkpoint=200к, после него INCOME=50к, EXPENSE=10к, FUND_TRANSFER=30к, копилок=30к.
        // accountBalance = 200 + 50 − 10 − 30 = 210
        // pocketBalance  = 30
        // liquid         = 240
        BalanceCheckpoint cp = BalanceCheckpoint.builder()
                .date(LocalDate.now().minusDays(30))
                .amount(new BigDecimal("200000"))
                .build();
        when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.of(cp));
        when(eventRepo.sumFactByTypeBetween(eq(EventType.INCOME),        any(), any())).thenReturn(new BigDecimal("50000"));
        when(eventRepo.sumFactByTypeBetween(eq(EventType.EXPENSE),       any(), any())).thenReturn(new BigDecimal("10000"));
        when(eventRepo.sumFactByTypeBetween(eq(EventType.FUND_TRANSFER), any(), any())).thenReturn(new BigDecimal("30000"));
        when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(new BigDecimal("30000"));
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        assertThat(s.liquid()).isEqualByComparingTo("240000");
        assertThat(s.total()).isEqualByComparingTo("240000");
    }

    @Test
    void summary_noCheckpoint_usesSentinelEpochAsLowerBound() {
        when(checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(any())).thenReturn(Optional.empty());
        // Если код корректно использует sentinel 1970-01-01, вызов sumFactByTypeBetween
        // с from = 1970-01-01 должен дать нашу замоканную сумму:
        when(eventRepo.sumFactByTypeBetween(eq(EventType.INCOME),
                eq(LocalDate.of(1970, 1, 1)), any())).thenReturn(new BigDecimal("12345"));
        when(eventRepo.sumFactByTypeBetween(eq(EventType.EXPENSE),       any(), any())).thenReturn(BigDecimal.ZERO);
        when(eventRepo.sumFactByTypeBetween(eq(EventType.FUND_TRANSFER), any(), any())).thenReturn(BigDecimal.ZERO);
        when(fundTxRepo.sumByTransactionDateLessThanEqual(any())).thenReturn(BigDecimal.ZERO);
        when(revRepo.snapshotAt(any())).thenReturn(List.of());
        when(itemRepo.findAllActive(null)).thenReturn(List.of());

        CapitalSummaryDto s = service.summary();

        // 12 345 пришло только потому, что код реально использовал sentinel.
        assertThat(s.liquid()).isEqualByComparingTo("12345");
    }
}
