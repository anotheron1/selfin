package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.BalanceCheckpointDto;
import ru.selfin.backend.dto.BalanceSheetDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BalanceCheckpointService {

    private final BalanceCheckpointRepository repository;
    private final FinancialEventRepository eventRepository;
    private final AccountRepository accountRepository;
    /** ANO-39: «сегодня» приходит извне — иначе календарную логику не проверить детерминированно. */
    private final Clock clock;
    /** «по записям» карты листа — то же правило, что у остатка счёта (срез 4, п. 3). */
    private final AccountBalanceService balances;
    /** «Свободно» до и после пакета — то же число, что {@code GET /pocket}. */
    private final PocketService pocketService;

    /** Лист «Обновить остатки» (срез 4, п. 3): карты с остатком — основная, вторая «на всё», накопительные, кредитки. */
    public BalanceSheetDto sheet() {
        LocalDate today = LocalDate.now(clock);
        List<Account> cards = balances.active().stream()
                .filter(Account::isTrackBalance)
                .sorted(java.util.Comparator.comparingInt(BalanceCheckpointService::sheetGroup))
                .toList();
        return new BalanceSheetDto(cards.stream()
                .map(a -> {
                    BalanceCheckpoint anchor = balances.anchorAt(a, today).orElse(null);
                    BigDecimal records = byRecords(a, today, anchor);
                    return new BalanceSheetDto.Card(a.getId(), a.getName(), a.getKind(), a.isDefaultAccount(),
                            records, anchor != null ? anchor.getDate() : null, transfersSince(a, anchor, records));
                })
                .toList(), onCredit(cards, today));
    }

    /** «по записям» карты, кроме основной, — обновление плюс переводы в её копилку ({@code balanceAt}): разница — они. */
    private static BigDecimal transfersSince(Account a, BalanceCheckpoint anchor, BigDecimal records) {
        if (anchor == null || a.isDefaultAccount() || a.getKind() == AccountKind.CREDIT) return null;
        return records.subtract(anchor.getAmount());
    }

    /**
     * «В кредит записано» (срез 4, п. 2): записи с отметкой с самого старого из последних обновлений кредиток. Граница —
     * {@link AnchorWindow}: записанное до ввода обновления уже в числе из банка.
     */
    private BalanceSheetDto.OnCredit onCredit(List<Account> cards, LocalDate today) {
        BalanceCheckpoint oldest = cards.stream()
                .filter(a -> a.getKind() == AccountKind.CREDIT)
                .flatMap(a -> balances.anchorAt(a, today).stream())
                .min(java.util.Comparator.comparing(BalanceCheckpoint::getDate)
                        .thenComparing(BalanceCheckpoint::getCreatedAt,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .orElse(null);
        if (oldest == null) return null;
        BigDecimal amount = eventRepository.findAllByDeletedFalseAndDateBetween(oldest.getDate(), today).stream()
                .filter(e -> e.isOnCredit() && e.getFactAmount() != null && e.getWishlistStatus() == null)
                .filter(e -> AnchorWindow.countsTowardBalance(e.getDate(), e.getCreatedAt(),
                        oldest.getDate(), oldest.getCreatedAt(), today))
                .map(FinancialEvent::getFactAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BalanceSheetDto.OnCredit(oldest.getDate(), amount);
    }

    /** Порядок листа; внутри группы — порядок {@code active()}: сортировка устойчивая. */
    private static int sheetGroup(Account a) {
        if (a.isDefaultAccount()) return 0;
        return switch (a.getKind()) {
            case DEBIT, CASH -> 1;
            case DEPOSIT -> 2;
            case CREDIT -> 3;
        };
    }

    private BigDecimal byRecords(Account a, LocalDate today, BalanceCheckpoint anchor) {
        return anchor != null ? balances.balanceAt(a, today, anchor) : null;
    }

    /** Пакет обновлений листа: все числа — одной транзакцией, ошибка одного не пишет ни одного. */
    @Transactional
    public BalanceSheetDto.Result batch(BalanceSheetDto.Batch dto) {
        return write(dto);
    }

    /** Предпросмотр — тот же {@link #write} в транзакции с откатом: итог совпадает с записью по построению. */
    @Transactional
    public BalanceSheetDto.Result preview(BalanceSheetDto.Batch dto) {
        BalanceSheetDto.Result result = write(dto);
        org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return result;
    }

    /** Старые обновления не правятся и не удаляются: пакет только добавляет обновление на сегодня. */
    private BalanceSheetDto.Result write(BalanceSheetDto.Batch dto) {
        LocalDate today = LocalDate.now(clock);
        BigDecimal freeBefore = free(today);
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        List<BalanceSheetDto.Change> cards = new java.util.ArrayList<>();
        for (BalanceSheetDto.Entry e : dto.entries()) {
            if (!seen.add(e.accountId())) throw badRequest("A card appears twice in the batch");
            if (e.amount() == null) continue; // пустое поле — карта не обновляется
            Account account = resolveAccount(e.accountId());
            if (!account.isTrackBalance()) throw badRequest("This card has no balance to update");
            BigDecimal records = byRecords(account, today, balances.anchorAt(account, today).orElse(null));
            repository.save(BalanceCheckpoint.builder().date(today).amount(e.amount()).account(account).build());
            cards.add(new BalanceSheetDto.Change(account.getId(),
                    records != null ? e.amount().subtract(records) : null));
        }
        repository.flush();
        return new BalanceSheetDto.Result(freeBefore, free(today), cards);
    }

    private BigDecimal free(LocalDate today) {
        return pocketService.getPocket(null, today).pocket();
    }

    private static org.springframework.web.server.ResponseStatusException badRequest(String message) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    /**
     * История чекпоинтов, от свежих к старым, с дрейфом каждого интервала (ANO-15 §4):
     * computedBalance = prev.amount + знаковые факты окна {@code prev → cur}
     * (factAmount != null, не-wishlist; граница окна — {@link AnchorWindow}, то же правило,
     * что у {@link PocketEngine#calculate} и {@link AccountBalanceService#balanceAt});
     * drift = amount − computedBalance. Один range-запрос фактов на всю историю.
     *
     * <p>После ANO-82 обе границы окна решаются по времени ЗАПИСИ, а не по дате, и это
     * несимметрично только на первый взгляд. Дрейф сравнивает посчитанный остаток с числом
     * ВТОРОГО якоря, поэтому факт обязан быть уже вне числа {@code prev} и ещё внутри числа
     * {@code cur}. Пропустить верхнюю границу — значит включить в расчёт трату, записанную
     * после сверки, и показать расхождение там, где его нет (найдено ревью PR #41).
     *
     * <p><b>Цепочка группируется по счёту (Task 2.4).</b> Дрейф второго и последующих
     * чекпоинтов каждого счёта считается от ПРЕДЫДУЩЕГО чекпоинта ТОГО ЖЕ счёта, а не
     * от соседнего по дате чекпоинта чужого счёта — иначе при нескольких счетах дрейф
     * сравнивал бы, например, остаток вклада с остатком карты.
     *
     * <p>Дрейф вычисляется ТОЛЬКО для дефолтного счёта. Безадресные факты применяются
     * исключительно к нему ({@link AccountBalanceService} — «Безадресные факты…»),
     * поэтому между двумя чекпоинтами прочего счёта журнал в принципе не двигает остаток:
     * там «дрейф» всегда равнялся бы полной разнице между соседними якорями и не был бы
     * диагностикой рассинхрона, а был бы просто перепечаткой этой разницы. Прочим счетам
     * возвращается {@code null} — как самому раннему чекпоинту в цепочке.
     *
     * <p>Группировка — в памяти по одному запросу {@link
     * BalanceCheckpointRepository#findAllByOrderByDateDesc()}, а не по запросу на счёт
     * (такой метод, {@code findAllForAccountOrderByDateDesc}, был добавлен в Task 2.1 как
     * задел и здесь сознательно не используется — удалён как мёртвый): чекпоинтов в системе
     * за всю историю немного, а счетов может быть несколько — один range-запрос дешевле, чем
     * N запросов на N счетов. {@code cp.getAccount().getId()} читается с ленивого прокси без
     * обращения к БД (Hibernate знает id ассоциации из FK), поэтому группировка сама по себе
     * не стоит ни одного лишнего запроса.
     */
    public List<BalanceCheckpointDto> findAll() {
        List<BalanceCheckpoint> chain = repository.findAllByOrderByDateDesc();
        if (chain.isEmpty()) return List.of();
        if (chain.size() == 1) {
            BalanceCheckpoint only = chain.get(0);
            return List.of(toDto(only, null, null));
        }

        List<FinancialEvent> facts = eventRepository.findAllByDeletedFalseAndDateBetween(
                        chain.get(chain.size() - 1).getDate(), chain.get(0).getDate())
                .stream()
                .filter(e -> e.getFactAmount() != null && e.getWishlistStatus() == null)
                .toList();

        // LinkedHashMap/toList — чтобы относительный порядок чекпоинтов внутри группы
        // остался тем же, что и в исходной цепочке (date DESC, createdAt DESC).
        Map<UUID, List<BalanceCheckpoint>> byAccount = chain.stream()
                .collect(Collectors.groupingBy(cp -> cp.getAccount().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<UUID, BalanceCheckpointDto> byId = new LinkedHashMap<>();
        FundLinks links = balances.fundLinks();
        for (List<BalanceCheckpoint> group : byAccount.values()) {
            boolean isDefault = group.get(0).getAccount().isDefaultAccount();
            for (int i = 0; i < group.size(); i++) {
                BalanceCheckpoint cur = group.get(i);
                if (i == group.size() - 1 || !isDefault) {
                    byId.put(cur.getId(), toDto(cur, null, null)); // самый ранний своего счёта, либо не-дефолтный счёт
                    continue;
                }
                BalanceCheckpoint prev = group.get(i + 1);
                // Граница окна — одна на все три места, живёт в AnchorWindow (ANO-82).
                // Дрейфу нужны ОБЕ границы по времени записи: он сравнивает посчитанный
                // остаток с числом ВТОРОГО якоря, а не с сегодняшним днём. Трата, записанная
                // после сверки, в это число не попала — и в computedBalance ей не место.
                BigDecimal delta = facts.stream()
                        .filter(e -> AnchorWindow.fallsBetweenAnchors(
                                e.getDate(), e.getCreatedAt(),
                                prev.getDate(), prev.getCreatedAt(),
                                cur.getDate(), cur.getCreatedAt()))
                        .map(e -> AnchorWindow.balanceEffect(e.getType(), e.getFactAmount(), e.isOnCredit(),
                                links.leavesCard(e)))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal computed = prev.getAmount().add(delta);
                byId.put(cur.getId(), toDto(cur, computed, cur.getAmount().subtract(computed)));
            }
        }

        // Порядок ответа — исходный (date DESC по всей системе, не по группе).
        return chain.stream().map(cp -> byId.get(cp.getId())).toList();
    }

    @Transactional
    public BalanceCheckpointDto create(BalanceCheckpointCreateDto dto) {
        BalanceCheckpoint checkpoint = BalanceCheckpoint.builder()
                .date(dto.date())
                .amount(dto.amount())
                .account(resolveAccount(dto.accountId()))
                .build();
        return toDto(repository.save(checkpoint), null, null);
    }

    @Transactional
    public BalanceCheckpointDto update(UUID id, BalanceCheckpointCreateDto dto) {
        BalanceCheckpoint checkpoint = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BalanceCheckpoint", id));
        checkpoint.setDate(dto.date());
        checkpoint.setAmount(dto.amount());
        // Счёт меняется, только если его явно прислали: правка суммы задним числом не должна
        // молча переносить чужой якорь на счёт-приёмник.
        if (dto.accountId() != null) {
            checkpoint.setAccount(resolveAccount(dto.accountId()));
        }
        checkpoint.setUpdatedAt(LocalDateTime.now(clock));
        return toDto(repository.save(checkpoint), null, null);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("BalanceCheckpoint", id);
        }
        repository.deleteById(id);
    }

    /**
     * Счёт, чей остаток вводится. Без явного выбора — счёт-приёмник: так «ввёл остаток» днём 1
     * остаётся однокликовым и не требует от нового пользователя понимать, что такое счёт.
     * Отсутствие дефолтного счёта после миграции V20 невозможно в норме — падаем явной
     * ошибкой, а не NPE ниже по стеку.
     */
    private Account resolveAccount(UUID accountId) {
        if (accountId == null) {
            return accountRepository.findByDefaultAccountTrueAndDeletedFalse()
                    .orElseThrow(() -> new IllegalStateException(
                            "No default account found — invariant from V20 migration violated"));
        }
        return accountRepository.findById(accountId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private BalanceCheckpointDto toDto(BalanceCheckpoint cp, BigDecimal computed, BigDecimal drift) {
        Account account = cp.getAccount();
        return new BalanceCheckpointDto(cp.getId(), cp.getDate(), cp.getAmount(),
                account.getId(), account.getName(), cp.getCreatedAt(), computed, drift);
    }
}
