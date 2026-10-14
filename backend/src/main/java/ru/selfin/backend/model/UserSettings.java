package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Простое key/value-хранилище настроек (single-user).
 * {@code settingsValue} — произвольный JSON, маппится как String; парсинг в сервисе.
 */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // Уникален в пределах владельца — держит база (uq_user_settings_owner_key, V31).
    @Column(name = "settings_key", nullable = false, length = 64)
    private String settingsKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_value", nullable = false, columnDefinition = "jsonb")
    private String settingsValue;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
