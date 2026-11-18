package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.FinancialEventUpdateFactDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FactAggregateProjection;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Срез 3, PR 1 «Путь денег»: правка записи (п. 2, ANO-253) и «Больше не нужно» (п. 3). */
class MoneyPathServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    final FinancialEventRepository events = mock(FinancialEventRepository.class);
    final CategoryRepository categories = mock(CategoryRepository.class);
    final TargetFundService funds = mock(TargetFundService.class);
    final FinancialEventService service;

    final Category products = category("Продукты", CategoryType.EXPENSE);
    final Category cafe = category("Кафе", CategoryType.EXPENSE);

    {
        service = new FinancialEventService(events, categories, mock(TargetFundRepository.class),
                mock(CategoryService.class), Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()), mock(RecurringRuleService.class), mock(WishlistArtifactService.class));
        try {
            var field = FinancialEventService.class.getDeclaredField("targetFundService");
            field.setAccessible(true);
            field.set(service, funds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        when(events.findPlanRowsOfCategory(any(), any(), any(), any())).thenReturn(List.of());
        when(events.findFactAggregatesByPlanIds(anyList())).thenReturn(List.of());
    }

    static Category category(String name, CategoryType type) {
        return Category.builder().id(UUID.randomUUID()).name(name).type(type).priority(Priority.MEDIUM).build();
    }

    FinancialEvent plan(Category c, LocalDate date, Priority priority) {
        FinancialEvent p = FinancialEvent.builder().id(UUID.randomUUID()).eventKind(EventKind.PLAN).category(c)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("6000")).status(EventStatus.PLANNED)
                .priority(priority).date(date).build();
        when(events.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    FinancialEvent fact(Category c, LocalDate date, FinancialEvent parent) {
        FinancialEvent f = FinancialEvent.builder().id(UUID.randomUUID()).eventKind(EventKind.FACT).category(c)
                .type(EventType.EXPENSE).factAmount(new BigDecimal("1200")).status(EventStatus.EXECUTED)
                .priority(Priority.MEDIUM).date(date).idempotencyKey(UUID.randomUUID())
                .parentEventId(parent != null ? parent.getId() : null).build();
        when(events.findById(f.getId())).thenReturn(Optional.of(f));
        return f;
    }

    void rows(Category c, FinancialEvent... rows) {
        when(events.findPlanRowsOfCategory(eq(c.getId()), eq(EventType.EXPENSE), any(), any()))
                .thenReturn(List.of(rows));
    }

    static FactAggregateProjection agg(UUID planId, String total) {
        return new FactAggregateProjection() {
            public UUID getParentEventId() { return planId; }
            public Long getCount() { return 1L; }
            public BigDecimal getTotalAmount() { return new BigDecimal(total); }
        };
    }

    FinancialEventUpdateFactDto edit(LocalDate date, Category category, Priority priority) {
        return new FinancialEventUpdateFactDto(new BigDecimal("1200"), null, null, date,
                category != null ? category.getId() : null, priority);
    }

    void assertBadRequest(FinancialEvent f, FinancialEventUpdateFactDto dto) {
        assertThatThrownBy(() -> service.updateFact(f.getId(), dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        verify(events, never()).save(any());
    }

    // ── проверки ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("дата позже сегодня — 400")
    void futureDate_rejected() {
        assertBadRequest(fact(products, TODAY, null), edit(TODAY.plusDays(1), null, null));
    }

    @Test
    @DisplayName("категория удалена — 400")
    void deletedCategory_rejected() {
        Category gone = category("Старое", CategoryType.EXPENSE);
        gone.setDeleted(true);
        when(categories.findById(gone.getId())).thenReturn(Optional.of(gone));
        assertBadRequest(fact(products, TODAY, null), edit(null, gone, null));
    }

    @Test
    @DisplayName("категория другого типа — 400")
    void otherTypeCategory_rejected() {
        Category salary = category("Зарплата", CategoryType.INCOME);
        when(categories.findById(salary.getId())).thenReturn(Optional.of(salary));
        assertBadRequest(fact(products, TODAY, null), edit(null, salary, null));
    }

    @Test
    @DisplayName("запись к брони: смена категории — 400")
    void bookingFact_categoryChange_rejected() {
        when(categories.findById(cafe.getId())).thenReturn(Optional.of(cafe));
        FinancialEvent booking = plan(products, TODAY, Priority.HIGH);
        assertBadRequest(fact(products, TODAY, booking), edit(null, cafe, null));
    }

    @Test
    @DisplayName("перевод в копилку: смена категории — 400")
    void transfer_categoryChange_rejected() {
        when(categories.findById(cafe.getId())).thenReturn(Optional.of(cafe));
        FinancialEvent transfer = fact(products, TODAY, null);
        transfer.setType(EventType.FUND_TRANSFER);
        transfer.setTargetFundId(UUID.randomUUID());
        assertBadRequest(transfer, edit(null, cafe, null));
    }

    // ── переходы ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("новая дата — запись уходит к строке своей недели, обе строки пересчитаны")
    void dateChange_movesToOtherExpectation() {
        FinancialEvent thisWeek = plan(products, TODAY, Priority.MEDIUM);
        FinancialEvent lastWeek = plan(products, TODAY.minusDays(9), Priority.MEDIUM);
        rows(products, lastWeek, thisWeek);
        FinancialEvent f = fact(products, TODAY, thisWeek);

        service.updateFact(f.getId(), edit(TODAY.minusDays(7), null, null));

        assertThat(f.getDate()).isEqualTo(TODAY.minusDays(7));
        assertThat(f.getParentEventId()).isEqualTo(lastWeek.getId());
        verify(events).findFactAggregatesByPlanIds(List.of(thisWeek.getId()));
        verify(events).findFactAggregatesByPlanIds(List.of(lastWeek.getId()));
    }

    @Test
    @DisplayName("новая категория без ожидания — запись выходит из плана, прежняя строка пересчитана")
    void categoryChange_leavesPlan() {
        when(categories.findById(cafe.getId())).thenReturn(Optional.of(cafe));
        FinancialEvent row = plan(products, TODAY, Priority.MEDIUM);
        FinancialEvent f = fact(products, TODAY, row);

        service.updateFact(f.getId(), edit(null, cafe, null));

        assertThat(f.getCategory()).isSameAs(cafe);
        assertThat(f.getParentEventId()).isNull();
        verify(events).findFactAggregatesByPlanIds(List.of(row.getId()));
    }

    @Test
    @DisplayName("вид «Бронь» — запись выходит из ожидания")
    void priorityToHigh_leavesExpectation() {
        FinancialEvent row = plan(products, TODAY, Priority.MEDIUM);
        rows(products, row);
        FinancialEvent f = fact(products, TODAY, row);

        service.updateFact(f.getId(), edit(null, null, Priority.HIGH));

        assertThat(f.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(f.getParentEventId()).isNull();
    }

    @Test
    @DisplayName("строка, погашенная целиком только этой записью, её не теряет")
    void rowSettledOnlyByThisFact_keepsIt() {
        FinancialEvent row = plan(products, TODAY.minusDays(2), Priority.MEDIUM);
        row.setPlannedAmount(new BigDecimal("1200"));
        row.setStatus(EventStatus.EXECUTED);
        FinancialEvent later = plan(products, TODAY.plusDays(5), Priority.MEDIUM);
        rows(products, row, later);
        when(events.findFactAggregatesByPlanIds(List.of(row.getId(), later.getId())))
                .thenReturn(List.of(agg(row.getId(), "1200")));
        FinancialEvent f = fact(products, TODAY, row);

        service.updateFact(f.getId(), edit(TODAY.minusDays(1), null, null));

        assertThat(f.getParentEventId()).isEqualTo(row.getId());
    }

    @Test
    @DisplayName("запись к брони: новая дата — бронь держится, правило не зовётся")
    void bookingFact_dateChange_keepsBooking() {
        FinancialEvent booking = plan(products, TODAY, Priority.HIGH);
        FinancialEvent f = fact(products, TODAY, booking);

        service.updateFact(f.getId(), edit(TODAY.minusDays(3), products, null));

        assertThat(f.getDate()).isEqualTo(TODAY.minusDays(3));
        assertThat(f.getParentEventId()).isEqualTo(booking.getId());
        verify(events, never()).findPlanRowsOfCategory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("перевод в копилку: новая дата — движение копилки идёт за ней")
    void transfer_dateChange_movesFundMovement() {
        FinancialEvent transfer = fact(products, TODAY, null);
        transfer.setType(EventType.FUND_TRANSFER);
        UUID fund = UUID.randomUUID();
        transfer.setTargetFundId(fund);

        service.updateFact(transfer.getId(), edit(TODAY.minusDays(4), null, null));

        verify(funds).syncMovement(fund, transfer.getIdempotencyKey(), TODAY.minusDays(4), new BigDecimal("1200"));
        verify(events, never()).findPlanRowsOfCategory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("правка одной суммы старую запись к ожиданию не привязывает")
    void amountOnly_doesNotBind() {
        rows(products, plan(products, TODAY, Priority.MEDIUM));
        FinancialEvent f = fact(products, TODAY, null);

        service.updateFact(f.getId(), new FinancialEventUpdateFactDto(new BigDecimal("900"), null, null,
                TODAY, products.getId(), Priority.MEDIUM));

        assertThat(f.getParentEventId()).isNull();
    }

    // ── п. 1: запись без плана ───────────────────────────────────────────────

    @Test
    @DisplayName("запись на всю сумму ожидания исполняет строку")
    void standaloneFact_fullAmount_executesExpectation() {
        FinancialEvent row = plan(products, TODAY, Priority.MEDIUM);
        rows(products, row);
        when(categories.findById(products.getId())).thenReturn(Optional.of(products));
        when(events.findFactAggregatesByPlanIds(List.of(row.getId())))
                .thenReturn(List.of(), List.of(agg(row.getId(), "6000")));   // до записи — ничего, после — 6 000

        service.createStandaloneFact(UUID.randomUUID(), new ru.selfin.backend.dto.StandaloneFactCreateDto(
                TODAY, products.getId(), EventType.EXPENSE, new BigDecimal("6000"), null, Priority.MEDIUM, null));

        assertThat(row.getStatus()).isEqualTo(EventStatus.EXECUTED);
    }

    // ── п. 3: «Больше не нужно» — closePlan ──────────────────────────────────

    FinancialEvent booking(EventType type, Priority priority) {
        FinancialEvent p = plan(products, TODAY, priority);
        p.setType(type);
        p.setPlannedAmount(new BigDecimal("10000"));
        return p;
    }

    void pay(FinancialEvent plan, String amount, Boolean closePlan) {
        when(events.findFactAggregatesByPlanIds(List.of(plan.getId()))).thenReturn(List.of(agg(plan.getId(), amount)));
        service.createLinkedFact(plan.getId(), UUID.randomUUID(),
                new ru.selfin.backend.dto.FactCreateDto(TODAY, new BigDecimal(amount), null, null, null, closePlan));
    }

    @Test
    @DisplayName("бронь 10 000, записано 7 000 с «Больше не нужно» — сумма брони 7 000, исполнена")
    void closePlan_shrinksBookingToSettled() {
        FinancialEvent b = booking(EventType.EXPENSE, Priority.HIGH);
        pay(b, "7000", true);
        assertThat(b.getPlannedAmount()).isEqualByComparingTo("7000");
        assertThat(b.getStatus()).isEqualTo(EventStatus.EXECUTED);
    }

    @Test
    @DisplayName("плановый доход — так же")
    void closePlan_incomeBooking() {
        FinancialEvent b = booking(EventType.INCOME, Priority.HIGH);
        pay(b, "7000", true);
        assertThat(b.getPlannedAmount()).isEqualByComparingTo("7000");
        assertThat(b.getStatus()).isEqualTo(EventStatus.EXECUTED);
    }

    @Test
    @DisplayName("без признака — остаток держится бронью")
    void noClosePlan_keepsRemainder() {
        FinancialEvent b = booking(EventType.EXPENSE, Priority.HIGH);
        pay(b, "7000", false);
        assertThat(b.getPlannedAmount()).isEqualByComparingTo("10000");
        assertThat(b.getStatus()).isEqualTo(EventStatus.PLANNED);
    }

    @Test
    @DisplayName("записано больше брони — сумма брони прежняя, бронь исполнена")
    void closePlan_overpaid_keepsPlannedAmount() {
        FinancialEvent b = booking(EventType.EXPENSE, Priority.HIGH);
        pay(b, "12000", true);
        assertThat(b.getPlannedAmount()).isEqualByComparingTo("10000");
        assertThat(b.getStatus()).isEqualTo(EventStatus.EXECUTED);
    }

    @Test
    @DisplayName("у ожидания и хотелки — 400, ничего не записано")
    void closePlan_onExpectationOrWish_rejected() {
        for (Priority p : List.of(Priority.MEDIUM, Priority.LOW)) {
            FinancialEvent b = booking(EventType.EXPENSE, p);
            assertThatThrownBy(() -> pay(b, "7000", true))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
            assertThat(b.getPlannedAmount()).isEqualByComparingTo("10000");
        }
        verify(events, never()).save(any());
    }
}
