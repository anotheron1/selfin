package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    /** Filled only when eventType = FUND_TRANSFER */
    @Column(name = "target_fund_id")
    private UUID targetFundId;

    @Column(name = "planned_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal plannedAmount;

    @Column(name = "mandatory", nullable = false)
    @Builder.Default
    private boolean mandatory = false;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringFrequency frequency;

    /** Day of month (1–28). Used when frequency = MONTHLY. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** Day of week. Used when frequency = WEEKLY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week")
    private DayOfWeek dayOfWeek;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Null means "forever". */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
