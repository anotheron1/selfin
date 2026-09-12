package ru.selfin.backend.dto.pocket;

import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Плоский снапшот события для чистого движка (без JPA-прокси и lazy-полей).
 *
 * @param syntheticKind null для реальных событий из БД; задан у синтетики
 *                      (взносы копилок §6, примерка ANO-16) — у неё {@code id == null}
 * @param createdAt     когда событие ЗАПИСАНО (ANO-82). Движку нужно, чтобы отличить факт
 *                      дня якоря, существовавший в момент сверки, от записанного после неё —
 *                      см. {@code AnchorWindow}. {@code null} у синтетики и у старых вызовов:
 *                      тогда день якоря решается по дате, как до ANO-82.
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
        SyntheticKind syntheticKind,
        LocalDateTime createdAt
) {
    /** Старая сигнатура (реальное событие, syntheticKind = null) — щадит существующие тесты. */
    public EventSnapshot(UUID id, LocalDate date, EventType type, EventKind eventKind,
                         EventStatus status, Priority priority, BigDecimal plannedAmount,
                         BigDecimal factAmount, WishlistStatus wishlistStatus,
                         boolean converted, String description) {
        this(id, date, type, eventKind, status, priority, plannedAmount, factAmount,
                wishlistStatus, converted, description, null, null);
    }

    /** Сигнатура до ANO-82 (синтетика и примерка) — времени записи у таких событий нет. */
    public EventSnapshot(UUID id, LocalDate date, EventType type, EventKind eventKind,
                         EventStatus status, Priority priority, BigDecimal plannedAmount,
                         BigDecimal factAmount, WishlistStatus wishlistStatus,
                         boolean converted, String description, SyntheticKind syntheticKind) {
        this(id, date, type, eventKind, status, priority, plannedAmount, factAmount,
                wishlistStatus, converted, description, syntheticKind, null);
    }

    public static EventSnapshot from(FinancialEvent e) {
        return new EventSnapshot(
                e.getId(), e.getDate(), e.getType(), e.getEventKind(), e.getStatus(),
                e.getPriority(), e.getPlannedAmount(), e.getFactAmount(), e.getWishlistStatus(),
                e.getConvertedToEventId() != null || e.getConvertedToFundId() != null,
                e.getDescription(), null, e.getCreatedAt());
    }
}
