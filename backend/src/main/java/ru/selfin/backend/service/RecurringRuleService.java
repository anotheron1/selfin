package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.dto.RecurringRuleCreateDto;
import ru.selfin.backend.dto.RecurringRuleDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecurringRuleService {

    private static final int GENERATION_HORIZON_MONTHS = 12;

    private final RecurringRuleRepository ruleRepository;
    private final FinancialEventRepository eventRepository;
    private final CategoryRepository categoryRepository;

    /** Create a rule and generate 12 months of PLANNED events. */
    @Transactional
    public RecurringRuleDto createRule(RecurringRuleCreateDto dto) {
        Category category = categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

        RecurringRule rule = RecurringRule.builder()
                .category(category)
                .eventType(dto.eventType())
                .targetFundId(dto.targetFundId())
                .plannedAmount(dto.plannedAmount())
                .mandatory(Boolean.TRUE.equals(dto.mandatory()))
                .description(dto.description())
                .frequency(dto.frequency())
                .dayOfMonth(dto.dayOfMonth())
                .dayOfWeek(dto.dayOfWeek())
                .startDate(dto.startDate())
                .endDate(dto.endDate())
                .build();
        rule = ruleRepository.save(rule);

        LocalDate horizon = dto.startDate().plusMonths(GENERATION_HORIZON_MONTHS);
        if (dto.endDate() != null && dto.endDate().isBefore(horizon)) {
            horizon = dto.endDate();
        }
        generateEvents(rule, dto.startDate(), horizon, category);

        return toDto(rule);
    }

    /**
     * Update rule fields and all PLANNED events from fromDate onward.
     * EXECUTED events are never touched.
     */
    @Transactional
    public void updateThisAndFollowing(UUID ruleId, LocalDate fromDate, RecurringRuleCreateDto dto) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("RecurringRule", ruleId));

        Category category = categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

        // Save old day before updating
        Integer oldDayOfMonth = rule.getDayOfMonth();

        // Update rule
        rule.setCategory(category);
        rule.setPlannedAmount(dto.plannedAmount());
        rule.setMandatory(Boolean.TRUE.equals(dto.mandatory()));
        rule.setDescription(dto.description());
        rule.setDayOfMonth(dto.dayOfMonth());
        rule.setDayOfWeek(dto.dayOfWeek());
        ruleRepository.save(rule);

        // Update all PLANNED events in the series from fromDate forward
        List<FinancialEvent> toUpdate = eventRepository
                .findAllByRecurringRuleIdAndDateGreaterThanEqualAndStatusAndDeletedFalse(
                        ruleId, fromDate, EventStatus.PLANNED);

        boolean dayChanged = !java.util.Objects.equals(dto.dayOfMonth(), oldDayOfMonth);

        if (dayChanged) {
            // Soft-delete existing events and regenerate with new day
            toUpdate.forEach(e -> e.setDeleted(true));
            eventRepository.saveAll(toUpdate);

            LocalDate horizon = fromDate.plusMonths(GENERATION_HORIZON_MONTHS);
            generateEvents(rule, fromDate, horizon, category);
        } else {
            // In-place update
            toUpdate.forEach(e -> {
                e.setCategory(category);
                e.setPlannedAmount(dto.plannedAmount());
                e.setMandatory(Boolean.TRUE.equals(dto.mandatory()));
                e.setDescription(dto.description());
            });
            eventRepository.saveAll(toUpdate);
        }
    }

    /**
     * Convenience overload called from FinancialEventService when the caller has a
     * FinancialEventCreateDto rather than a RecurringRuleCreateDto.
     * Mutable fields (category, plannedAmount, mandatory, description) come from the event dto;
     * schedule fields (frequency, dayOfMonth, dayOfWeek, startDate, endDate, eventType,
     * targetFundId) are preserved from the existing rule so the series cadence is not disrupted.
     */
    @Transactional
    public void updateThisAndFollowing(UUID ruleId, LocalDate fromDate, FinancialEventCreateDto dto) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("RecurringRule", ruleId));

        RecurringRuleCreateDto merged = new RecurringRuleCreateDto(
                dto.categoryId(),
                rule.getEventType(),
                rule.getTargetFundId(),
                dto.plannedAmount() != null ? dto.plannedAmount() : rule.getPlannedAmount(),
                dto.mandatory(),
                dto.description(),
                rule.getFrequency(),
                rule.getDayOfMonth(),
                rule.getDayOfWeek(),
                rule.getStartDate(),
                rule.getEndDate()
        );
        updateThisAndFollowing(ruleId, fromDate, merged);
    }

    /**
     * Soft-delete all PLANNED events in the series from fromDate onward.
     * If fromDate <= rule.startDate, also soft-delete the rule itself.
     */
    @Transactional
    public void deleteThisAndFollowing(UUID ruleId, LocalDate fromDate) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("RecurringRule", ruleId));

        List<FinancialEvent> toDelete = eventRepository
                .findAllByRecurringRuleIdAndDateGreaterThanEqualAndStatusAndDeletedFalse(
                        ruleId, fromDate, EventStatus.PLANNED);
        toDelete.forEach(e -> e.setDeleted(true));
        eventRepository.saveAll(toDelete);

        if (!fromDate.isAfter(rule.getStartDate())) {
            rule.setDeleted(true);
            ruleRepository.save(rule);
        }
    }

    /**
     * Ensure events exist up to upToDate. Called lazily when user views a far-future month.
     */
    @Transactional
    public void extendIfNeeded(UUID ruleId, LocalDate upToDate) {
        RecurringRule rule = ruleRepository.findById(ruleId).orElse(null);
        if (rule == null || rule.isDeleted()) return;

        LocalDate lastGenerated = eventRepository.findMaxDateByRecurringRuleId(ruleId)
                .orElse(rule.getStartDate().minusDays(1));

        if (!lastGenerated.isBefore(upToDate)) return;

        LocalDate generateFrom = nextOccurrenceAfter(rule, lastGenerated);
        LocalDate horizon = upToDate.plusMonths(1);

        Category category = rule.getCategory();
        generateEvents(rule, generateFrom, horizon, category);
    }

    // -- Private helpers ------------------------------------------------------

    private void generateEvents(RecurringRule rule, LocalDate from, LocalDate until, Category category) {
        List<LocalDate> dates = computeDates(rule, from, until);
        List<FinancialEvent> events = new ArrayList<>();
        for (LocalDate date : dates) {
            events.add(FinancialEvent.builder()
                    .date(date)
                    .category(category)
                    .type(rule.getEventType())
                    .plannedAmount(rule.getPlannedAmount())
                    .mandatory(rule.isMandatory())
                    .description(rule.getDescription())
                    .recurringRuleId(rule.getId())
                    .build());
        }
        eventRepository.saveAll(events);
    }

    private List<LocalDate> computeDates(RecurringRule rule, LocalDate from, LocalDate until) {
        List<LocalDate> dates = new ArrayList<>();
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            int dom = rule.getDayOfMonth();
            YearMonth ym = YearMonth.from(from);
            YearMonth last = YearMonth.from(until);
            while (!ym.isAfter(last)) {
                int clampedDay = Math.min(dom, ym.lengthOfMonth());
                LocalDate date = ym.atDay(clampedDay);
                if (!date.isBefore(from) && date.isBefore(until)) {
                    if (rule.getEndDate() == null || !date.isAfter(rule.getEndDate())) {
                        dates.add(date);
                    }
                }
                ym = ym.plusMonths(1);
            }
        } else { // WEEKLY
            LocalDate cursor = from;
            while (cursor.isBefore(until)) {
                if (cursor.getDayOfWeek() == rule.getDayOfWeek()) {
                    if (rule.getEndDate() == null || !cursor.isAfter(rule.getEndDate())) {
                        dates.add(cursor);
                    }
                }
                cursor = cursor.plusDays(1);
            }
        }
        return dates;
    }

    private LocalDate nextOccurrenceAfter(RecurringRule rule, LocalDate after) {
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            YearMonth next = YearMonth.from(after).plusMonths(1);
            int dom = Math.min(rule.getDayOfMonth(), next.lengthOfMonth());
            return next.atDay(dom);
        } else {
            LocalDate cursor = after.plusDays(1);
            while (cursor.getDayOfWeek() != rule.getDayOfWeek()) {
                cursor = cursor.plusDays(1);
            }
            return cursor;
        }
    }

    public RecurringRuleDto toDto(RecurringRule r) {
        return new RecurringRuleDto(
                r.getId(), r.getCategory().getId(), r.getCategory().getName(),
                r.getEventType(), r.getTargetFundId(), r.getPlannedAmount(),
                r.isMandatory(), r.getDescription(), r.getFrequency(),
                r.getDayOfMonth(), r.getDayOfWeek(), r.getStartDate(), r.getEndDate());
    }
}
