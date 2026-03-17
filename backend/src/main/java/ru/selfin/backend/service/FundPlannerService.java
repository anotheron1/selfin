package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FundPlannerDto;
import ru.selfin.backend.dto.FundPlannerMonthDto;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FundPlannerService {

    private final FinancialEventRepository eventRepository;

    /**
     * Возвращает агрегацию плановых событий по месяцам на 36 месяцев вперёд
     * для использования в планировщике накоплений.
     *
     * @return DTO с помесячной разбивкой плановых доходов и расходов
     */
    public FundPlannerDto getPlanner() {
        List<FinancialEvent> events =
                eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED);

        YearMonth current = YearMonth.now();
        List<FundPlannerMonthDto> months = new ArrayList<>(36);

        for (int i = 0; i < 36; i++) {
            YearMonth month = current.plusMonths(i);

            List<FinancialEvent> monthEvents = events.stream()
                    .filter(e -> e.getDate() != null
                            && YearMonth.from(e.getDate()).equals(month))
                    .toList();

            BigDecimal plannedIncome = monthEvents.stream()
                    .filter(e -> e.getType() == EventType.INCOME)
                    .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal mandatoryExpenses = monthEvents.stream()
                    .filter(e -> e.getPriority() == Priority.HIGH && e.getType() == EventType.EXPENSE)
                    .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal allPlannedExpenses = monthEvents.stream()
                    .filter(e -> e.getType() == EventType.EXPENSE)
                    .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            months.add(new FundPlannerMonthDto(
                    month.toString(),
                    plannedIncome,
                    mandatoryExpenses,
                    allPlannedExpenses));
        }

        return new FundPlannerDto(months);
    }
}
