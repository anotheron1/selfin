package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Счёт — место, где лежат деньги, с выбираемой пользователем поведенческой моделью
 * (спека docs/superpowers/specs/2026-08-12-accounts-skeleton-design.md §3.1).
 *
 * <p>Четыре оси: природа {@code kind}; слежение за остатком {@code trackBalance};
 * зона ответственности {@code purposeCategory}; кредитные {@code creditLimit} и
 * {@code availableFloor} (планка возврата).
 *
 * <p><b>ВАЖНО.</b> У счёта с {@code kind = CREDIT} чекпоинт хранит ДОСТУПНЫЙ ОСТАТОК,
 * а не долг. Долг вычисляется как {@code creditLimit − доступно}. Так мыслит пользователь
 * (принцип №4 подхода) — не «переворачивать обратно» при реализации.
 *
 * <p>{@code defaultAccount} — счёт-приёмник безадресных операций. Транзакции адреса не
 * имеют осознанно (§5.1), поэтому ровно один счёт объявлен приёмником. Это же место,
 * куда позже воткнётся импорт (ANO-10).
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountKind kind;

    /** Слежу ли за остатком. Главный тумблер: решает и участие в свободных деньгах,
     *  и смысл пополнения конверта (§5.2). */
    @Column(name = "track_balance", nullable = false)
    private boolean trackBalance;

    /** Зона ответственности: мягкая, переназначаемая (бензин → авто → транспорт). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_category_id")
    private Category purposeCategory;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    /** Планка возврата: уровень доступного, к которому возвращаешься. null — не задана,
     *  второе число кармашка не показывается (§3.1). */
    @Column(name = "available_floor", precision = 19, scale = 2)
    private BigDecimal availableFloor;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean defaultAccount = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 100;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Даёт ли счёт свободные деньги (спека §4.1). За чем не следишь — того не считаешь. */
    public boolean countsAsFreeMoney() {
        return !deleted && trackBalance && (kind == AccountKind.DEBIT || kind == AccountKind.CASH);
    }

    /** Полу-ликвид: входит в капитал, но не в свободные деньги (§4.3). */
    public boolean isSemiLiquid() {
        return !deleted && trackBalance && kind == AccountKind.DEPOSIT;
    }

    /**
     * Равенство по {@code id}, устойчивое к Hibernate-прокси (Task 2.1 начинает сравнивать
     * счета и складывать их в коллекции — экземпляры одного счёта, загруженные разными
     * запросами, иначе сравнивались бы по ссылке и тихо давали неверный результат вместо
     * ошибки). Сущность с ещё не сгенерированным {@code id} равна только самой себе.
     *
     * <p>Сознательное отступление от конвенции проекта: остальные сущности {@code equals}
     * не переопределяют, потому что их и не сравнивают в расчётах.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> thisClass = org.hibernate.Hibernate.getClass(this);
        if (thisClass != org.hibernate.Hibernate.getClass(o)) return false;
        Account other = (Account) o;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return org.hibernate.Hibernate.getClass(this).hashCode();
    }
}
