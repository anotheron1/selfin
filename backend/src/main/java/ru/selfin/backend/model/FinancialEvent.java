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
public class FinancialEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Idempotency key от клиента (UUID). Гарантирует, что одна операция создания
     * не продублируется при повторных запросах (потеря связи, ретраи).
     */
    @Column(name = "idempotency_key", unique = true)
    private UUID idempotencyKey;

    @Column(nullable = false)
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

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** FK to target_fund. Populated only when type = FUND_TRANSFER. */
    @Column(name = "target_fund_id")
    private UUID targetFundId;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
