package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Снимок состояния бюджетного плана на начало месяца.
 * Решает проблему "ползущего бюджета" — позволяет сравнить:
 * Изначальный план vs Текущий план vs Факт.
 */
@Entity
@Table(name = "budget_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "snapshot_date", nullable = false)
    @Builder.Default
    private LocalDateTime snapshotDate = LocalDateTime.now();

    /** JSONB слепок всех запланированных событий в момент создания снимка */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_data", columnDefinition = "jsonb")
    private String snapshotData;
}
