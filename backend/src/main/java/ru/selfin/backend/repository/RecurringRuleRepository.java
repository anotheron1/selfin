package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.RecurringRule;

import java.util.UUID;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, UUID> {
}
