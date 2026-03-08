package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.BalanceCheckpointDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.repository.BalanceCheckpointRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BalanceCheckpointService {

    private final BalanceCheckpointRepository repository;

    /** Самый свежий чекпоинт — точка отсчёта для всех балансовых расчётов. */
    public Optional<BalanceCheckpoint> findLatest() {
        return repository.findTopByOrderByDateDesc();
    }

    /** История всех чекпоинтов, от свежих к старым. */
    public List<BalanceCheckpointDto> findAll() {
        return repository.findAllByOrderByDateDesc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public BalanceCheckpointDto create(BalanceCheckpointCreateDto dto) {
        BalanceCheckpoint checkpoint = BalanceCheckpoint.builder()
                .date(dto.date())
                .amount(dto.amount())
                .build();
        return toDto(repository.save(checkpoint));
    }

    @Transactional
    public BalanceCheckpointDto update(UUID id, BalanceCheckpointCreateDto dto) {
        BalanceCheckpoint checkpoint = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BalanceCheckpoint", id));
        checkpoint.setDate(dto.date());
        checkpoint.setAmount(dto.amount());
        checkpoint.setUpdatedAt(LocalDateTime.now());
        return toDto(repository.save(checkpoint));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("BalanceCheckpoint", id);
        }
        repository.deleteById(id);
    }

    private BalanceCheckpointDto toDto(BalanceCheckpoint cp) {
        return new BalanceCheckpointDto(cp.getId(), cp.getDate(), cp.getAmount(), cp.getCreatedAt());
    }
}
