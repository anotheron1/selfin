package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.BalanceCheckpointDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
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

    /**
     * История чекпоинтов, от свежих к старым, с дрейфом каждого интервала (ANO-15 §4):
     * computedBalance = prev.amount + знаковые факты в (prev.date, cur.date]
     * (правило фактов = PocketEngine.currentBalance: factAmount != null, не-wishlist);
     * drift = amount − computedBalance. Один range-запрос фактов на всю историю.
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
        for (List<BalanceCheckpoint> group : byAccount.values()) {
            boolean isDefault = group.get(0).getAccount().isDefaultAccount();
            for (int i = 0; i < group.size(); i++) {
                BalanceCheckpoint cur = group.get(i);
                if (i == group.size() - 1 || !isDefault) {
                    byId.put(cur.getId(), toDto(cur, null, null)); // самый ранний своего счёта, либо не-дефолтный счёт
                    continue;
                }
                BalanceCheckpoint prev = group.get(i + 1);
                BigDecimal delta = facts.stream()
                        .filter(e -> e.getDate().isAfter(prev.getDate())
                                && !e.getDate().isAfter(cur.getDate()))
                        .map(e -> signed(e.getType(), e.getFactAmount()))
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
                .account(defaultAccount())
                .build();
        return toDto(repository.save(checkpoint), null, null);
    }

    @Transactional
    public BalanceCheckpointDto update(UUID id, BalanceCheckpointCreateDto dto) {
        BalanceCheckpoint checkpoint = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BalanceCheckpoint", id));
        checkpoint.setDate(dto.date());
        checkpoint.setAmount(dto.amount());
        checkpoint.setUpdatedAt(LocalDateTime.now());
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
     * Счёт-приёмник для ре-якоря остатка, введённого без выбора счёта (Task 3.3
     * добавит выбор в API). Отсутствие дефолтного счёта после миграции V20
     * невозможно в норме — падаем явной ошибкой, а не NPE ниже по стеку.
     */
    private Account defaultAccount() {
        return accountRepository.findByDefaultAccountTrueAndDeletedFalse()
                .orElseThrow(() -> new IllegalStateException(
                        "No default account found — invariant from V20 migration violated"));
    }

    private static BigDecimal signed(EventType type, BigDecimal amount) {
        return type == EventType.INCOME ? amount : amount.negate();
    }

    private BalanceCheckpointDto toDto(BalanceCheckpoint cp, BigDecimal computed, BigDecimal drift) {
        return new BalanceCheckpointDto(cp.getId(), cp.getDate(), cp.getAmount(),
                cp.getCreatedAt(), computed, drift);
    }
}
