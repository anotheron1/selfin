package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private FinancialEventRepository eventRepository;
    private AnalyticsService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        service = new AnalyticsService(eventRepository);
    }

    // ─── buildPlanFact sort (via getReport) ───────────────────────────────────

    @Test
    @DisplayName("buildPlanFact: категории отсортированы по имени в русском алфавитном порядке")
    void planFact_categoriesSortedAlphabetically() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                expenseOn("Еда",    TODAY.withDayOfMonth(5), bd(10_000), bd(8_000)),
                expenseOn("Аренда", TODAY.withDayOfMonth(5), bd(30_000), bd(30_000)),
                expenseOn("Бензин", TODAY.withDayOfMonth(5), bd(5_000),  bd(3_300))
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        AnalyticsReportDto report = service.getReport(TODAY);

        List<String> names = report.planFact().categories().stream()
                .map(c -> c.categoryName())
                .toList();
        assertThat(names).containsExactly("Аренда", "Бензин", "Еда");
    }

    // ─── getMultiMonthReport sort ─────────────────────────────────────────────

    @Test
    @DisplayName("getMultiMonthReport: строки категорий отсортированы по имени в русском алфавитном порядке")
    void multiMonth_categoriesSortedAlphabetically() {
        LocalDate start = TODAY.withDayOfMonth(1);
        LocalDate end   = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate eventDate = TODAY.withDayOfMonth(5);

        List<FinancialEvent> events = List.of(
                expenseOn("Еда",    eventDate, bd(10_000), null),
                expenseOn("Аренда", eventDate, bd(30_000), null),
                expenseOn("Бензин", eventDate, bd(5_000),  null)
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(start, end))
                .thenReturn(events);

        MultiMonthReportDto report = service.getMultiMonthReport(start, end);

        List<String> categoryLabels = report.rows().stream()
                .filter(r -> r.type() == RowType.CATEGORY)
                .map(RowDto::label)
                .toList();
        assertThat(categoryLabels).containsExactly("Аренда", "Бензин", "Еда");
    }

    // ─── buildPriorityBreakdown ───────────────────────────────────────────────

    @Test
    void buildPriorityBreakdown_aggregatesByPriorityAndKind() {
        FinancialEvent highPlan = makeEvent(EventKind.PLAN, Priority.HIGH, CategoryType.EXPENSE,
                BigDecimal.valueOf(10000), null);
        FinancialEvent highFact = makeEvent(EventKind.FACT, Priority.HIGH, CategoryType.EXPENSE,
                null, BigDecimal.valueOf(9000));
        FinancialEvent medPlan = makeEvent(EventKind.PLAN, Priority.MEDIUM, CategoryType.EXPENSE,
                BigDecimal.valueOf(5000), null);
        FinancialEvent incFact = makeEvent(EventKind.FACT, Priority.MEDIUM, CategoryType.INCOME,
                null, BigDecimal.valueOf(80000));

        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(highPlan, highFact, medPlan, incFact));

        AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

        AnalyticsReportDto.PriorityBreakdown b = report.priorityBreakdown();
        assertThat(b.highPlanned()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(b.highFact()).isEqualByComparingTo(BigDecimal.valueOf(9000));
        assertThat(b.mediumPlanned()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(b.mediumFact()).isEqualByComparingTo(BigDecimal.ZERO); // income fact must not pollute expense buckets
        assertThat(b.totalIncomeFact()).isEqualByComparingTo(BigDecimal.valueOf(80000));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Факт-ребёнок, гасящий план на свою сумму (ANO-155). */
    private FinancialEvent factFor(FinancialEvent plan, LocalDate date, BigDecimal amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(plan.getCategory())
                .type(plan.getType())
                .eventKind(EventKind.FACT)
                .parentEventId(plan.getId())
                .factAmount(amount)
                .status(EventStatus.EXECUTED)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }

    private FinancialEvent makeEvent(EventKind kind, Priority priority, CategoryType catType,
            BigDecimal planned, BigDecimal fact) {
        EventType evtType = catType == CategoryType.INCOME ? EventType.INCOME : EventType.EXPENSE;
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name("Test")
                .type(catType)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 4, 5))
                .category(cat)
                .type(evtType)
                .eventKind(kind)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(priority)
                .deleted(false)
                .build();
    }

    private FinancialEvent expenseOn(String categoryName, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name(categoryName)
                .type(CategoryType.EXPENSE)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(cat)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }

    private FinancialEvent incomeOn(String categoryName, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name(categoryName)
                .type(CategoryType.INCOME)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(cat)
                .type(EventType.INCOME)
                .eventKind(EventKind.PLAN)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
