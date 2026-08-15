package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.wishlist.ConvertWishlistRequestDto;
import ru.selfin.backend.dto.wishlist.SandboxFixRequestDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WishlistConversionServiceTest {

    private final FinancialEventRepository eventRepo = mock(FinancialEventRepository.class);
    private final TargetFundRepository fundRepo = mock(TargetFundRepository.class);
    private final RecurringRuleService recurringRuleService = mock(RecurringRuleService.class);
    private final CategoryRepository categoryRepo = mock(CategoryRepository.class);
    /** Настоящий сервис поверх моков: правило «сколько уже накоплено» (ANO-9 §3.3) в fixFund
     *  проверяемое, а не подменённое — именно его подмена молча усыхала цель. */
    private final ru.selfin.backend.repository.AccountRepository accountRepo =
            mock(ru.selfin.backend.repository.AccountRepository.class);
    private final ru.selfin.backend.repository.BalanceCheckpointRepository checkpointRepo =
            mock(ru.selfin.backend.repository.BalanceCheckpointRepository.class);
    private final WishlistConversionService service =
            new WishlistConversionService(eventRepo, fundRepo, recurringRuleService, categoryRepo,
                    new AccountBalanceService(accountRepo, checkpointRepo, eventRepo));

    private FinancialEvent openWishlist(UUID id) {
        Category cat = Category.builder().id(UUID.randomUUID()).name("Прочее").build();
        return FinancialEvent.builder().id(id).priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("150000"))
                .date(LocalDate.now().plusMonths(6)).description("Ноут").build();
    }

    @Test
    void convert_wishlistToPlanEvent_createsEventAndFixesSource() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        when(eventRepo.save(any())).thenAnswer(i -> {
            FinancialEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        var resp = service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false));

        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(src.getConvertedToEventId()).isNotNull();
        assertThat(resp.convertedTo().kind()).isEqualTo("EVENT");
        // verify a new PLAN event (eventKind=PLAN, wishlistStatus=null) was saved
        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(e -> {
            assertThat(e.getEventKind()).isEqualTo(EventKind.PLAN);
            assertThat(e.getWishlistStatus()).isNull();
        });
    }

    @Test
    void convert_wishlistToFund_fundTargetDateOverridesSourceDate() {
        // ANO-16 §8: фиксация растянутой примерки передаёт последний день месяца
        // последнего взноса — дата цели копилки берётся из запроса, не из хотелки
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);   // дата хотелки = +6 месяцев
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        when(fundRepo.save(any())).thenAnswer(i -> {
            TargetFund f = i.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });
        when(eventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        LocalDate override = LocalDate.now().plusMonths(3).withDayOfMonth(
                LocalDate.now().plusMonths(3).lengthOfMonth());
        service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "FUND", false, override));

        ArgumentCaptor<TargetFund> cap = ArgumentCaptor.forClass(TargetFund.class);
        verify(fundRepo).save(cap.capture());
        assertThat(cap.getValue().getTargetDate()).isEqualTo(override);
        assertThat(cap.getValue().getTargetAmount()).isEqualByComparingTo("150000");

        // Без переопределения — прежнее поведение: дата источника
        UUID id2 = UUID.randomUUID();
        FinancialEvent src2 = openWishlist(id2);
        when(eventRepo.findById(id2)).thenReturn(Optional.of(src2));
        service.convertItem(id2, new ConvertWishlistRequestDto("WISHLIST", "FUND", false));
        ArgumentCaptor<TargetFund> cap2 = ArgumentCaptor.forClass(TargetFund.class);
        verify(fundRepo, atLeast(2)).save(cap2.capture());
        assertThat(cap2.getAllValues().get(cap2.getAllValues().size() - 1).getTargetDate())
                .isEqualTo(src2.getDate());
    }

    @Test
    void convert_fundToFund_fundTargetDateOverridesToo() {
        // Зеркальный кейс: fund-source ветка тоже уважает переопределение даты цели
        UUID id = UUID.randomUUID();
        TargetFund src = TargetFund.builder()
                .id(id).name("Горнолыжка")
                .targetAmount(new BigDecimal("80000"))
                .targetDate(LocalDate.now().plusMonths(6))
                .purchaseType(FundPurchaseType.SAVINGS)
                .wishlistStatus(WishlistStatus.OPEN)
                .build();
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        when(fundRepo.save(any())).thenAnswer(i -> {
            TargetFund f = i.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        LocalDate override = LocalDate.now().plusMonths(2);
        service.convertItem(id,
                new ConvertWishlistRequestDto("SAVINGS", "FUND", false, override));

        ArgumentCaptor<TargetFund> cap = ArgumentCaptor.forClass(TargetFund.class);
        verify(fundRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(f ->
                assertThat(f.getTargetDate()).isEqualTo(override));
    }

    @Test
    void convert_alreadyConverted_throws409() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        src.setWishlistStatus(WishlistStatus.FIXED);
        src.setConvertedToEventId(UUID.randomUUID());   // already converted
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void convert_creditWithRecurring_createsFundAndRule() {
        UUID id = UUID.randomUUID();
        TargetFund src = TargetFund.builder().id(id).name("Машина")
                .purchaseType(FundPurchaseType.CREDIT).wishlistStatus(WishlistStatus.OPEN)
                .targetAmount(new BigDecimal("2000000")).targetDate(LocalDate.now().plusMonths(2))
                .creditRate(new BigDecimal("16.5")).creditTermMonths(60).build();
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        when(fundRepo.save(any())).thenAnswer(i -> {
            TargetFund f = i.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });
        UUID ruleId = UUID.randomUUID();
        // RecurringRuleService.createFromDto returns a CreateResult carrying the rule;
        // mock to return a rule with ruleId. Match the actual return type.
        when(recurringRuleService.createFromDto(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RecurringRuleService.CreateResult(
                        RecurringRule.builder().id(ruleId).build(), java.util.List.of()));

        var resp = service.convertItem(id,
                new ConvertWishlistRequestDto("CREDIT", "FUND_WITH_CREDIT", true));

        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(src.getConvertedToFundId()).isNotNull();
        assertThat(resp.recurringRuleId()).isEqualTo(ruleId);
        verify(recurringRuleService).createFromDto(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void convert_creditWithCredit_missingRateOrTerm_throws400() {
        UUID id = UUID.randomUUID();
        // CREDIT fund with null creditTermMonths → degenerate; conversion must be rejected.
        TargetFund src = TargetFund.builder().id(id).name("Машина")
                .purchaseType(FundPurchaseType.CREDIT).wishlistStatus(WishlistStatus.OPEN)
                .targetAmount(new BigDecimal("2000000")).targetDate(LocalDate.now().plusMonths(2))
                .creditRate(new BigDecimal("16.5")).creditTermMonths(null).build();
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("CREDIT", "FUND_WITH_CREDIT", true)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(400));

        // All-or-nothing: nothing saved, source untouched (still OPEN, no conversion link).
        verify(fundRepo, never()).save(any());
        verify(recurringRuleService, never())
                .createFromDto(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.OPEN);
        assertThat(src.getConvertedToFundId()).isNull();
    }

    @Test
    void convert_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(eventRepo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ====== ANO-34 §1: фиксация переносит параметры примерки ======

    /**
     * «Сегодня» зафиксировано на 31-е намеренно: раскладка календарно-зависима,
     * и на последнем дне месяца ломались прежние тесты кармашка.
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 31);

    private TargetFund openSavings(UUID id, String target, String balance) {
        return TargetFund.builder()
                .id(id).name("Горнолыжка")
                .targetAmount(new BigDecimal(target))
                .currentBalance(new BigDecimal(balance))
                .targetDate(LocalDate.of(2026, 12, 31))
                .purchaseType(FundPurchaseType.SAVINGS)
                .wishlistStatus(WishlistStatus.OPEN)
                .build();
    }

    private void stubEventSave() {
        when(eventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void stubFundSave() {
        when(fundRepo.save(any())).thenAnswer(i -> {
            TargetFund f = i.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });
    }

    @Test
    void fix_wishlistWithoutStretch_writesTweakedAmountAndDate_keepsItAWishlist() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);       // 150000, +6 месяцев
        Category originalCategory = src.getCategory();
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        stubEventSave();

        LocalDate tweaked = LocalDate.of(2026, 4, 15);
        var resp = service.applyAndFix(id, new SandboxFixRequestDto(
                "WISHLIST", new BigDecimal("175000"), tweaked, 0, null, null), TODAY);

        assertThat(src.getPlannedAmount()).isEqualByComparingTo("175000");
        assertThat(src.getDate()).isEqualTo(tweaked);
        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        // Хотелка обязана остаться LOW-хотелкой своей категории: иначе выпадет
        // из wishlist-выборок и следующий PATCH статуса вернёт 400.
        assertThat(src.getPriority()).isEqualTo(Priority.LOW);
        assertThat(src.getCategory()).isSameAs(originalCategory);
        assertThat(resp.artifactKind()).isEqualTo("EVENT_PARAMS");
        assertThat(resp.convertedTo()).isNull();
        verify(fundRepo, never()).save(any());
    }

    @Test
    void fix_wishlistWithStretch_createsFundWithTweakedAmount_andDateFromSlider() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);       // исходные 150000
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        stubEventSave();
        stubFundSave();

        var resp = service.applyAndFix(id, new SandboxFixRequestDto(
                "WISHLIST", new BigDecimal("175000"), LocalDate.of(2026, 12, 1), 3, null, null), TODAY);

        ArgumentCaptor<TargetFund> cap = ArgumentCaptor.forClass(TargetFund.class);
        verify(fundRepo).save(cap.capture());
        // Раньше сюда уезжала исходная сумма события, а не подкрученная в примерке
        assertThat(cap.getValue().getTargetAmount()).isEqualByComparingTo("175000");
        // Дата цели выводится из ползунка: последний день месяца последнего взноса
        assertThat(cap.getValue().getTargetDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        // Обратимость правила: резерв §6 воспроизведёт ровно ту же растяжку
        assertThat(SandboxLayout.maxStretchMonths(TODAY, cap.getValue().getTargetDate())).isEqualTo(3);
        assertThat(resp.convertedTo().kind()).isEqualTo("FUND");
        assertThat(src.getConvertedToFundId()).isNotNull();
        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
    }

    @Test
    void fix_savings_targetAmountAddsCurrentBalance() {
        // В примерке amount копилки — ОСТАТОК. Накопленные 20000 обязаны уцелеть:
        // «докопить ещё 60000» → цель 80000, а не 60000 (решение Кирилла 2026-07-31).
        UUID id = UUID.randomUUID();
        TargetFund src = openSavings(id, "80000", "20000");
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        stubFundSave();

        service.applyAndFix(id, new SandboxFixRequestDto(
                "SAVINGS", new BigDecimal("60000"), null, 2, null, null), TODAY);

        assertThat(src.getTargetAmount()).isEqualByComparingTo("80000");
        assertThat(src.getCurrentBalance()).isEqualByComparingTo("20000");
        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
    }

    @Test
    void fix_savingsOnAccount_targetAmountAddsAccountBalance_notStoredZero() {
        // ANO-9 §3.3: у копилки НА СЧЕТЕ собственное поле навсегда нулевое (перевод запрещён),
        // а примерка вычла реальный остаток счёта. Если fixFund прибавит сохранённый ноль,
        // цель усохнет на этот остаток при КАЖДОЙ фиксации: 1 000 000 → 700 000 → 400 000.
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        TargetFund src = openSavings(id, "1000000", "0");
        src.setAccountId(accountId);
        ru.selfin.backend.model.Account deposit = ru.selfin.backend.testsupport.AccountFixtures
                .account(ru.selfin.backend.model.enums.AccountKind.DEPOSIT, true)
                .id(accountId).build();
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(deposit));
        when(checkpointRepo.findLatestForAccountAt(accountId, TODAY)).thenReturn(Optional.of(
                ru.selfin.backend.model.BalanceCheckpoint.builder().id(UUID.randomUUID())
                        .date(TODAY.minusDays(5)).amount(new BigDecimal("300000"))
                        .account(deposit).build()));
        stubFundSave();

        service.applyAndFix(id, new SandboxFixRequestDto(
                "SAVINGS", new BigDecimal("700000"), null, 2, null, null), TODAY);

        // 700 000 (докопить) + 300 000 (уже лежит на счёте) = 1 000 000, цель не сдвинулась
        assertThat(src.getTargetAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void fix_savings_sliderBeatsDateField() {
        // Ползунок главнее поля даты: иначе «растянуть на меньше» невыразимо,
        // потому что резерв §6 всегда размазывает остаток ровно до targetDate.
        UUID id = UUID.randomUUID();
        TargetFund src = openSavings(id, "80000", "20000");
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        stubFundSave();

        service.applyAndFix(id, new SandboxFixRequestDto(
                "SAVINGS", new BigDecimal("60000"), LocalDate.of(2026, 11, 20), 2, null, null), TODAY);

        assertThat(src.getTargetDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(SandboxLayout.maxStretchMonths(TODAY, src.getTargetDate())).isEqualTo(2);
    }

    @Test
    void fix_savingsWithoutStretch_usesGivenDate() {
        UUID id = UUID.randomUUID();
        TargetFund src = openSavings(id, "80000", "20000");
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        stubFundSave();

        service.applyAndFix(id, new SandboxFixRequestDto(
                "SAVINGS", new BigDecimal("60000"), LocalDate.of(2026, 11, 20), 0, null, null), TODAY);

        assertThat(src.getTargetDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void fix_creditKeepsRateAndTerm() {
        UUID id = UUID.randomUUID();
        TargetFund src = TargetFund.builder()
                .id(id).name("Машина")
                .purchaseType(FundPurchaseType.CREDIT).wishlistStatus(WishlistStatus.OPEN)
                .targetAmount(new BigDecimal("2000000")).currentBalance(BigDecimal.ZERO)
                .targetDate(LocalDate.of(2026, 6, 30))
                .creditRate(new BigDecimal("16.5")).creditTermMonths(60).build();
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        stubFundSave();

        service.applyAndFix(id, new SandboxFixRequestDto(
                "CREDIT", new BigDecimal("2200000"), LocalDate.of(2026, 7, 31), 0,
                new BigDecimal("18.0"), 48), TODAY);

        assertThat(src.getTargetAmount()).isEqualByComparingTo("2200000");
        assertThat(src.getCreditRate()).isEqualByComparingTo("18.0");
        assertThat(src.getCreditTermMonths()).isEqualTo(48);
        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
    }

    @Test
    void fix_pastOrMissingDateWithoutStretch_throws400_andSavesNothing() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        // Дата в прошлом: событие не попало бы в резерв (date > asOfDate) — тот же
        // невидимый финал, ради которого задача и заведена.
        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "WISHLIST", new BigDecimal("1000"), TODAY.minusDays(1), 0, null, null), TODAY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(400));

        // Сегодняшняя дата тоже не годится: нужен строго будущий день
        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "WISHLIST", new BigDecimal("1000"), TODAY, 0, null, null), TODAY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "WISHLIST", new BigDecimal("1000"), null, 0, null, null), TODAY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        verify(eventRepo, never()).save(any());
        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.OPEN);
    }

    @Test
    void fix_alreadyConverted_throws409() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        src.setConvertedToFundId(UUID.randomUUID());
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "WISHLIST", new BigDecimal("1000"), TODAY.plusMonths(1), 0, null, null), TODAY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void fix_unknownSourceKindOrHugeStretch_throws400() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "NONSENSE", new BigDecimal("1000"), TODAY.plusMonths(1), 0, null, null), TODAY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(400));

        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "SAVINGS", new BigDecimal("1000"), null, 61, null, null), TODAY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        verify(eventRepo, never()).save(any());
        verify(fundRepo, never()).save(any());
    }

    @Test
    void fix_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(fundRepo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyAndFix(id, new SandboxFixRequestDto(
                "SAVINGS", new BigDecimal("1000"), TODAY.plusMonths(1), 0, null, null), TODAY))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
