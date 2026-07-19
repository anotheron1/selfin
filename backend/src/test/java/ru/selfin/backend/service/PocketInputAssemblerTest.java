package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.SandboxRef;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Резервирование FIXED-копилок в baseline (ANO-16 §6): раскладка, края, baselineRefs. */
class PocketInputAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final PocketScope MONTHS_6 = new PocketScope(PocketScope.Type.MONTHS, 6, null);

    private FinancialEventRepository eventRepository;
    private TargetFundRepository fundRepository;
    private PocketInputAssembler assembler;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        fundRepository = mock(TargetFundRepository.class);
        BalanceCheckpointRepository checkpointRepository = mock(BalanceCheckpointRepository.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        PredictionService predictionService = mock(PredictionService.class);
        RecurringRuleService recurringRuleService = mock(RecurringRuleService.class);

        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(eventRepository.findOverdueMandatoryExpenses(any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findPlannedIncomeDates(any(), any(), any())).thenReturn(List.of());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        when(fundRepository.findByWishlistStatusAndDeletedFalse(WishlistStatus.FIXED))
                .thenReturn(List.of());

        assembler = new PocketInputAssembler(eventRepository, checkpointRepository,
                settingsService, predictionService, recurringRuleService, fundRepository);
    }

    private static TargetFund fund(String name, long target, long balance,
                                   LocalDate targetDate, FundPurchaseType type) {
        return TargetFund.builder()
                .id(UUID.randomUUID())
                .name(name)
                .targetAmount(BigDecimal.valueOf(target))
                .currentBalance(BigDecimal.valueOf(balance))
                .targetDate(targetDate)
                .purchaseType(type)
                .wishlistStatus(WishlistStatus.FIXED)
                .build();
    }

    private void fixedFunds(TargetFund... funds) {
        when(fundRepository.findByWishlistStatusAndDeletedFalse(WishlistStatus.FIXED))
                .thenReturn(List.of(funds));
    }

    private List<EventSnapshot> contributions(PocketInputAssembler.Assembled a) {
        return a.input().events().stream()
                .filter(e -> e.syntheticKind() == SyntheticKind.SAVINGS_CONTRIBUTION)
                .toList();
    }

    @Test
    @DisplayName("Датированная FIXED SAVINGS-копилка: взносы остаток/n в первые доходы месяцев")
    void datedFixedSavingsFund_reserved() {
        // Остаток 80 000 − 20 000 = 60 000; цель 10.08 → n = 5 (апр..авг), взнос 12 000
        TargetFund f = fund("Египет", 80_000, 20_000, LocalDate.of(2026, 8, 10), FundPurchaseType.SAVINGS);
        fixedFunds(f);
        // Полное окно доходов: апрель имеет доход 15.04, дальше пусто → 1-е число
        when(eventRepository.findPlannedIncomeDates(eq(TODAY), any(), any()))
                .thenReturn(List.of(LocalDate.of(2026, 4, 15)));

        PocketInputAssembler.Assembled a = assembler.build(MONTHS_6, TODAY);

        List<EventSnapshot> contribs = contributions(a);
        assertThat(contribs).extracting(EventSnapshot::date).containsExactly(
                LocalDate.of(2026, 4, 15),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1));
        assertThat(contribs).extracting(EventSnapshot::plannedAmount)
                .allSatisfy(x -> assertThat(x).isEqualByComparingTo("12000.00"));
        assertThat(contribs).allSatisfy(e -> assertThat(e.description()).isEqualTo("Египет"));
        assertThat(a.baselineRefs()).containsKey(SandboxRef.fund(f.getId()));
        assertThat(a.baselineRefs().get(SandboxRef.fund(f.getId()))).hasSize(5);
    }

    @Test
    @DisplayName("Края §6: накоплено, протухшая цель, без даты, CREDIT, конвертирована — не резервируются")
    void edges_notReserved() {
        TargetFund saved = fund("Накоплено", 50_000, 50_000, LocalDate.of(2026, 8, 10), FundPurchaseType.SAVINGS);
        TargetFund stale = fund("Протухла", 50_000, 0, LocalDate.of(2026, 3, 20), FundPurchaseType.SAVINGS);
        TargetFund past = fund("Прошлое", 50_000, 0, LocalDate.of(2025, 12, 1), FundPurchaseType.SAVINGS);
        TargetFund undated = fund("Доска", 80_000, 0, null, FundPurchaseType.SAVINGS);
        TargetFund credit = fund("Машина", 1_300_000, 0, LocalDate.of(2026, 9, 1), FundPurchaseType.CREDIT);
        TargetFund converted = fund("Конверт", 50_000, 0, LocalDate.of(2026, 8, 10), FundPurchaseType.SAVINGS);
        converted.setConvertedToFundId(UUID.randomUUID());
        fixedFunds(saved, stale, past, undated, credit, converted);

        PocketInputAssembler.Assembled a = assembler.build(MONTHS_6, TODAY);

        assertThat(contributions(a)).isEmpty();
        assertThat(a.baselineRefs().keySet())
                .noneMatch(r -> r.type() == SandboxRef.RefType.FUND);
    }

    @Test
    @DisplayName("baselineRefs: датированное FIXED-событие-хотелка тоже операционально в baseline")
    void fixedDatedEvent_inBaselineRefs() {
        UUID id = UUID.randomUUID();
        EventSnapshot fixedEvent = new EventSnapshot(id, LocalDate.of(2026, 3, 20),
                ru.selfin.backend.model.enums.EventType.EXPENSE,
                ru.selfin.backend.model.EventKind.PLAN,
                ru.selfin.backend.model.enums.EventStatus.PLANNED,
                ru.selfin.backend.model.enums.Priority.LOW,
                BigDecimal.valueOf(8_500), null, WishlistStatus.FIXED, false, "Рюкзак");
        // Ассемблер строит снапшоты из FinancialEvent — подсовываем через маппинг не выйдет,
        // поэтому проверяем через реальный FinancialEvent
        ru.selfin.backend.model.FinancialEvent entity = ru.selfin.backend.model.FinancialEvent.builder()
                .id(id)
                .date(fixedEvent.date())
                .type(fixedEvent.type())
                .eventKind(fixedEvent.eventKind())
                .status(fixedEvent.status())
                .priority(fixedEvent.priority())
                .plannedAmount(fixedEvent.plannedAmount())
                .wishlistStatus(WishlistStatus.FIXED)
                .description("Рюкзак")
                .build();
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(entity));

        PocketInputAssembler.Assembled a = assembler.build(MONTHS_6, TODAY);

        assertThat(a.baselineRefs()).containsKey(SandboxRef.event(id));
    }
}
