package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Сервис стратегической временной шкалы.
 *
 * <p>Точка входа — {@link #getTimeline(int, int, boolean)}, реализуемая в следующих чанках.
 * Вспомогательные методы (firstActivityMonth и др.) реализуются по TDD в Chunk 2.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StrategyTimelineService {

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final CapitalService capitalService;

    /**
     * Точка входа для GET /api/v1/strategy/timeline.
     * Реализуется в следующих чанках.
     */
    public StrategyTimelineDto getTimeline(int historyMonths, int horizonMonths, boolean withBreakdown) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Самый ранний месяц активности пользователя — минимум из:
     * <ul>
     *   <li>первого FACT-события</li>
     *   <li>первого чекпоинта</li>
     *   <li>первой переоценки капитала</li>
     * </ul>
     * Если данных нет — возвращает предыдущий месяц (условный «старт»).
     *
     * <p>Используется для определения левой границы шкалы и {@code predictionWindowMonths}.
     */
    YearMonth firstActivityMonth() {
        Optional<LocalDate> earliestFact = eventRepository.findEarliestFactDate();
        Optional<LocalDate> earliestCheckpoint = checkpointRepository.findEarliestCheckpointDate();
        Optional<LocalDate> earliestRevaluation = capitalService.findEarliestRevaluationDate();

        Optional<LocalDate> earliest = Stream.of(earliestFact, earliestCheckpoint, earliestRevaluation)
                .flatMap(Optional::stream)
                .min(LocalDate::compareTo);

        return earliest
                .map(YearMonth::from)
                .orElseGet(() -> YearMonth.now().minusMonths(1));
    }
}
