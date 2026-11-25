package ru.selfin.mcp;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static ru.selfin.mcp.SelfinClient.SandboxDay;
import static ru.selfin.mcp.SelfinClient.SandboxPocket;

final class ExpensePreview {
    private final SelfinClient selfin;

    ExpensePreview(SelfinClient selfin) {
        this.selfin = selfin;
    }

    Map<String, Object> preview(Request request) throws IOException, InterruptedException {
        BigDecimal amount = PlanTools.amountOf(request.amount());
        LocalDate today = selfin.today();
        LocalDate date = purchaseDate(request.date(), today);
        LocalDate explicitEnd = request.horizonEndDate();
        LocalDate maxEnd = YearMonth.from(today).plusMonths(36).atEndOfMonth();
        if (date.isAfter(maxEnd) || (explicitEnd != null && explicitEnd.isAfter(maxEnd))) {
            throw new IllegalArgumentException("Selfin считает до " + maxEnd + ". Выбери дату в пределах этого периода.");
        }
        if (explicitEnd != null && !explicitEnd.isAfter(today)) {
            throw new IllegalArgumentException("Конец периода примерки должен быть после сегодняшней даты Selfin.");
        }

        var initial = compare(today, date, amount, explicitEnd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purchase", Map.of("amount", amount.toPlainString(), "date", date));
        result.put("currency", "RUB");
        result.put("requestedPeriod", initial);
        if ("outside_horizon".equals(initial.get("status"))
                && explicitEnd == null && !Boolean.TRUE.equals(request.keepHorizon())) {
            result.put("extendedPeriod", compare(today, date, amount, date));
        }
        return result;
    }

    private Map<String, Object> compare(LocalDate today, LocalDate date, BigDecimal amount, LocalDate end)
            throws IOException, InterruptedException {
        var calculation = selfin.previewExpense(end == null ? "NEXT_INCOME" : "DATE:" + end, amount, date);
        if (calculation == null || !usable(calculation.baseline(), today) || !usable(calculation.fitted(), today)) {
            throw new IOException("Missing Selfin comparison or calendar date changed");
        }
        var baseline = calculation.baseline();
        var fitted = calculation.fitted();
        var horizon = baseline.horizon();
        if (!horizon.equals(fitted.horizon()) || (end != null && !end.equals(horizon.endDate()))
                || !Objects.equals(baseline.checkpointDate(), fitted.checkpointDate())
                || horizon.endDate().isBefore(today)) {
            throw new IOException("Selfin returned different comparison periods");
        }

        boolean included = !date.isAfter(horizon.endDate());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", included ? "included" : "outside_horizon");
        view.put("from", today);
        view.put("horizon", Map.of("endDate", horizon.endDate(), "label", horizon.label(), "fallback", horizon.fallback()));
        if (baseline.checkpointDate() != null) {
            view.put("checkpointDate", baseline.checkpointDate());
        }
        view.put("baseline", summary(baseline));
        if (included) {
            view.put("withPurchase", summary(fitted));
            view.put("difference", fitted.pocket().subtract(baseline.pocket()).toPlainString());
            var beforeDay = dayAt(baseline, date);
            var afterDay = dayAt(fitted, date);
            if (beforeDay != null && afterDay != null) {
                view.put("purchaseDay", Map.of("date", date, "balanceBefore", beforeDay.balance().toPlainString(),
                        "balanceWithPurchase", afterDay.balance().toPlainString(),
                        "plannedIncome", beforeDay.income().toPlainString()));
            }
        }
        return view;
    }

    private static boolean usable(SandboxPocket pocket, LocalDate today) {
        return pocket != null && pocket.pocket() != null && pocket.horizon() != null
                && pocket.planHasExpectations() != null && pocket.trajectory() != null && !pocket.trajectory().isEmpty()
                && pocket.trajectory().getFirst() != null && today.equals(pocket.trajectory().getFirst().date());
    }

    private static Map<String, Object> summary(SandboxPocket pocket) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", pocket.pocket().toPlainString());
        summary.put("planHasExpectations", pocket.planHasExpectations());
        var min = pocket.minPoint();
        if (min != null && min.date() != null && min.balance() != null) {
            summary.put("minimum", Map.of("date", min.date(), "balance", min.balance().toPlainString()));
        }
        return summary;
    }

    private static SandboxDay dayAt(SandboxPocket pocket, LocalDate date) {
        return pocket.trajectory().stream()
                .filter(day -> day != null && date.equals(day.date()) && day.balance() != null && day.income() != null)
                .findFirst().orElse(null);
    }

    private static LocalDate purchaseDate(String value, LocalDate today) {
        if (value == null) {
            throw new IllegalArgumentException("Укажи день покупки.");
        }
        try {
            LocalDate date = switch (value) {
                case "tomorrow" -> today.plusDays(1);
                case "today" -> today;
                default -> LocalDate.parse(value);
            };
            if (!date.isAfter(today)) {
                throw new IllegalArgumentException("Примерка доступна начиная с " + today.plusDays(1)
                        + ". Укажи будущий день покупки.");
            }
            return date;
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Укажи дату покупки в формате ГГГГ-ММ-ДД.");
        }
    }

    record Request(BigDecimal amount, String date, LocalDate horizonEndDate, Boolean keepHorizon) {}
}
