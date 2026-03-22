package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private AnalyticsService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        service = new AnalyticsService(eventRepository, checkpointRepository);
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

    // ─── calcStartBalance (P1 regression) ─────────────────────────────────────

    /**
     * Регрессия P1: если в мостике между чекпоинтом и началом месяца есть
     * неисполненное PLANNED-событие (factAmount = null), оно НЕ должно влиять
     * на начальный баланс аналитики. Только EXECUTED события меняют реальный баланс.
     */
    @Test
    @DisplayName("calcStartBalance: PLANNED событие в мостике (без factAmount) не влияет на стартовый баланс")
    void calcStartBalance_plannedBridgeEventIgnored() {
        LocalDate monthStart = TODAY.withDayOfMonth(1); // 2026-03-01
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate cpDate = LocalDate.of(2026, 2, 20);

        ru.selfin.backend.model.BalanceCheckpoint cp = ru.selfin.backend.model.BalanceCheckpoint.builder()
                .id(UUID.randomUUID())
                .date(cpDate)
                .amount(bd(40_000))
                .build();
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));

        // Мостик: одно EXECUTED (факт 20_000) + одно PLANNED (факт null, план 10_000)
        when(eventRepository.findAllByDeletedFalseAndDateBetween(cpDate, monthStart.minusDays(1)))
                .thenReturn(List.of(
                        incomeOn(LocalDate.of(2026, 2, 25), bd(20_000), bd(20_000)),  // исполненное
                        incomeOn(LocalDate.of(2026, 2, 28), bd(10_000), null)          // PLANNED — должно игнорироваться
                ));

        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(TODAY);

        // Стартовый баланс = 40_000 (чекпоинт) + 20_000 (EXECUTED) = 60_000.
        // PLANNED событие 28.02 (10_000) НЕ должно быть добавлено.
        // Нарастающий баланс на 1 марта (начало, до событий) = 60_000.
        // getReport возвращает cashFlow где initialBalance = 60_000.
        // Если нет событий в марте и сегодня 15.03 — в cashFlow будет одна запись (сегодня).
        ru.selfin.backend.dto.AnalyticsReportDto.CashFlowDay today = report.cashFlow().stream()
                .filter(d -> d.date().equals(TODAY))
                .findFirst()
                .orElseThrow();
        // running balance at today = 60_000 (no march events)
        assertThat(today.runningBalance()).isEqualByComparingTo(bd(60_000));
    }

    /**
     * Регрессия P1 — обратный случай: EXECUTED событие в мостике учитывается корректно.
     */
    @Test
    @DisplayName("calcStartBalance: EXECUTED событие в мостике корректно учитывается")
    void calcStartBalance_executedBridgeEventCounted() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate cpDate = LocalDate.of(2026, 2, 20);

        ru.selfin.backend.model.BalanceCheckpoint cp = ru.selfin.backend.model.BalanceCheckpoint.builder()
                .id(UUID.randomUUID())
                .date(cpDate)
                .amount(bd(30_000))
                .build();
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));

        when(eventRepository.findAllByDeletedFalseAndDateBetween(cpDate, monthStart.minusDays(1)))
                .thenReturn(List.of(
                        expenseOn("Аренда", LocalDate.of(2026, 2, 25), bd(5_000), bd(5_000)) // EXECUTED расход
                ));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(TODAY);

        // Стартовый баланс = 30_000 - 5_000 = 25_000
        ru.selfin.backend.dto.AnalyticsReportDto.CashFlowDay today = report.cashFlow().stream()
                .filter(d -> d.date().equals(TODAY))
                .findFirst()
                .orElseThrow();
        assertThat(today.runningBalance()).isEqualByComparingTo(bd(25_000));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private FinancialEvent incomeOn(LocalDate date, BigDecimal planned, BigDecimal fact) {
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name("Зарплата")
                .type(CategoryType.INCOME)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(cat)
                .type(EventType.INCOME)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(Priority.MEDIUM)
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
