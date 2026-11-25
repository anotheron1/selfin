package ru.selfin.mcp;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static ru.selfin.mcp.SelfinClient.PlanEvent;

final class MonthPlanContext {
    private static final Comparator<PlanEvent> LARGEST_EXPENSE_FIRST = Comparator
            .comparing(PlanEvent::factAmount).reversed()
            .thenComparing(PlanEvent::date).thenComparing(PlanEvent::id);

    private final SelfinClient selfin;

    MonthPlanContext(SelfinClient selfin) {
        this.selfin = selfin;
    }

    Map<String, Object> read(Query query, Consumer<PlanEvent> rememberFact) throws IOException, InterruptedException {
        if (query.month() == null) {
            throw new IllegalArgumentException("Укажи целевой месяц.");
        }
        String view = query.view() == null ? "both" : query.view();
        if (!Set.of("both", "plan", "history").contains(view)) {
            throw new IllegalArgumentException("Выбери план, историю или оба источника.");
        }
        if (view.equals("plan") && (query.historyFrom() != null || query.historyTo() != null)) {
            throw new IllegalArgumentException("Даты истории подходят для просмотра истории или обоих источников.");
        }
        LocalDate today = selfin.today();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", query.month().toString());
        result.put("today", today);
        if (!view.equals("history")) {
            var plans = selfin.events(query.month().atDay(1), query.month().atEndOfMonth()).stream()
                    .filter(e -> PlanTools.currentPlan(e, null) && e.plannedAmount() != null)
                    .filter(e -> matchesCategory(e, query.categoryId()))
                    .map(MonthPlanContext::planView)
                    .toList();
            result.put("plan", plans);
        }
        if (!view.equals("plan")) {
            result.put("history", history(query, today, rememberFact));
        }
        return result;
    }

    private Map<String, Object> history(Query query, LocalDate today, Consumer<PlanEvent> rememberFact)
            throws IOException, InterruptedException {
        var range = historyRange(query, today);
        var facts = RecordedExpenses.select(selfin.events(range.from(), range.to()), query.categoryId());
        var byMonth = facts.stream().collect(groupingBy(e -> YearMonth.from(e.date())));
        List<Map<String, Object>> months = new ArrayList<>();
        for (YearMonth month = YearMonth.from(range.from()); !month.atDay(1).isAfter(range.to());
                month = month.plusMonths(1)) {
            var start = month.atDay(1);
            var end = month.atEndOfMonth();
            months.add(Map.of(
                    "month", month.toString(),
                    "from", start.isBefore(range.from()) ? range.from() : start,
                    "to", end.isAfter(range.to()) ? range.to() : end,
                    "completeCalendarMonth", !start.isBefore(range.from()) && !end.isAfter(range.to()),
                    "categories", RecordedExpenses.categoryTotals(byMonth.getOrDefault(month, List.of()))));
        }

        boolean focused = query.historyFrom() != null || query.categoryId() != null;
        YearMonth priorYearMonth = query.month().minusYears(1);
        var candidates = (focused ? facts : byMonth.getOrDefault(priorYearMonth, List.of())).stream()
                .filter(e -> e.description() != null && !e.description().isBlank())
                .sorted(LARGEST_EXPENSE_FIRST)
                .toList();
        var selected = focused ? candidates.stream().limit(20).toList()
                : candidates.stream().collect(groupingBy(PlanEvent::categoryId, LinkedHashMap::new, toList()))
                        .values().stream().flatMap(rows -> rows.stream().limit(3)).toList();
        selected.forEach(rememberFact);
        var examples = selected.stream()
                .map(e -> Map.of("id", e.id(), "name", e.description(), "date", e.date(),
                        "categoryId", e.categoryId(), "categoryName", e.categoryName(),
                        "amount", e.factAmount().toPlainString()))
                .toList();
        return Map.of("from", range.from(), "to", range.to(), "months", months,
                "examples", examples, "examplesTruncated", candidates.size() > examples.size(),
                "examplesScope", focused ? "requestedHistory" : priorYearMonth.toString());
    }

    private static Map<String, Object> planView(PlanEvent event) {
        var view = PlanTools.eventView(event);
        boolean settled = event.linkedFactsCount() > 0
                ? event.linkedFactsAmount() != null && event.linkedFactsAmount().compareTo(event.plannedAmount()) >= 0
                : event.factAmount() != null || "EXECUTED".equals(event.status());
        view.put("settled", settled);
        view.put("character", event.priority());
        return view;
    }

    private static DateRange historyRange(Query query, LocalDate today) {
        if (query.historyFrom() == null && query.historyTo() == null) {
            YearMonth current = YearMonth.from(today);
            YearMonth cutoff = query.month().isBefore(current) ? query.month() : current;
            return new DateRange(cutoff.minusMonths(12).atDay(1), cutoff.atDay(1).minusDays(1));
        }
        if (query.historyFrom() == null || query.historyTo() == null) {
            throw new IllegalArgumentException("Укажи обе даты истории.");
        }
        var from = query.historyFrom();
        var to = query.historyTo().isAfter(today) ? today : query.historyTo();
        if (to.isBefore(from) || !to.isBefore(from.plusYears(1))) {
            throw new IllegalArgumentException("Выбери прошедший период истории длительностью до года.");
        }
        return new DateRange(from, to);
    }

    private static boolean matchesCategory(PlanEvent event, UUID categoryId) {
        return categoryId == null || categoryId.equals(event.categoryId());
    }

    record Query(YearMonth month, String view, LocalDate historyFrom, LocalDate historyTo, UUID categoryId) {}

    private record DateRange(LocalDate from, LocalDate to) {}
}
