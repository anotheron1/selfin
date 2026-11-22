package ru.selfin.backend.service;

import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.FundAccountLink;
import ru.selfin.backend.model.enums.EventType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * История привязок копилок к счетам (ANO-163) на один расчёт: на каком счёте копилка лежала в день записи.
 *
 * <p>Срез 4, п. 6 (ANO-262): перевод в копилку на своём счёте уводит деньги с основной карты на этот счёт. Решает
 * день записи, а не сегодняшний счёт копилки: привязка сегодня не переписывает прошлые переводы (ANO-158), отвязка —
 * время, прожитое на счёте (ANO-163). День привязки — день на счёте, день отвязки — день конверта, как в
 * {@code FundTransactionRepository.sumEnvelopeFundsByTransactionDateLessThanEqual}.
 *
 * <p>Копилка на самой основной карте денег с неё не уводит: перевод остаётся долей её остатка.
 *
 * @param defaultAccountId основная карта; {@code null} — её нет
 */
public record FundLinks(List<FundAccountLink> links, UUID defaultAccountId) {

    /** Счёт, на котором копилка лежала в этот день; {@code null} — конвертом. */
    public UUID accountOn(UUID fundId, LocalDate day) {
        if (fundId == null || day == null) return null;
        return links.stream()
                .filter(l -> l.getFundId().equals(fundId))
                .filter(l -> !l.getLinkedFrom().isAfter(day) && (l.getLinkedTo() == null || l.getLinkedTo().isAfter(day)))
                .map(FundAccountLink::getAccountId)
                .findFirst().orElse(null);
    }

    /** Перевод уводит деньги с основной: копилка в день записи лежала на своём счёте, не на основной. */
    public boolean leavesCard(FinancialEvent e) {
        if (e.getType() != EventType.FUND_TRANSFER) return false;
        UUID account = accountOn(e.getTargetFundId(), e.getDate());
        return account != null && !account.equals(defaultAccountId);
    }

    /** Копилки, жившие на этом счёте хоть день, — чьи переводы может нести его остаток. */
    public List<UUID> fundsEverOn(UUID accountId) {
        return links.stream().filter(l -> l.getAccountId().equals(accountId))
                .map(FundAccountLink::getFundId).distinct().toList();
    }
}
