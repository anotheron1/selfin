package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private final CategoryRepository categoryRepository;
    private final PredictionService predictionService;
    // ВАЖНО: НЕ добавлять CapitalRevaluationRepository здесь.
    // Доступ к "earliest revaluation" идёт через capitalService.findEarliestRevaluationDate() —
    // CapitalService уже инжектит revRepo и предоставляет публичный метод.
    private final CapitalService capitalService;

    /**
     * Точка входа для GET /api/v1/strategy/timeline.
     * Реализуется в следующих чанках.
     */
    public StrategyTimelineDto getTimeline(int historyMonths, int horizonMonths, boolean withBreakdown) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    List<StrategyTimelinePointDto> buildPastPoints(YearMonth from, YearMonth currentMonth) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        if (from.isAfter(currentMonth.minusMonths(1))) {
            return points; // нет прошлых месяцев
        }

        LocalDate windowStart = from.atDay(1);
        LocalDate windowEnd = currentMonth.minusMonths(1).atEndOfMonth();

        // Один запрос фактов на весь диапазон, потом группируем
        Map<YearMonth, List<FinancialEvent>> factsByMonth = eventRepository
                .findFactsByDateRange(windowStart, windowEnd).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        for (YearMonth ym = from; ym.isBefore(currentMonth); ym = ym.plusMonths(1)) {
            List<FinancialEvent> facts = factsByMonth.getOrDefault(ym, List.of());

            BigDecimal income = sumByType(facts, EventType.INCOME);
            BigDecimal expense = sumByType(facts, EventType.EXPENSE);
            BigDecimal nettoFlow = income.subtract(expense);

            BigDecimal balance = capitalService.liquidAt(ym.atEndOfMonth());

            points.add(new StrategyTimelinePointDto(
                    ym,
                    StrategyPointPhase.PAST,
                    balance,
                    income,
                    expense,
                    nettoFlow,
                    null, null, null,                       // balanceConfirmed/Low/High не для PAST
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,  // капитал — заполнится в enrichWithCapital
                    null                                    // breakdown — заполнится в enrichWithBreakdown
            ));
        }
        return points;
    }

    private BigDecimal sumByType(List<FinancialEvent> facts, EventType type) {
        return facts.stream()
                .filter(e -> e.getType() == type)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
