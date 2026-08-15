package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "balance_checkpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Дата, на которую зафиксирован остаток на счёте. */
    @Column(nullable = false)
    private LocalDate date;

    /** Реальный остаток на счёте на указанную дату. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /**
     * Счёт, чей остаток зафиксирован. Для {@code AccountKind.CREDIT} здесь
     * ДОСТУПНЫЙ ОСТАТОК, не долг (спека §3.2).
     *
     * <p><b>Ленивое и обязательное.</b> Чтение {@code getKind()}/{@code isTrackBalance()}
     * и прочих свойств счёта вне транзакции кидает {@code LazyInitializationException}.
     * В Task 2.1 запросы, которым нужен счёт, обязаны брать его через {@code JOIN FETCH}
     * или {@code @EntityGraph}, а не полагаться на ленивую подгрузку. Осторожно с
     * {@code @Modifying(clearAutomatically = true)} — та же ловушка, что и в
     * {@code RecurringRuleService} (строки 96 и 137): такой запрос отсоединяет прокси,
     * и последующее чтение счёта из уже загруженного чекпоинта падает.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
