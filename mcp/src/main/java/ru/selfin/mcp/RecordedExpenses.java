package ru.selfin.mcp;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static ru.selfin.mcp.SelfinClient.PlanEvent;

final class RecordedExpenses {
    private RecordedExpenses() {}

    static List<PlanEvent> select(List<PlanEvent> events, UUID categoryId) {
        Set<UUID> counted = new HashSet<>();
        return events.stream()
                .filter(e -> "EXPENSE".equals(e.type()) && e.factAmount() != null)
                .filter(e -> (categoryId == null || categoryId.equals(e.categoryId())) && counted.add(e.id()))
                .toList();
    }

    static BigDecimal total(List<PlanEvent> expenses) {
        return expenses.stream().map(PlanEvent::factAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static List<Map<String, Object>> categoryTotals(List<PlanEvent> expenses) {
        return expenses.stream().collect(groupingBy(PlanEvent::categoryId)).values().stream()
                .sorted(Comparator.comparing(rows -> rows.getFirst().categoryName()))
                .map(rows -> Map.<String, Object>of(
                        "categoryId", rows.getFirst().categoryId(), "categoryName", rows.getFirst().categoryName(),
                        "recordedExpense", total(rows).toPlainString(), "count", rows.size()))
                .toList();
    }
}
