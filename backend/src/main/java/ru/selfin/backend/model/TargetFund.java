package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.FundStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "target_funds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TargetFund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** null = без ограничения (Кармашек) */
    @Column(name = "target_amount", precision = 19, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "current_balance", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FundStatus status = FundStatus.FUNDING;

    /** Порядок забора профицита из кармашка (меньше = выше приоритет) */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Желаемая дата достижения цели (задаётся пользователем, null = не указана) */
    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
