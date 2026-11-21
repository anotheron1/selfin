package ru.selfin.backend.service;

import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Поиск по журналу (срез 3, п. 9, ANO-126): что находит строка поиска и чипы. Число — по сумме строки, слова — по
 * названию и категории; у перевода название — имя копилки, как в журнале. «ё» и «е» — одна буква: «пятёр» находит
 * «Пятерочку».
 *
 * @param words     слова поиска, каждое должно найтись; пусто — без условия
 * @param amount    сумма, если поиск — число; иначе {@code null}
 * @param factsOnly «Только записи» — строки с записанной суммой
 */
record EventSearch(List<String> words, BigDecimal amount, LocalDate from, LocalDate to, UUID categoryId,
                   Priority priority, boolean factsOnly) {

    private static final Pattern NUMBER = Pattern.compile("\\d+([.,]\\d+)?");

    /** Свежие первыми; в одном дне — позже введённая выше. */
    static final Comparator<FinancialEvent> NEWEST_FIRST = Comparator
            .comparing(FinancialEvent::getDate)
            .thenComparing(FinancialEvent::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FinancialEvent::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    static EventSearch of(String q, LocalDate from, LocalDate to, UUID categoryId, Priority priority, boolean factsOnly) {
        String text = q == null ? "" : q.strip();
        String digits = text.replaceAll("[\\s ₽]", "");
        if (NUMBER.matcher(digits).matches()) {
            return new EventSearch(List.of(), new BigDecimal(digits.replace(',', '.')), from, to, categoryId, priority, factsOnly);
        }
        List<String> words = Arrays.stream(normalize(text).split("\\s+")).filter(w -> !w.isEmpty()).toList();
        return new EventSearch(words, null, from, to, categoryId, priority, factsOnly);
    }

    /** Строка подходит; {@code fundName} — имя копилки перевода или {@code null}. */
    boolean test(FinancialEvent e, String fundName) {
        if (from != null && e.getDate().isBefore(from)) return false;
        if (to != null && e.getDate().isAfter(to)) return false;
        if (categoryId != null && !categoryId.equals(e.getCategory().getId())) return false;
        if (priority != null && priority != e.getPriority()) return false;
        if (factsOnly && e.getFactAmount() == null) return false;
        if (amount != null) {
            BigDecimal shown = amountOf(e);
            return shown != null && shown.abs().compareTo(amount) == 0;
        }
        String hay = normalize(String.join(" ",
                nullToEmpty(e.getDescription()), e.getCategory().getName(), nullToEmpty(fundName)));
        return words.stream().allMatch(hay::contains);
    }

    /** Сумма строки, как её показывает журнал: у записи — записанная, у плана — плановая. */
    static BigDecimal amountOf(FinancialEvent e) {
        return e.getFactAmount() != null ? e.getFactAmount() : e.getPlannedAmount();
    }

    /** Записанная сумма со знаком для свободных денег: доход — плюс, расход и перевод в копилку — минус. */
    static BigDecimal toFree(FinancialEvent e) {
        if (e.getFactAmount() == null) return BigDecimal.ZERO;
        return e.getType() == EventType.INCOME ? e.getFactAmount() : e.getFactAmount().negate();
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
