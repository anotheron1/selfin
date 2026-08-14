package ru.selfin.backend.model;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.enums.AccountKind;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    private static Account of(AccountKind kind, boolean track) {
        return Account.builder().name("тест").kind(kind).trackBalance(track).build();
    }

    @Test
    void debitWithTrackingCountsAsFreeMoney() {
        assertTrue(of(AccountKind.DEBIT, true).countsAsFreeMoney());
    }

    @Test
    void envelopeWithoutTrackingDoesNotCount() {
        // Конверт: за остатком не следим, значит посчитать его нечем (спека §4.1)
        assertFalse(of(AccountKind.DEBIT, false).countsAsFreeMoney());
    }

    @Test
    void creditNeverCountsAsFreeMoney() {
        assertFalse(of(AccountKind.CREDIT, true).countsAsFreeMoney());
    }

    @Test
    void depositIsSemiLiquidNotFreeMoney() {
        assertFalse(of(AccountKind.DEPOSIT, true).countsAsFreeMoney());
    }

    @Test
    void deletedAccountDropsOut() {
        Account a = of(AccountKind.DEBIT, true);
        a.setDeleted(true);
        assertFalse(a.countsAsFreeMoney());
    }

    @Test
    void depositWithTrackingIsSemiLiquid() {
        assertTrue(of(AccountKind.DEPOSIT, true).isSemiLiquid());
    }

    @Test
    void debitIsNotSemiLiquid() {
        assertFalse(of(AccountKind.DEBIT, true).isSemiLiquid());
    }

    @Test
    void deletedDepositIsNotSemiLiquid() {
        Account a = of(AccountKind.DEPOSIT, true);
        a.setDeleted(true);
        assertFalse(a.isSemiLiquid());
    }
}
