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
import java.time.LocalDate;
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

            // All events in the month — used for factExpenses (must include past executed)
            List<FinancialEvent> allMonthEvents = events.stream()
                    .filter(e -> e.getDate() != null
                            && YearMonth.from(e.getDate()).equals(month))
                    .toList();

            // For the current month: only events from today onwards feed the planned aggregates.
            // For future months: use all events in the month.
            List<FinancialEvent> monthEvents = (i == 0)
                    ? allMonthEvents.stream()
                            .filter(e -> !e.getDate().isBefore(LocalDate.now()))
                            .toList()
                    : allMonthEvents;

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

            BigDecimal factExpenses = null;
            if (i == 0) {
                factExpenses = allMonthEvents.stream()
                        .filter(e -> e.getType() == EventType.EXPENSE
                                && e.getStatus() == EventStatus.EXECUTED
                                && e.getFactAmount() != null)
                        .map(FinancialEvent::getFactAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            months.add(new FundPlannerMonthDto(
                    month.toString(),
                    plannedIncome,
                    mandatoryExpenses,
                    allPlannedExpenses,
                    factExpenses));
        }

        return new FundPlannerDto(months);
    }
}
