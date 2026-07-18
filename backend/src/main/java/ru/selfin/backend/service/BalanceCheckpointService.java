package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.BalanceCheckpointDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BalanceCheckpointService {

    private final BalanceCheckpointRepository repository;
    private final FinancialEventRepository eventRepository;

    /** Самый свежий чекпоинт — точка отсчёта для всех балансовых расчётов. */
    public Optional<BalanceCheckpoint> findLatest() {
        return repository.findTopByOrderByDateDesc();
    }

    /**
     * История чекпоинтов, от свежих к старым, с дрейфом каждого интервала (ANO-15 §4):
     * computedBalance = prev.amount + знаковые факты в (prev.date, cur.date]
     * (правило фактов = PocketEngine.currentBalance: factAmount != null, не-wishlist);
     * drift = amount − computedBalance. Один range-запрос на всю цепочку.
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

        return java.util.stream.IntStream.range(0, chain.size())
                .mapToObj(i -> {
                    BalanceCheckpoint cur = chain.get(i);
                    if (i == chain.size() - 1) return toDto(cur, null, null); // самый ранний
                    BalanceCheckpoint prev = chain.get(i + 1);
                    BigDecimal delta = facts.stream()
                            .filter(e -> e.getDate().isAfter(prev.getDate())
                                    && !e.getDate().isAfter(cur.getDate()))
                            .map(e -> signed(e.getType(), e.getFactAmount()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal computed = prev.getAmount().add(delta);
                    return toDto(cur, computed, cur.getAmount().subtract(computed));
                })
                .toList();
    }

    @Transactional
    public BalanceCheckpointDto create(BalanceCheckpointCreateDto dto) {
        BalanceCheckpoint checkpoint = BalanceCheckpoint.builder()
                .date(dto.date())
                .amount(dto.amount())
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

    private static BigDecimal signed(EventType type, BigDecimal amount) {
        return type == EventType.INCOME ? amount : amount.negate();
    }

    private BalanceCheckpointDto toDto(BalanceCheckpoint cp, BigDecimal computed, BigDecimal drift) {
        return new BalanceCheckpointDto(cp.getId(), cp.getDate(), cp.getAmount(),
                cp.getCreatedAt(), computed, drift);
    }
}
