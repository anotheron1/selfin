package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.TargetFund;

import java.util.List;
import java.util.UUID;

public interface TargetFundRepository extends JpaRepository<TargetFund, UUID> {
    List<TargetFund> findAllByDeletedFalseOrderByPriorityAsc();
}
