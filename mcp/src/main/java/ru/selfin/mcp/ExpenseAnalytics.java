package ru.selfin.mcp;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static ru.selfin.mcp.SelfinClient.PlanEvent;

final class ExpenseAnalytics {
    private static final int ENTRY_LIMIT = 200;
    private static final String EXPENSE = "EXPENSE";
    private static final String INCOME = "INCOME";

    private final SelfinClient selfin;

    ExpenseAnalytics(SelfinClient selfin) {
        this.selfin = selfin;
    }

    Map<String, Object> search(Query query) throws IOException, InterruptedException {
        if ((query.from() == null) != (query.to() == null)) {
            throw new IllegalArgumentException("Укажи обе даты периода расходов.");
        }
        LocalDate today = selfin.today();
        LocalDate from = query.from() == null ? YearMonth.from(today).atDay(1) : query.from();
        LocalDate to = query.to() == null || query.to().isAfter(today) ? today : query.to();
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Выбери период до сегодняшней даты Selfin включительно.");
        }

        String text = query.text() == null || query.text().isBlank() ? "" : PlanTools.nameOf(query.text());
        var words = Arrays.stream(normalize(text).split("\\s+")).filter(word -> !word.isEmpty()).toList();
        var expenses = RecordedExpenses.select(selfin.events(from, to), query.categoryId()).stream()
                .filter(e -> matches(e, words))
                .toList();
        var byMonth = expenses.stream().collect(groupingBy(e -> YearMonth.from(e.date()), TreeMap::new, toList()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("today", today);
        result.put("completeCalendarMonths", from.getDayOfMonth() == 1
                && to.equals(YearMonth.from(to).atEndOfMonth()) && to.isBefore(today));
        result.put("currency", "RUB");
        result.put("count", expenses.size());
        result.put("recordedExpense", RecordedExpenses.total(expenses).toPlainString());
        if (!text.isEmpty()) {
            result.put("text", text);
        }
        if (query.categoryId() != null) {
            result.put("categoryId", query.categoryId());
        }
        result.put("categories", RecordedExpenses.categoryTotals(expenses));
        result.put("months", byMonth.entrySet().stream()
                .map(entry -> monthTotal(entry.getKey(), entry.getValue(), from, to, today))
                .toList());

        if (Boolean.TRUE.equals(query.includeEntries())) {
            // Selfin returns date, creation time and id in ascending order.
            var entries = expenses.reversed().stream()
                    .limit(ENTRY_LIMIT).map(ExpenseAnalytics::entryView).toList();
            result.put("entries", entries);
            result.put("entriesTruncated", expenses.size() > entries.size());
        }
        return result;
    }

    Map<String, Object> planFact(PlanFactQuery query) throws IOException, InterruptedException {
        YearMonth month = query.month();
        if (month == null) {
            throw new IllegalArgumentException("Укажи месяц для сравнения плана и записанного.");
        }
        String type = query.type();
        if (type != null && !INCOME.equals(type) && !EXPENSE.equals(type)) {
            throw new IllegalArgumentException("В отчёте можно сравнить расходы, доходы или оба направления.");
        }
        LocalDate today = selfin.today();
        var report = selfin.planFact(month.atDay(1));
        if (report.categories() == null) {
            throw new IOException("Missing Selfin category totals");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month.toString());
        result.put("today", today);
        result.put("from", month.atDay(1));
        result.put("planThrough", month.atEndOfMonth());
        result.put("completeCalendarMonth", month.isBefore(YearMonth.from(today)));
        result.put("currency", "RUB");
        if (type == null || EXPENSE.equals(type)) {
            result.put("expenses", comparison(report.totalPlannedExpense(), report.totalFactExpense()));
        }
        if (type == null || INCOME.equals(type)) {
            result.put("income", comparison(report.totalPlannedIncome(), report.totalFactIncome()));
        }
        var categories = report.categories().stream()
                .filter(row -> (INCOME.equals(row.type()) || EXPENSE.equals(row.type())) && (type == null || type.equals(row.type())))
                .toList();
        var rows = new ArrayList<Map<String, Object>>();
        for (var category : categories) {
            var row = comparison(category.planned(), category.fact());
            row.put("categoryName", category.categoryName());
            row.put("type", category.type());
            rows.add(row);
        }
        result.put("categories", rows);
        return result;
    }

    private static Map<String, Object> comparison(BigDecimal planned, BigDecimal recorded) throws IOException {
        if (planned == null || recorded == null) {
            throw new IOException("Missing Selfin report amounts");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planned", planned.toPlainString());
        result.put("recorded", recorded.toPlainString());
        result.put("difference", recorded.subtract(planned).toPlainString());
        return result;
    }

    private static Map<String, Object> monthTotal(YearMonth month, List<PlanEvent> entries,
                                                   LocalDate from, LocalDate to, LocalDate today) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return Map.of("month", month.toString(), "from", start.isBefore(from) ? from : start,
                "to", earlier(end, to), "completeCalendarMonth",
                !start.isBefore(from) && !end.isAfter(to) && end.isBefore(today),
                "count", entries.size(), "recordedExpense", RecordedExpenses.total(entries).toPlainString(),
                "categories", RecordedExpenses.categoryTotals(entries));
    }

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static boolean matches(PlanEvent event, List<String> words) {
        if (words.isEmpty()) {
            return true;
        }
        String searchable = normalize(Objects.toString(event.description(), "") + " " + event.categoryName());
        return words.stream().allMatch(searchable::contains);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static Map<String, Object> entryView(PlanEvent event) {
        String name = PlanTools.displayName(event);
        return Map.of("id", event.id(), "date", event.date(), "name", name, "categoryName", event.categoryName(),
                "categoryId", event.categoryId(), "amount", event.factAmount().toPlainString(), "onCredit", event.onCredit());
    }

    record Query(LocalDate from, LocalDate to, UUID categoryId, String text, Boolean includeEntries) {}

    record PlanFactQuery(YearMonth month, String type) {}
}
