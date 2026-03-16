package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TargetFundServiceTest {

    private TargetFundRepository fundRepository;
    private FundTransactionRepository transactionRepository;
    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private CategoryRepository categoryRepository;
    private TargetFundService service;

    @BeforeEach
    void setUp() {
        fundRepository = mock(TargetFundRepository.class);
        transactionRepository = mock(FundTransactionRepository.class);
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        service = new TargetFundService(
                fundRepository, transactionRepository,
                eventRepository, checkpointRepository, categoryRepository);
    }

    @Test
    @DisplayName("Кармашек: без чекпоинта = факт доходов − факт расходов − балансы фондов (только EXECUTED)")
    void pocketBalance_noCheckpoint_usesExecutedOnly() {
        // Нет чекпоинта
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());

        // Нет фондов — баланс фондов = 0
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());

        // Только исполненные события учитываются
        when(eventRepository.sumFactExecutedByType(EventType.INCOME)).thenReturn(bd(100_000));
        when(eventRepository.sumFactExecutedByType(EventType.EXPENSE)).thenReturn(bd(30_000));

        FundsOverviewDto overview = service.getOverview();

        // 100_000 - 30_000 - 0 = 70_000
        assertThat(overview.pocketBalance()).isEqualByComparingTo(bd(70_000));
    }

    @Test
    @DisplayName("Кармашек: с чекпоинтом = чекпоинт + факт доходов − факт расходов − балансы фондов (только EXECUTED с даты чекпоинта)")
    void pocketBalance_withCheckpoint_usesExecutedFromDate() {
        LocalDate cpDate = LocalDate.of(2026, 3, 1);
        BalanceCheckpoint cp = BalanceCheckpoint.builder()
                .id(UUID.randomUUID())
                .date(cpDate)
                .amount(bd(50_000))
                .build();
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));

        // Один фонд с балансом 10_000 (не POCKET — входит в fundBalances)
        TargetFund fund = TargetFund.builder()
                .id(UUID.randomUUID())
                .name("Отпуск")
                .currentBalance(bd(10_000))
                .targetAmount(bd(100_000))
                .priority(1)
                .status(FundStatus.FUNDING)
                .deleted(false)
                .build();
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(fund));
        // Прогноз завершения: нет транзакций за последние 3 месяца
        when(transactionRepository.findByFundIdAndDeletedFalseAndTransactionDateAfter(any(), any()))
                .thenReturn(List.of());

        when(eventRepository.sumFactExecutedByTypeFromDate(EventType.INCOME, cpDate)).thenReturn(bd(75_000));
        when(eventRepository.sumFactExecutedByTypeFromDate(EventType.EXPENSE, cpDate)).thenReturn(bd(20_000));

        FundsOverviewDto overview = service.getOverview();

        // 50_000 + 75_000 - 20_000 - 10_000 = 95_000
        assertThat(overview.pocketBalance()).isEqualByComparingTo(bd(95_000));
    }

    @Test
    @DisplayName("Кармашек: PLANNED события не учитываются — sumEffectiveByType не вызывается")
    void pocketBalance_doesNotCallSumEffective() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
        when(eventRepository.sumFactExecutedByType(any())).thenReturn(BigDecimal.ZERO);

        service.getOverview();

        // Убеждаемся что старые «effective» методы не вызываются
        verify(eventRepository, never()).sumEffectiveByType(any());
        verify(eventRepository, never()).sumEffectiveByTypeFromDate(any(), any());
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
