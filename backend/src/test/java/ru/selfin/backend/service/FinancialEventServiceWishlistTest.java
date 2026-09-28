package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import ru.selfin.backend.dto.wishlist.EventWishlistParamsDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.WishlistStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// NOTE: check FinancialEventService constructor signature before writing this test.
// Pass mocks in the same order as the actual constructor parameters, with Clock last.
@ExtendWith(MockitoExtension.class)
class FinancialEventServiceWishlistTest {

    @Mock FinancialEventRepository eventRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TargetFundRepository targetFundRepository;
    @Mock CategoryService categoryService;
    @Mock RecurringRuleService ruleService;

    // Clock fixed to 2026-04-09
    Clock clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void findWishlist_usesFirstDayOfMonth_notToday() {
        // Inject clock via constructor (after implementation step)
        FinancialEventService service = new FinancialEventService(
                eventRepository, categoryRepository, targetFundRepository, categoryService, clock, ruleService, mock(WishlistArtifactService.class));

        when(eventRepository.findWishlistItems(
                Priority.LOW, EventStatus.PLANNED,
                LocalDate.of(2026, 4, 1))) // first day of April, not April 9
                .thenReturn(List.of());

        service.findWishlist();

        verify(eventRepository).findWishlistItems(
                Priority.LOW, EventStatus.PLANNED,
                LocalDate.of(2026, 4, 1));
    }

    // ====== ANO-162: «Что с капиталом» пишет только параметры примерки ======

    private FinancialEventService service() {
        return new FinancialEventService(eventRepository, categoryRepository, targetFundRepository,
                categoryService, clock, ruleService, mock(WishlistArtifactService.class));
    }

    /** Хотелка из переноса V18: описание и исходный текст быстрого ввода, своя категория. */
    private static FinancialEvent wishlistRow(LocalDate date) {
        Category cat = Category.builder().id(UUID.randomUUID()).name("Хотелки")
                .type(CategoryType.EXPENSE).priority(Priority.MEDIUM).build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("1212"))
                .description("Велокресло").rawInput("Велокресло детское").url("https://example.test/item")
                .status(EventStatus.PLANNED).priority(Priority.LOW).wishlistStatus(WishlistStatus.OPEN)
                .date(date).build();
    }

    @Test
    void applyWishlistParams_writesAmountAndDate_leavesEverythingElse() {
        FinancialEvent row = wishlistRow(LocalDate.of(2026, 11, 15));
        Category cat = row.getCategory();
        when(eventRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().applyWishlistParams(row.getId(),
                new EventWishlistParamsDto(new BigDecimal("57000"), LocalDate.of(2026, 12, 1)));

        assertThat(row.getPlannedAmount()).isEqualByComparingTo("57000");
        assertThat(row.getDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(row.getDescription()).isEqualTo("Велокресло");
        assertThat(row.getRawInput()).isEqualTo("Велокресло детское");
        assertThat(row.getUrl()).isEqualTo("https://example.test/item");
        assertThat(row.getCategory()).isSameAs(cat);
        assertThat(row.getPriority()).isEqualTo(Priority.LOW);
        assertThat(row.getWishlistStatus()).as("статус пишет не эта запись").isEqualTo(WishlistStatus.OPEN);
        verify(eventRepository).save(row);
    }

    @Test
    void applyWishlistParams_withoutDate_keepsDate() {
        // Срок из примерки не пришёл — у хотелки он прежний, в том числе пустой (ANO-29).
        FinancialEvent row = wishlistRow(null);
        when(eventRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().applyWishlistParams(row.getId(), new EventWishlistParamsDto(new BigDecimal("618800"), null));

        assertThat(row.getPlannedAmount()).isEqualByComparingTo("618800");
        assertThat(row.getDate()).isNull();
    }

    @Test
    void applyWishlistParams_notAWishlistItem_404() {
        FinancialEvent row = wishlistRow(LocalDate.of(2026, 11, 15));
        row.setWishlistStatus(null);
        when(eventRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service().applyWishlistParams(row.getId(),
                new EventWishlistParamsDto(BigDecimal.TEN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventRepository, never()).save(any());
    }
}
