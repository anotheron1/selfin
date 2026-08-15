package ru.selfin.backend.model;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.enums.AccountKind;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    private static Account of(AccountKind kind, boolean track) {
        return Account.builder().name("тест").kind(kind).trackBalance(track).build();
    }

    @Test
    void debitWithTrackingCountsAsFreeMoney() {
        assertThat(of(AccountKind.DEBIT, true).countsAsFreeMoney()).isTrue();
    }

    @Test
    void cashWithTrackingCountsAsFreeMoney() {
        assertThat(of(AccountKind.CASH, true).countsAsFreeMoney()).isTrue();
    }

    @Test
    void envelopeWithoutTrackingDoesNotCount() {
        // Конверт: за остатком не следим, значит посчитать его нечем (спека §4.1)
        assertThat(of(AccountKind.DEBIT, false).countsAsFreeMoney()).isFalse();
    }

    @Test
    void creditNeverCountsAsFreeMoney() {
        assertThat(of(AccountKind.CREDIT, true).countsAsFreeMoney()).isFalse();
    }

    @Test
    void depositIsSemiLiquidNotFreeMoney() {
        assertThat(of(AccountKind.DEPOSIT, true).countsAsFreeMoney()).isFalse();
    }

    @Test
    void deletedAccountDropsOut() {
        Account a = of(AccountKind.DEBIT, true);
        a.setDeleted(true);
        assertThat(a.countsAsFreeMoney()).isFalse();
    }

    @Test
    void depositWithTrackingIsSemiLiquid() {
        assertThat(of(AccountKind.DEPOSIT, true).isSemiLiquid()).isTrue();
    }

    @Test
    void debitIsNotSemiLiquid() {
        assertThat(of(AccountKind.DEBIT, true).isSemiLiquid()).isFalse();
    }

    @Test
    void cashIsNotSemiLiquid() {
        assertThat(of(AccountKind.CASH, true).isSemiLiquid()).isFalse();
    }

    @Test
    void creditIsNotSemiLiquid() {
        assertThat(of(AccountKind.CREDIT, true).isSemiLiquid()).isFalse();
    }

    @Test
    void deletedDepositIsNotSemiLiquid() {
        Account a = of(AccountKind.DEPOSIT, true);
        a.setDeleted(true);
        assertThat(a.isSemiLiquid()).isFalse();
    }
}
