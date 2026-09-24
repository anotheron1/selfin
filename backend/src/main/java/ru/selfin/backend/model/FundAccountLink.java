package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Период, когда копилка жила на счёте (ANO-163).
 *
 * <p>Привязка к счёту меняет смысл денег копилки: у конверта они отложены и складываются в
 * капитал отдельно, у цели на счёте — лежат в остатке счёта (спека счетов §3.3). Капитал за
 * прошлую дату обязан знать, каким копилка была В ТОТ ДЕНЬ, а {@link TargetFund#getAccountId()}
 * знает только сегодняшнее. Отсюда история.
 *
 * <p>Единица — день. День привязки — день на счёте, день отвязки — день конверта. Пишет её
 * только {@code TargetFundService}: это единственное место, где меняется счёт копилки.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-24-fund-link-history-design.md}.
 */
@Entity
@Table(name = "fund_account_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundAccountLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fund_id", nullable = false)
    private UUID fundId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Первый день на счёте. */
    @Column(name = "linked_from", nullable = false)
    private LocalDate linkedFrom;

    /** Первый день снова конвертом; {@code null} — копилка на счёте и сейчас. */
    @Column(name = "linked_to")
    private LocalDate linkedTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
