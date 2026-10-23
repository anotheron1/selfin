package ru.selfin.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;
import java.util.UUID;

/**
 * День визита человека (ANO-102): строка за день — «открывал», {@code wrote} — в этот день был удачный запрос к
 * ручке, которая пишет данные. Пишет {@code VisitService} нативной вставкой; сущность — для чтения и второго слоя
 * изоляции ({@code @TenantId}).
 *
 * <p>Спека: docs/superpowers/specs/2026-10-19-break-hint-design.md
 */
@Entity
@Table(name = "visit_days")
@Getter
@NoArgsConstructor
public class VisitDay {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private boolean wrote;
}
