package ru.selfin.backend.model;

import ru.selfin.backend.model.enums.EventType;

import java.time.LocalDate;
import java.util.UUID;

/** Common read contract for both PLAN and FACT records. */
public interface Transaction {
    UUID getId();
    LocalDate getDate();
    Category getCategory();
    EventType getType();
    String getDescription();
    EventKind getEventKind();
}
