package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Запись в журнале переоценок единицы капитала.
 * <p>Стоимость item'а на дату {@code t} = последняя живая переоценка с {@code valued_at ≤ t}
 * (порядок tie-break: {@code valued_at DESC, created_at DESC}). Если ни одной такой записи нет —
 * вклад item'а в капитал = 0 (item «не существовал» до своей первой переоценки).
 *
 * <p>{@code value ≥ 0} всегда: знак применяется в формуле капитала по {@code item.kind}.
 *
 * <p>Soft-delete нужен для «удалить ошибочную запись» — без потери остального журнала.
 */
@Entity
@Table(name = "capital_revaluations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapitalRevaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal value;

    @Column(name = "valued_at", nullable = false)
    private LocalDate valuedAt;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
