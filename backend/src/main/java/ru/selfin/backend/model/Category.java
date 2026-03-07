package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.CategoryType;

import java.util.UUID;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryType type;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean mandatory = false;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
