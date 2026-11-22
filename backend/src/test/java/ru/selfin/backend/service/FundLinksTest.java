package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.FundAccountLink;
import ru.selfin.backend.model.enums.EventType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Срез 4, п. 6: уводит ли перевод деньги с основной — по счёту копилки в день записи (ANO-158, ANO-163). */
class FundLinksTest {

    static final UUID FUND = UUID.randomUUID(), DEPOSIT = UUID.randomUUID(), MAIN = UUID.randomUUID();
    static final LocalDate FROM = LocalDate.of(2026, 11, 10), TO = LocalDate.of(2026, 11, 20);

    static FinancialEvent transfer(LocalDate day) {
        return FinancialEvent.builder().type(EventType.FUND_TRANSFER).targetFundId(FUND).date(day).build();
    }

    static FundLinks links(UUID account) {
        return new FundLinks(List.of(FundAccountLink.builder().fundId(FUND).accountId(account)
                .linkedFrom(FROM).linkedTo(TO).build()), MAIN);
    }

    @Test
    @DisplayName("на своём счёте с дня привязки по день до отвязки — уводит; до привязки и в день отвязки — конверт")
    void leavesCard_byDayOnAccount() {
        FundLinks links = links(DEPOSIT);
        assertThat(links.leavesCard(transfer(FROM))).as("день привязки — день на счёте").isTrue();
        assertThat(links.leavesCard(transfer(TO.minusDays(1)))).isTrue();
        assertThat(links.leavesCard(transfer(FROM.minusDays(1)))).as("до привязки (ANO-158)").isFalse();
        assertThat(links.leavesCard(transfer(TO))).as("день отвязки — день конверта (ANO-163)").isFalse();
    }

    @Test
    @DisplayName("копилка на самой основной карте денег с неё не уводит; запись не перевод — тоже")
    void notLeavesCard_onMainOrNotTransfer() {
        assertThat(links(MAIN).leavesCard(transfer(FROM))).isFalse();
        FinancialEvent expense = transfer(FROM);
        expense.setType(EventType.EXPENSE);
        assertThat(links(DEPOSIT).leavesCard(expense)).isFalse();
    }
}
