package ru.selfin.backend.dto.pocket;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-106: расчёт свободных и журнал узнают сконвертированную хотелку одним правилом —
 * {@code FinancialEvent.convertedToArtifact()}. Здесь сторожится сторона расчёта: без неё
 * подмена флага на {@code false} проходила все юниты (мутация MB5).
 */
class EventSnapshotTest {

    private static FinancialEvent wishlist() {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).eventKind(EventKind.PLAN).type(EventType.EXPENSE)
                .status(EventStatus.PLANNED).priority(Priority.LOW).wishlistStatus(WishlistStatus.FIXED)
                .plannedAmount(new BigDecimal("1063")).date(LocalDate.of(2026, 9, 30))
                .category(Category.builder().id(UUID.randomUUID()).name("Хотелки").build())
                .build();
    }

    @Test
    void from_wishlistConvertedToPlan_isConverted() {
        FinancialEvent e = wishlist();
        e.setConvertedToEventId(UUID.randomUUID());
        assertThat(EventSnapshot.from(e).converted()).isTrue();
    }

    @Test
    void from_wishlistConvertedToFund_isConverted() {
        FinancialEvent e = wishlist();
        e.setConvertedToFundId(UUID.randomUUID());
        assertThat(EventSnapshot.from(e).converted()).isTrue();
    }

    @Test
    void from_fixedWishlistWithoutArtifact_isNotConverted() {
        assertThat(EventSnapshot.from(wishlist()).converted()).isFalse();
    }
}
