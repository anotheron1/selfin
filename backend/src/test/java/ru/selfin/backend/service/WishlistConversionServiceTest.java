package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.wishlist.ConvertWishlistRequestDto;
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
    private final WishlistConversionService service =
            new WishlistConversionService(eventRepo, fundRepo, recurringRuleService, categoryRepo);

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
}
