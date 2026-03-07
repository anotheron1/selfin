package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "fund_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fund_id", nullable = false)
    private TargetFund fund;

    /** Может быть null для ручных переводов напрямую из кармашка */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_event_id")
    private FinancialEvent sourceEvent;

    @Column(name = "idempotency_key", unique = true)
    private UUID idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
