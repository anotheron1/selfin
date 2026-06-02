package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
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

    @Column(name = "settings_key", nullable = false, unique = true, length = 64)
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
