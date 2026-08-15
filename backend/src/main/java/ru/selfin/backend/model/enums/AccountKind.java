package ru.selfin.backend.model.enums;

/**
 * Природа счёта (спека §3.1). Решает, куда попадают деньги:
 * DEBIT и CASH — в свободные, DEPOSIT — в капитал минуя свободные,
 * CREDIT — в обязательства.
 */
public enum AccountKind {
    DEBIT, CREDIT, DEPOSIT, CASH
}
