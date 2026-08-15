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
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Резервирование FIXED-копилок в baseline (ANO-16 §6): раскладка, края, baselineRefs. */
class PocketInputAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final PocketScope MONTHS_6 = new PocketScope(PocketScope.Type.MONTHS, 6, null);

    private FinancialEventRepository eventRepository;
    private TargetFundRepository fundRepository;
    private CategoryRepository categoryRepository;
    private AccountBalanceService accountBalanceService;
    private PocketInputAssembler assembler;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        fundRepository = mock(TargetFundRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        accountBalanceService = mock(AccountBalanceService.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        PredictionService predictionService = mock(PredictionService.class);
        RecurringRuleService recurringRuleService = mock(RecurringRuleService.class);

        // По умолчанию — «дефолтного счёта нет» (Mockito отдаёт Optional.empty() на
        // незастабленный Optional-метод): тот же эффект, что раньше давал пустой
        // checkpointRepository.findTopByOrderByDateDesc() — чекпоинта нет вовсе.
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(eventRepository.findOverdueMandatoryExpenses(any(), any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findPlannedIncomeDates(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        when(fundRepository.findByWishlistStatusAndDeletedFalse(WishlistStatus.FIXED))
                .thenReturn(List.of());
        // По умолчанию — «счетов, кроме дефолтного, нет»: эти тесты про резервирование
        // копилок и горизонт, а не про счета (ANO-9 Task 2.2 покрыта отдельно).
        when(accountBalanceService.snapshot(any(), any()))
                .thenReturn(new AccountBalanceService.Snapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assembler = new PocketInputAssembler(eventRepository,
                settingsService, predictionService, recurringRuleService, fundRepository, categoryRepository,
                accountBalanceService);
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
        when(eventRepository.findPlannedIncomeDates(eq(TODAY), any(), anyBoolean(), any()))
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
    @DisplayName("ANO-35: есть категории «основной доход» — горизонт считается только по ним")
    void horizonUsesOnlyPrimaryIncome_whenFlagged() {
        when(categoryRepository.existsByPrimaryIncomeTrueAndDeletedFalse()).thenReturn(true);
        assembler.build(new PocketScope(PocketScope.Type.NEXT_INCOME, null, null), TODAY);

        org.mockito.Mockito.verify(eventRepository).findPlannedIncomeDates(
                eq(TODAY), eq(TODAY.plusDays(92)), eq(true), any());
    }

    @Test
    @DisplayName("ANO-35: ни одной размеченной категории — прежнее поведение (любой доход)")
    void horizonUsesAnyIncome_whenNothingFlagged() {
        when(categoryRepository.existsByPrimaryIncomeTrueAndDeletedFalse()).thenReturn(false);
        assembler.build(new PocketScope(PocketScope.Type.NEXT_INCOME, null, null), TODAY);

        org.mockito.Mockito.verify(eventRepository).findPlannedIncomeDates(
                eq(TODAY), eq(TODAY.plusDays(92)), eq(false), any());
    }

    @Test
    @DisplayName("ANO-35: флаг стоит, но плановых доходов по нему нет — откат на любой доход")
    void horizonFallsBackToAnyIncome_whenPrimaryYieldsNothing() {
        when(categoryRepository.existsByPrimaryIncomeTrueAndDeletedFalse()).thenReturn(true);
        LocalDate any = LocalDate.of(2026, 3, 20);
        when(eventRepository.findPlannedIncomeDates(eq(TODAY), any(), eq(true), any()))
                .thenReturn(List.of());                 // по размеченным — пусто
        when(eventRepository.findPlannedIncomeDates(eq(TODAY), any(), eq(false), any()))
                .thenReturn(List.of(any));              // но доходы вообще есть

        var a = assembler.build(new PocketScope(PocketScope.Type.NEXT_INCOME, null, null), TODAY);

        // Горизонт заякорен реальным доходом, а не 30-дневным фолбэком с ложной подписью
        assertThat(a.input().horizonEnd()).isEqualTo(any);
        assertThat(a.input().fallbackKind())
                .isEqualTo(ru.selfin.backend.dto.pocket.FallbackKind.NONE);
    }

    @Test
    @DisplayName("ANO-35: даты доходов уезжают в Assembled — примерка кладёт взносы теми же днями")
    void incomeDates_exposedForSandbox() {
        LocalDate salary = LocalDate.of(2026, 4, 15);
        when(eventRepository.findPlannedIncomeDates(eq(TODAY), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(salary));

        var a = assembler.build(MONTHS_6, TODAY);

        assertThat(a.incomeDates()).containsExactly(salary);
    }

    @Test
    @DisplayName("ANO-28: просрочка запрашивается строго ПОСЛЕ даты якоря дефолтного счёта (якорь её съел)")
    void overdue_queriedAfterCheckpointDate() {
        LocalDate cpDate = LocalDate.of(2026, 2, 10);
        ru.selfin.backend.model.Account defaultAccount = AccountFixtures.defaultAccount();
        ru.selfin.backend.model.BalanceCheckpoint cp = ru.selfin.backend.model.BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(cpDate).amount(BigDecimal.valueOf(5000))
                .account(defaultAccount).build();
        when(accountBalanceService.defaultAccount()).thenReturn(Optional.of(defaultAccount));
        when(accountBalanceService.anchorAt(defaultAccount, TODAY)).thenReturn(Optional.of(cp));

        assembler.build(MONTHS_6, TODAY);

        org.mockito.Mockito.verify(eventRepository)
                .findOverdueMandatoryExpenses(eq(cpDate), eq(TODAY));
    }

    @Test
    @DisplayName("ANO-9: снимок счетов исключает id ДЕФОЛТНОГО счёта, чей чекпоинт стал якорём currentBalance "
            + "(якорь теперь ищется account-scoped через accountBalanceService.anchorAt, не глобально)")
    void accountsSnapshot_excludesDefaultAccountId() {
        LocalDate cpDate = LocalDate.of(2026, 2, 10);
        ru.selfin.backend.model.Account defaultAccount = AccountFixtures.defaultAccount();
        ru.selfin.backend.model.BalanceCheckpoint cp = ru.selfin.backend.model.BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(cpDate).amount(BigDecimal.valueOf(5000))
                .account(defaultAccount).build();
        when(accountBalanceService.defaultAccount()).thenReturn(Optional.of(defaultAccount));
        when(accountBalanceService.anchorAt(defaultAccount, TODAY)).thenReturn(Optional.of(cp));

        assembler.build(MONTHS_6, TODAY);

        org.mockito.Mockito.verify(accountBalanceService).snapshot(eq(TODAY), eq(defaultAccount.getId()));
    }

    @Test
    @DisplayName("ANO-28: без якоря просрочка запрашивается с начала времён")
    void overdue_noCheckpoint_queriedFromEpoch() {
        assembler.build(MONTHS_6, TODAY);
        org.mockito.Mockito.verify(eventRepository)
                .findOverdueMandatoryExpenses(eq(LocalDate.of(2000, 1, 1)), eq(TODAY));
    }

    @Test
    @DisplayName("ANO-9 Task 2.2: без чекпоинта вообще снимок счетов не исключает никого (null)")
    void accountsSnapshot_noCheckpoint_excludesNobody() {
        assembler.build(MONTHS_6, TODAY);
        org.mockito.Mockito.verify(accountBalanceService)
                .snapshot(eq(TODAY), org.mockito.ArgumentMatchers.isNull());
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
