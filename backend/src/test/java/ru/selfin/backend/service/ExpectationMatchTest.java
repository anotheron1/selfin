package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Срез 3 п. 1: к какой строке ожидания встаёт запись без плана. */
class ExpectationMatchTest {

    static final Category PRODUCTS = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
    static final Category CAFE = Category.builder().id(UUID.randomUUID()).name("Кафе").build();
    /** Среда, 18 ноября 2026. */
    static final LocalDate REC = LocalDate.of(2026, 11, 18);

    static FinancialEvent row(LocalDate date) {
        return FinancialEvent.builder().id(UUID.randomUUID()).eventKind(EventKind.PLAN).date(date)
                .category(PRODUCTS).type(EventType.EXPENSE).priority(Priority.MEDIUM)
                .status(EventStatus.PLANNED).plannedAmount(new BigDecimal("6000")).build();
    }

    static Optional<FinancialEvent> pick(List<FinancialEvent> rows) {
        return pick(rows, Map.of(), Priority.MEDIUM);
    }

    static Optional<FinancialEvent> pick(List<FinancialEvent> rows, Map<UUID, BigDecimal> settled, Priority priority) {
        return ExpectationMatch.pick(REC, PRODUCTS.getId(), EventType.EXPENSE, priority, false, rows, settled);
    }

    @Test
    @DisplayName("срез 4 п. 1: запись «в кредит» к строке своей недели не встаёт")
    void onCredit_skipsExpectation() {
        FinancialEvent own = row(REC.minusDays(1));
        assertThat(pick(List.of(own))).as("без отметки встаёт").contains(own);
        assertThat(ExpectationMatch.pick(REC, PRODUCTS.getId(), EventType.EXPENSE, Priority.MEDIUM, true,
                List.of(own), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("своя неделя — последняя строка не позже записи")
    void ownWeek_latestRowNotAfterRecord() {
        FinancialEvent older = row(REC.minusDays(5));
        FinancialEvent own = row(REC.minusDays(1));
        assertThat(pick(List.of(older, own, row(REC.plusDays(3))))).contains(own);
    }

    @Test
    @DisplayName("строка в день записи — своя")
    void rowOnRecordDay_isOwn() {
        FinancialEvent own = row(REC);
        assertThat(pick(List.of(own, row(REC.plusDays(1))))).contains(own);
    }

    @Test
    @DisplayName("шестой день назад — встаёт, седьмой — нет")
    void sixDaysBack_bindsSeventhDoesNot() {
        FinancialEvent sixth = row(REC.minusDays(6));
        assertThat(pick(List.of(sixth))).contains(sixth);
        assertThat(pick(List.of(row(REC.minusDays(7))))).isEmpty();
    }

    @Test
    @DisplayName("иначе — ближайшая строка впереди в том же месяце")
    void otherwise_nearestRowAheadInMonth() {
        FinancialEvent near = row(REC.plusDays(2));
        assertThat(pick(List.of(row(REC.plusDays(9)), near, row(REC.minusDays(7))))).contains(near);
    }

    @Test
    @DisplayName("строка в следующем месяце — нет")
    void rowNextMonth_none() {
        assertThat(pick(List.of(row(LocalDate.of(2026, 12, 1))))).isEmpty();
    }

    @Test
    @DisplayName("разовое ожидание в конце месяца собирает траты до своей даты")
    void oneOffAtMonthEnd_collectsEarlierRecords() {
        FinancialEvent oneOff = row(LocalDate.of(2026, 11, 30));
        assertThat(ExpectationMatch.pick(LocalDate.of(2026, 11, 2), PRODUCTS.getId(), EventType.EXPENSE,
                Priority.MEDIUM, false, List.of(oneOff), Map.of())).contains(oneOff);
        assertThat(ExpectationMatch.pick(LocalDate.of(2026, 11, 30), PRODUCTS.getId(), EventType.EXPENSE,
                Priority.MEDIUM, false, List.of(oneOff), Map.of())).contains(oneOff);
    }

    @Test
    @DisplayName("запись с характером «Бронь» или «Хотелка» не встаёт")
    void highOrLowRecord_none() {
        List<FinancialEvent> rows = List.of(row(REC));
        assertThat(pick(rows, Map.of(), Priority.HIGH)).isEmpty();
        assertThat(pick(rows, Map.of(), Priority.LOW)).isEmpty();
    }

    @Test
    @DisplayName("строка-бронь и строка-хотелка — не кандидаты")
    void highOrLowRow_notCandidate() {
        FinancialEvent high = row(REC);
        high.setPriority(Priority.HIGH);
        FinancialEvent low = row(REC);
        low.setPriority(Priority.LOW);
        assertThat(pick(List.of(high, low))).isEmpty();
    }

    @Test
    @DisplayName("погашенная целиком строка — не кандидат, запись идёт к следующей")
    void fullySettledRow_notCandidate() {
        FinancialEvent settled = row(REC.minusDays(1));
        FinancialEvent next = row(REC.plusDays(5));
        assertThat(pick(List.of(settled, next), Map.of(settled.getId(), new BigDecimal("6000")), Priority.MEDIUM))
                .contains(next);
        assertThat(pick(List.of(settled, next), Map.of(settled.getId(), new BigDecimal("5999")), Priority.MEDIUM))
                .as("погашенная частью — ещё кандидат")
                .contains(settled);
    }

    @Test
    @DisplayName("другой тип, другая категория, удалённая строка, факт — нет")
    void otherTypeCategoryDeletedOrFact_none() {
        FinancialEvent income = row(REC);
        income.setType(EventType.INCOME);
        FinancialEvent cafe = row(REC);
        cafe.setCategory(CAFE);
        FinancialEvent deleted = row(REC);
        deleted.setDeleted(true);
        FinancialEvent fact = row(REC);
        fact.setEventKind(EventKind.FACT);
        assertThat(pick(List.of(income))).isEmpty();
        assertThat(pick(List.of(cafe))).isEmpty();
        assertThat(pick(List.of(deleted))).isEmpty();
        assertThat(pick(List.of(fact))).isEmpty();
    }

    @Test
    @DisplayName("доходная запись встаёт к доходному ожиданию")
    void incomeRecord_bindsIncomeRow() {
        FinancialEvent income = row(REC);
        income.setType(EventType.INCOME);
        assertThat(ExpectationMatch.pick(REC, PRODUCTS.getId(), EventType.INCOME, Priority.MEDIUM,
                false, List.of(row(REC), income), Map.of())).contains(income);
    }
}
