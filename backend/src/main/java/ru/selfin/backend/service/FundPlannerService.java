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
        // PLANы (для плановых агрегатов)
        List<FinancialEvent> plans =
                eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED);

        YearMonth current = YearMonth.now();
        LocalDate today = LocalDate.now();

        // FACT-записи текущего месяца (для фактических агрегатов)
        LocalDate monthStart = current.atDay(1);
        LocalDate monthEnd = current.atEndOfMonth();
        List<FinancialEvent> currentMonthFacts =
                eventRepository.findFactsByDateRange(monthStart, monthEnd);

        List<FundPlannerMonthDto> months = new ArrayList<>(36);

        for (int i = 0; i < 36; i++) {
            YearMonth month = current.plusMonths(i);

            List<FinancialEvent> allMonthPlans = plans.stream()
                    .filter(e -> e.getDate() != null
                            && YearMonth.from(e.getDate()).equals(month))
                    .toList();

            // Текущий месяц: плановые агрегаты только от сегодня и далее
            List<FinancialEvent> monthPlans = (i == 0)
                    ? allMonthPlans.stream()
                            .filter(e -> !e.getDate().isBefore(today))
                            .toList()
                    : allMonthPlans;

            BigDecimal plannedIncome = monthPlans.stream()
                    .filter(e -> e.getType() == EventType.INCOME)
                    .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal mandatoryExpenses = monthPlans.stream()
                    .filter(e -> e.getPriority() == Priority.HIGH && e.getType() == EventType.EXPENSE)
                    .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (i == 0) {
                BigDecimal overdueMandate = eventRepository.sumOverdueMandatoryExpenses(
                        today.withDayOfMonth(1), today);
                mandatoryExpenses = mandatoryExpenses.add(overdueMandate);
            }

            BigDecimal allPlannedExpenses = monthPlans.stream()
                    .filter(e -> e.getType() == EventType.EXPENSE)
                    .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal factExpenses = null;
            BigDecimal factIncome = null;
            if (i == 0) {
                // Факты берём из отдельного запроса FACT-записей (после V12 PLANы не имеют factAmount)
                factExpenses = currentMonthFacts.stream()
                        .filter(e -> e.getType() == EventType.EXPENSE)
                        .map(FinancialEvent::getFactAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                factIncome = currentMonthFacts.stream()
                        .filter(e -> e.getType() == EventType.INCOME)
                        .map(FinancialEvent::getFactAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            months.add(new FundPlannerMonthDto(
                    month.toString(),
                    plannedIncome,
                    mandatoryExpenses,
                    allPlannedExpenses,
                    factExpenses,
                    factIncome));
        }

        return new FundPlannerDto(months);
    }
}
