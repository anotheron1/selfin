package ru.selfin.backend.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Spring Data projection for fact aggregates per parent plan. */
public interface FactAggregateProjection {
    UUID getParentEventId();
    Long getCount();
    BigDecimal getTotalAmount();
}
