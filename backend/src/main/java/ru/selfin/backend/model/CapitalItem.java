package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Единица капитала — актив или обязательство.
 * <p>Симметричная сущность: дискриминатор {@code kind} различает активы (вклад в капитал со знаком +)
 * и обязательства (со знаком −). История стоимости — в {@link CapitalRevaluation}.
 *
 * <p>Soft-delete: {@code deleted = true} (колонка {@code is_deleted}) скрывает item из всех расчётов
 * целиком — это «удалить безвозвратно, ошибся при создании». Для «продал актив / закрыл кредит»
 * добавляется переоценка с {@code value=0}, item остаётся, но уходит в архивные.
 */
@Entity
@Table(name = "capital_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapitalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CapitalItemKind kind;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

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
}
