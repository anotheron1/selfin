package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Единственное место, где живёт правило «остаток счёта на дату» (спека §4.1).
 *
 * <p>Безадресные факты применяются ТОЛЬКО к дефолтному счёту: транзакции адреса не
 * имеют осознанно (§5.1), разнести их по счетам нечем. Остальные счета двигаются
 * только чекпоинтами — известное ограничение первой версии (§6). Фиктивных операций,
 * чтобы это обойти, не выдумываем: замерший остаток честнее мусора в аналитике.
 *
 * <p>NB: правило отбора фактов продублировано в {@link PocketEngine} — движок чистый и
 * суммирует факты сам. Схождение этих двух мест — предмет ANO-23, здесь не решается,
 * но при правке одного обязательно править второе.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBalanceService {

    private final AccountRepository accountRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final FinancialEventRepository eventRepository;

    public Optional<Account> defaultAccount() {
        return accountRepository.findByDefaultAccountTrueAndDeletedFalse();
    }

    public List<Account> active() {
        return accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc();
    }

    public Optional<BalanceCheckpoint> anchorAt(Account a, LocalDate t) {
        return checkpointRepository.findLatestForAccountAt(a.getId(), t);
    }

    /** Остаток счёта на дату. Для CREDIT — доступный остаток. */
    public BigDecimal balanceAt(Account a, LocalDate t) {
        Optional<BalanceCheckpoint> anchor = anchorAt(a, t);
        if (anchor.isEmpty()) return BigDecimal.ZERO;
        BigDecimal base = anchor.get().getAmount();
        if (!a.isDefaultAccount()) return base;
        return base.add(factsDelta(anchor.get().getDate(), t));
    }

    /** Свободные деньги со счетов, КРОМЕ дефолтного: его считает движок кармашка сам. */
    public BigDecimal otherFreeMoneyAt(LocalDate t) {
        return active().stream()
                .filter(Account::countsAsFreeMoney)
                .filter(a -> !a.isDefaultAccount())
                .map(a -> balanceAt(a, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Полу-ликвид: вклады (§4.3). */
    public BigDecimal semiLiquidAt(LocalDate t) {
        return active().stream()
                .filter(Account::isSemiLiquid)
                .map(a -> balanceAt(a, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Резерв возврата карт к планке (§4.2). Счёт без планки в резерв не входит. */
    public BigDecimal creditRestoreReserveAt(LocalDate t) {
        return creditGap(t, Account::getAvailableFloor);
    }

    /** Долг по кредиткам для капитала (§4.4). Считается от лимита, а не от планки. */
    public BigDecimal creditDebtAt(LocalDate t) {
        return creditGap(t, Account::getCreditLimit);
    }

    private BigDecimal creditGap(LocalDate t, java.util.function.Function<Account, BigDecimal> level) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Account a : active()) {
            if (a.getKind() != AccountKind.CREDIT) continue;
            BigDecimal target = level.apply(a);
            if (target == null) continue;
            Optional<BalanceCheckpoint> anchor = anchorAt(a, t);
            if (anchor.isEmpty()) continue; // нет остатка — счёт молчит, а не считает ноль
            BigDecimal gap = target.subtract(anchor.get().getAmount());
            if (gap.signum() > 0) sum = sum.add(gap);
        }
        return sum;
    }

    /**
     * Знаковая сумма фактов в {@code (from, to]}. Правило совпадает с шагом 1
     * {@link PocketEngine#calculate}: только факты, не-хотелки, дата не позже {@code to}
     * и строго после даты якоря — операции дня чекпоинта уже внутри его суммы (ANO-15 §5).
     */
    private BigDecimal factsDelta(LocalDate from, LocalDate to) {
        return eventRepository.findAllByDeletedFalseAndDateBetween(from, to).stream()
                .filter(e -> e.getFactAmount() != null)
                .filter(e -> e.getWishlistStatus() == null)
                .filter(e -> e.getDate() != null && e.getDate().isAfter(from))
                .map(e -> e.getType() == EventType.INCOME
                        ? e.getFactAmount() : e.getFactAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
