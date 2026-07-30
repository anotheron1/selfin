package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Priority priority = Priority.MEDIUM;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private boolean system = false;

    @Column(name = "forecast_enabled", nullable = false)
    @Builder.Default
    private boolean forecastEnabled = false;

    /**
     * «Основной доход» — приход по этой категории считается настоящим следующим доходом
     * и задаёт горизонт кармашка (зарплата, аванс). Мелкие нерегулярные приходы
     * (возврат долга, кэшбек) флага не имеют и горизонт не смещают (ANO-35).
     *
     * <p>Пока флага нет НИ У ОДНОЙ категории, горизонт берётся по любому доходу —
     * прежнее поведение.
     */
    @Column(name = "primary_income", nullable = false)
    @Builder.Default
    private boolean primaryIncome = false;
}
