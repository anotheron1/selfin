package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Что находит поиск по журналу (срез 3, п. 9): слова, число, чипы, порядок, знак суммы. */
class EventSearchTest {

    private static final Category FOOD = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
    private static final Category CAFE = Category.builder().id(UUID.randomUUID()).name("Кафе и доставка").build();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    private static FinancialEvent fact(String name, Category c, String amount) {
        return FinancialEvent.builder().id(UUID.randomUUID()).date(DAY).category(c).type(EventType.EXPENSE)
                .priority(Priority.MEDIUM).description(name).factAmount(new BigDecimal(amount)).build();
    }

    private static FinancialEvent plan(String name, Category c, String amount) {
        return FinancialEvent.builder().id(UUID.randomUUID()).date(DAY).category(c).type(EventType.EXPENSE)
                .priority(Priority.HIGH).description(name).plannedAmount(new BigDecimal(amount)).build();
    }

    private static EventSearch q(String text) {
        return EventSearch.of(text, null, null, null, null, false);
    }

    @Test
    @DisplayName("слова — по названию и категории, без регистра, «ё» как «е», каждое слово должно найтись")
    void words() {
        FinancialEvent shop = fact("Пятерочка у дома", FOOD, "1240");
        assertThat(q("пятёр").test(shop, null)).isTrue();
        assertThat(q("ПРОДУКТ").test(shop, null)).isTrue();
        assertThat(q("пятёр продукты").test(shop, null)).isTrue();
        assertThat(q("пятёр кафе").test(shop, null)).isFalse();
        assertThat(q("лента").test(shop, null)).isFalse();
        assertThat(q("  ").test(shop, null)).isTrue();
    }

    @Test
    @DisplayName("без названия — по категории; у перевода — по имени копилки")
    void categoryAndFund() {
        FinancialEvent noName = fact(null, CAFE, "300");
        assertThat(q("кафе").test(noName, null)).isTrue();
        assertThat(q("отпуск").test(noName, "Отпуск")).isTrue();
    }

    @Test
    @DisplayName("число — по сумме строки: у записи — записанная, у плана — плановая; пробелы и «₽» не мешают")
    void number() {
        assertThat(q("1 240").test(fact("Пятерочка", FOOD, "1240.00"), null)).isTrue();
        assertThat(q("1240 ₽").test(fact("Пятерочка", FOOD, "1240"), null)).isTrue();
        assertThat(q("1240,5").test(fact("Пятерочка", FOOD, "1240.50"), null)).isTrue();
        assertThat(q("1240").test(fact("Пятерочка", FOOD, "1241"), null)).isFalse();
        assertThat(q("25000").test(plan("Аренда", FOOD, "25000"), null)).isTrue();
        // Снятие из копилки — сумма с минусом, на экране без него.
        assertThat(q("500").test(fact(null, FOOD, "-500"), null)).isTrue();
        // Число в названии — не сумма: «1240» название «Чек 1240» не находит.
        assertThat(q("1240").test(fact("Чек 1240", FOOD, "99"), null)).isFalse();
    }

    @Test
    @DisplayName("чипы: даты включительно, категория, вид, «Только записи»")
    void chips() {
        FinancialEvent shop = fact("Пятерочка", FOOD, "100");
        assertThat(EventSearch.of("", DAY, DAY, null, null, false).test(shop, null)).isTrue();
        assertThat(EventSearch.of("", DAY.plusDays(1), null, null, null, false).test(shop, null)).isFalse();
        assertThat(EventSearch.of("", null, DAY.minusDays(1), null, null, false).test(shop, null)).isFalse();
        assertThat(EventSearch.of("", null, null, FOOD.getId(), null, false).test(shop, null)).isTrue();
        assertThat(EventSearch.of("", null, null, CAFE.getId(), null, false).test(shop, null)).isFalse();
        assertThat(EventSearch.of("", null, null, null, Priority.MEDIUM, false).test(shop, null)).isTrue();
        assertThat(EventSearch.of("", null, null, null, Priority.LOW, false).test(shop, null)).isFalse();
        assertThat(EventSearch.of("", null, null, null, null, true).test(shop, null)).isTrue();
        assertThat(EventSearch.of("", null, null, null, null, true).test(plan("Аренда", FOOD, "1"), null)).isFalse();
    }

    @Test
    @DisplayName("свежие первыми; в одном дне — позже введённая выше")
    void order() {
        FinancialEvent old = fact("a", FOOD, "1");
        old.setDate(DAY.minusDays(3));
        FinancialEvent early = fact("b", FOOD, "1");
        early.setCreatedAt(LocalDateTime.of(2026, 11, 12, 9, 0));
        FinancialEvent late = fact("c", FOOD, "1");
        late.setCreatedAt(LocalDateTime.of(2026, 11, 12, 18, 0));
        assertThat(List.of(old, early, late).stream().sorted(EventSearch.NEWEST_FIRST).toList())
                .containsExactly(late, early, old);
    }

    @Test
    @DisplayName("записанное — со знаком для свободных: доход плюс, расход минус, у плана без записи — ноль")
    void toFree() {
        FinancialEvent salary = fact("Зарплата", FOOD, "50000");
        salary.setType(EventType.INCOME);
        assertThat(EventSearch.toFree(salary)).isEqualByComparingTo("50000");
        assertThat(EventSearch.toFree(fact("Пятерочка", FOOD, "1240"))).isEqualByComparingTo("-1240");
        assertThat(EventSearch.toFree(plan("Аренда", FOOD, "25000"))).isEqualByComparingTo("0");
    }
}
