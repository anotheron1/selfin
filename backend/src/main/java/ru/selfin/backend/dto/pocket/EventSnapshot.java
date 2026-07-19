package ru.selfin.backend.dto.pocket;

import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Плоский снапшот события для чистого движка (без JPA-прокси и lazy-полей).
 *
 * @param syntheticKind null для реальных событий из БД; задан у синтетики
 *                      (взносы копилок §6, примерка ANO-16) — у неё {@code id == null}
 */
public record EventSnapshot(
        UUID id,
        LocalDate date,
        EventType type,
        EventKind eventKind,
        EventStatus status,
        Priority priority,
        BigDecimal plannedAmount,
        BigDecimal factAmount,
        WishlistStatus wishlistStatus,
        boolean converted,
        String description,
        SyntheticKind syntheticKind
) {
    /** Старая сигнатура (реальное событие, syntheticKind = null) — щадит существующие тесты. */
    public EventSnapshot(UUID id, LocalDate date, EventType type, EventKind eventKind,
                         EventStatus status, Priority priority, BigDecimal plannedAmount,
                         BigDecimal factAmount, WishlistStatus wishlistStatus,
                         boolean converted, String description) {
        this(id, date, type, eventKind, status, priority, plannedAmount, factAmount,
                wishlistStatus, converted, description, null);
    }

    public static EventSnapshot from(FinancialEvent e) {
        return new EventSnapshot(
                e.getId(), e.getDate(), e.getType(), e.getEventKind(), e.getStatus(),
                e.getPriority(), e.getPlannedAmount(), e.getFactAmount(), e.getWishlistStatus(),
                e.getConvertedToEventId() != null || e.getConvertedToFundId() != null,
                e.getDescription());
    }
}
