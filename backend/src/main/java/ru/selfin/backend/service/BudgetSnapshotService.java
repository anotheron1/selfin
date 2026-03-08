package ru.selfin.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.BudgetSnapshotDto;
import ru.selfin.backend.model.BudgetSnapshot;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.repository.BudgetSnapshotRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Сервис снимков бюджета.
 * Фиксирует план на начало месяца — позволяет сравнить «Изначальный план vs
 * Факт».
 * Решает проблему «ползущего бюджета».
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BudgetSnapshotService {

    private final BudgetSnapshotRepository snapshotRepository;
    private final FinancialEventRepository eventRepository;

    /**
     * ObjectMapper создаётся локально чтобы не зависеть от Spring bean конфигурации
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Создаёт снимок бюджета за текущий или указанный месяц.
     * Идемпотентен: если снимок за этот месяц уже существует — возвращает его.
     */
    @Transactional
    public BudgetSnapshotDto createSnapshot(LocalDate referenceDate) {
        LocalDate periodStart = referenceDate.withDayOfMonth(1);
        LocalDate periodEnd = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth());

        // Идемпотентность: снимок за месяц уже есть
        List<BudgetSnapshot> existing = snapshotRepository
                .findAllByPeriodStartGreaterThanEqualOrderBySnapshotDateDesc(periodStart)
                .stream()
                .filter(s -> s.getPeriodStart().equals(periodStart))
                .toList();
        if (!existing.isEmpty()) {
            log.info("snapshot_exists period={}", periodStart);
            return toDto(existing.get(0));
        }

        // Делаем снимок: сериализуем все плановые события месяца в JSON
        List<FinancialEvent> events = eventRepository
                .findAllByDeletedFalseAndDateBetween(periodStart, periodEnd);

        String snapshotJson;
        try {
            snapshotJson = MAPPER.writeValueAsString(
                    events.stream().map(e -> new SnapshotEventEntry(
                            e.getId().toString(),
                            e.getDate().toString(),
                            e.getType().name(),
                            e.getCategory().getName(),
                            e.getPlannedAmount(),
                            e.getFactAmount())).toList());
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize snapshot", ex);
        }

        BudgetSnapshot snapshot = BudgetSnapshot.builder()
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .snapshotData(snapshotJson)
                .build();
        BudgetSnapshot saved = snapshotRepository.save(snapshot);

        log.info("snapshot_created period={} events_count={}", periodStart, events.size());
        return toDto(saved);
    }

    /**
     * Получить список снимков (последние 12 месяцев).
     */
    public List<BudgetSnapshotDto> getSnapshots() {
        LocalDate since = LocalDate.now().minusMonths(12).withDayOfMonth(1);
        return snapshotRepository
                .findAllByPeriodStartGreaterThanEqualOrderBySnapshotDateDesc(since)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Конвертирует entity снимка в DTO.
     * Поле {@code snapshotData} (JSONB с деталями событий) намеренно не включается
     * в ответ списка — оно большое и нужно только при детальном просмотре снимка.
     *
     * @param s entity снимка бюджета
     * @return облегчённый DTO для списка снимков
     */
    private BudgetSnapshotDto toDto(BudgetSnapshot s) {
        return new BudgetSnapshotDto(s.getId(), s.getPeriodStart(), s.getPeriodEnd(), s.getSnapshotDate());
    }

    /**
     * Вспомогательная запись для сериализации событий в JSON снимка.
     * Хранит только поля, необходимые для план-факт сравнения в будущем.
     */
    private record SnapshotEventEntry(
            String id, String date, String type, String category,
            java.math.BigDecimal planned, java.math.BigDecimal fact) {
    }
}
