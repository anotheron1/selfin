package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Финансовое событие — центральная сущность плана-факт модели.
 * Каждое событие описывает запланированный или совершённый платёж/поступление.
 *
 * <p>Жизненный цикл статуса:
 * <ul>
 *   <li>{@code PLANNED} — только план, факт не введён</li>
 *   <li>{@code EXECUTED} — введён {@code factAmount}, событие исполнено</li>
 *   <li>{@code CANCELLED} — отменено без исполнения</li>
 * </ul>
 *
 * <p>Физически не удаляется: {@code deleted = true} скрывает запись из запросов.
 */
@Entity
@Table(name = "financial_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialEvent implements FinancialRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Idempotency key от клиента (UUID). Гарантирует, что одна операция создания
     * не продублируется при повторных запросах (потеря связи, ретраи).
     */
    @Column(name = "idempotency_key", unique = true)
    private UUID idempotencyKey;

    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(name = "planned_amount", precision = 19, scale = 2)
    private BigDecimal plannedAmount;

    @Column(name = "fact_amount", precision = 19, scale = 2)
    private BigDecimal factAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EventStatus status = EventStatus.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Priority priority = Priority.MEDIUM;

    private String description;

    /** Оригинальный текст пользователя — датасет для будущего AI-парсера */
    @Column(name = "raw_input", columnDefinition = "TEXT")
    private String rawInput;

    @Column(length = 2048)
    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** FK to target_fund. Populated only when type = FUND_TRANSFER. */
    @Column(name = "target_fund_id")
    private UUID targetFundId;

    /**
     * Если событие порождено повторяющимся правилом — ссылка на правило.
     * NULL для одиночных событий. См. spec, инварианты I5, I9.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_rule_id")
    private RecurringRule recurringRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false)
    @Builder.Default
    private EventKind eventKind = EventKind.PLAN;

    @Column(name = "parent_event_id")
    private UUID parentEventId;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /**
     * Статус item'а в модуле /wishlist. NULL — обычное событие, не хотелка.
     * Не-NULL допустим только при priority=LOW (DB constraint chk_wishlist_status_only_low).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "wishlist_status", length = 16)
    private ru.selfin.backend.model.enums.WishlistStatus wishlistStatus;

    /** Если хотелка сконвертирована в PLAN-событие — ссылка на него. Только при FIXED. */
    @Column(name = "converted_to_event_id")
    private UUID convertedToEventId;

    /** Если хотелка сконвертирована в копилку/кредит — ссылка на TargetFund. Только при FIXED. */
    @Column(name = "converted_to_fund_id")
    private UUID convertedToFundId;

    /**
     * Хотелка превращена в план или копилку: её деньги несёт созданное (ANO-106). Сама она —
     * уже не событие периода и не трата: так её видят и расчёт свободных
     * ({@code EventSnapshot.converted}), и журнал ({@code FinancialEventService.findByPeriod}).
     *
     * <p>Имя без {@code is}/{@code get}: это правило, а не поле, и сериализаторы его не подхватят.
     */
    public boolean convertedToArtifact() {
        return convertedToEventId != null || convertedToFundId != null;
    }

    /**
     * Хотелка вне плана ядра: обсуждается, отложена или сконвертирована. Тратой хотелка становится,
     * только когда зафиксирована и не сконвертирована (ANO-108) — то же правило, что
     * {@code PocketEngine.allowedInTrajectory} на снимках и запрос {@code findPlannedEventsByDateRange}.
     */
    public boolean wishlistOutsidePlan() {
        return wishlistStatus != null
                && (wishlistStatus != ru.selfin.backend.model.enums.WishlistStatus.FIXED || convertedToArtifact());
    }

    /**
     * Строки нет в плане месяца ни на одном экране (Р4, ANO-206): хотелка вне плана ядра и без денег
     * в самой строке. Факт, записанный прямо в строку старым путём, — настоящая трата: такую строку
     * не прячем, иначе из журнала пропали бы деньги (ANO-106, ревью Codex на #109).
     */
    public boolean outsideMonthPlan() {
        return wishlistOutsidePlan() && factAmount == null;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
