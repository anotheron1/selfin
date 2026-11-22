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
 * @param parentEventId у факта — план, который он гасит (ANO-155). Движку нужно, чтобы
 *                      считать непогашенный остаток плана: факт не замещает обязательство,
 *                      а уменьшает его на свою сумму. {@code null} у планов и у синтетики.
 * @param onCredit      расход «в кредит» (срез 4, п. 1): остаток основной он не двигает
 * @param toFundOnAccount перевод в копилку, лежавшую в день записи на своём счёте (срез 4, п. 6): деньги ушли с
 *                      основной. Считает сборщик по истории привязок — {@code FundLinks#leavesCard}
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
        LocalDateTime createdAt,
        UUID parentEventId,
        boolean onCredit,
        boolean toFundOnAccount
) {
    /** Сигнатура до среза 4 — запись не в кредит и не перевод на счёт копилки. */
    public EventSnapshot(UUID id, LocalDate date, EventType type, EventKind eventKind,
                         EventStatus status, Priority priority, BigDecimal plannedAmount,
                         BigDecimal factAmount, WishlistStatus wishlistStatus,
                         boolean converted, String description, SyntheticKind syntheticKind,
                         LocalDateTime createdAt, UUID parentEventId) {
        this(id, date, type, eventKind, status, priority, plannedAmount, factAmount,
                wishlistStatus, converted, description, syntheticKind, createdAt, parentEventId, false, false);
    }

    /** Сигнатура до ANO-155 — у таких вызовов родителя нет. */
    public EventSnapshot(UUID id, LocalDate date, EventType type, EventKind eventKind,
                         EventStatus status, Priority priority, BigDecimal plannedAmount,
                         BigDecimal factAmount, WishlistStatus wishlistStatus,
                         boolean converted, String description, SyntheticKind syntheticKind,
                         LocalDateTime createdAt) {
        this(id, date, type, eventKind, status, priority, plannedAmount, factAmount,
                wishlistStatus, converted, description, syntheticKind, createdAt, null);
    }

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
        return from(e, e.getPlannedAmount());
    }

    /** Событие с другой суммой плана — просроченная бронь своим непогашенным остатком (срез 3 п. 3). */
    public static EventSnapshot from(FinancialEvent e, BigDecimal plannedAmount) {
        return from(e, plannedAmount, false);
    }

    /** Событие для шага 1 движка: перевод знает, ушли ли его деньги с основной (срез 4, п. 6). */
    public static EventSnapshot from(FinancialEvent e, boolean toFundOnAccount) {
        return from(e, e.getPlannedAmount(), toFundOnAccount);
    }

    private static EventSnapshot from(FinancialEvent e, BigDecimal plannedAmount, boolean toFundOnAccount) {
        return new EventSnapshot(
                e.getId(), e.getDate(), e.getType(), e.getEventKind(), e.getStatus(),
                e.getPriority(), plannedAmount, e.getFactAmount(), e.getWishlistStatus(),
                e.convertedToArtifact(),
                e.getDescription(), null, e.getCreatedAt(), e.getParentEventId(),
                e.isOnCredit(), toFundOnAccount);
    }
}
