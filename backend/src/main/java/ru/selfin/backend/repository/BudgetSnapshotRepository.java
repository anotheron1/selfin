package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.BudgetSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BudgetSnapshotRepository extends JpaRepository<BudgetSnapshot, UUID> {
    List<BudgetSnapshot> findAllByPeriodStartGreaterThanEqualOrderBySnapshotDateDesc(LocalDate date);
}
