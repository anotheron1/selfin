package ru.selfin.backend.service;

import org.springframework.stereotype.Component;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless генератор материализованных событий из {@link RecurringRule}.
 *
 * <p>Алгоритм и правила clamp'а — см. spec → Секция 2.
 */
@Component
public class RecurringEventGenerator {

    public List<FinancialEvent> generate(RecurringRule rule, LocalDate from, LocalDate through) {
        if (rule.getStartDate().isAfter(through)) {
            return List.of();
        }
        LocalDate effectiveFrom = from.isBefore(rule.getStartDate()) ? rule.getStartDate() : from;
        LocalDate cursor = firstOnOrAfter(rule, effectiveFrom);
        if (cursor == null || cursor.isAfter(through)) {
            return List.of();
        }
        List<FinancialEvent> result = new ArrayList<>();
        while (!cursor.isAfter(through)) {
            result.add(buildEvent(rule, cursor));
            cursor = next(rule, cursor);
        }
        return result;
    }

    private LocalDate firstOnOrAfter(RecurringRule rule, LocalDate from) {
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            YearMonth ym = YearMonth.from(from);
            int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
            LocalDate candidate = ym.atDay(day);
            return candidate.isBefore(from) ? next(rule, candidate) : candidate;
        }
        // YEARLY
        YearMonth ym = YearMonth.of(from.getYear(), rule.getMonthOfYear());
        int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
        LocalDate candidate = ym.atDay(day);
        return candidate.isBefore(from) ? next(rule, candidate) : candidate;
    }

    private LocalDate next(RecurringRule rule, LocalDate cursor) {
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            YearMonth ym = YearMonth.from(cursor).plusMonths(1);
            int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
            return ym.atDay(day);
        }
        YearMonth ym = YearMonth.of(cursor.getYear() + 1, rule.getMonthOfYear());
        int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
        return ym.atDay(day);
    }

    private FinancialEvent buildEvent(RecurringRule rule, LocalDate date) {
        return FinancialEvent.builder()
                .date(date)
                .category(rule.getCategory())
                .type(rule.getEventType())
                .plannedAmount(rule.getPlannedAmount())
                .priority(rule.getPriority())
                .description(rule.getDescription())
                .status(EventStatus.PLANNED)
                .eventKind(EventKind.PLAN)
                .recurringRule(rule)
                .build();
    }
}
