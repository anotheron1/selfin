package ru.selfin.mcp;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static ru.selfin.mcp.SelfinClient.Category;
import static ru.selfin.mcp.SelfinClient.PlanEvent;
import static ru.selfin.mcp.SelfinClient.PlanInput;
import static ru.selfin.mcp.SelfinClient.Recurring;

final class PlanTools {

    private static final Set<String> TYPES = Set.of("EXPENSE", "INCOME");
    // Last date in the API's YYYY-MM-DD format.
    private static final LocalDate LAST_JOURNAL_DATE = LocalDate.of(9999, 12, 31);
    static final Map<String, String> CHARACTERS = Map.of(
            "HIGH", "бронь", "MEDIUM", "ожидание", "LOW", "хотелка");

    private final SelfinClient selfin;
    private final Map<UUID, PlanSource> sources = new ConcurrentHashMap<>();
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    private final Object writes = new Object();

    PlanTools(SelfinClient selfin) {
        this.selfin = selfin;
    }

    Map<String, Object> context(ContextQuery query) throws IOException, InterruptedException {
        String name = nameOf(query.name());
        String type = query.type();
        if (type != null && !TYPES.contains(type)) {
            throw new IllegalArgumentException("Выбери расход или доход.");
        }
        if (query.month() == null) {
            throw new IllegalArgumentException("Укажи месяц плана.");
        }

        LocalDate today = selfin.today();
        var categories = selfin.categories().stream()
                .filter(PlanTools::planCategory)
                .filter(c -> type == null || type.equals(c.type()))
                .map(c -> Map.of("id", c.id(), "name", c.name(), "type", c.type(), "character", c.priority()))
                .toList();
        var existing = selfin.events(query.month().atDay(1), query.month().atEndOfMonth()).stream()
                .filter(e -> currentPlan(e, type) && hasName(e, name))
                .map(PlanTools::eventView)
                .toList();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("today", today);
        context.put("categories", categories);
        context.put("existing", existing);
        if (!Boolean.TRUE.equals(query.includeHistory())) {
            return context;
        }

        LocalDate end = query.month().atDay(1).minusDays(1);
        if (end.isAfter(today)) {
            end = today;
        }
        LocalDate start = YearMonth.from(end).minusMonths(2).atDay(1);
        var priorPlans = selfin.events(start, end).stream()
                .filter(e -> currentPlan(e, type) && e.plannedAmount() != null && hasName(e, name))
                .toList();
        var facts = selfin.facts(start, end, name, null);
        var priorFacts = facts.rows().stream()
                .filter(e -> "FACT".equals(e.eventKind()) && e.factAmount() != null
                        && TYPES.contains(e.type()) && (type == null || type.equals(e.type())))
                .toList();
        priorPlans.forEach(event -> sources.putIfAbsent(event.id(), new PlanSource(event, false)));
        priorFacts.forEach(event -> sources.put(event.id(), new PlanSource(event, true)));
        context.put("history", Map.of("from", start, "to", end,
                "plans", priorPlans.stream().map(PlanTools::eventView).toList(),
                "facts", priorFacts.stream().map(PlanTools::eventView).toList(),
                "factsTruncated", facts.total() > facts.rows().size()));
        return context;
    }

    Map<String, Object> monthContext(MonthPlanContext.Query query) throws IOException, InterruptedException {
        return new MonthPlanContext(selfin).read(query,
                fact -> sources.put(fact.id(), new PlanSource(fact, true)));
    }

    Map<String, Object> prepare(List<RowInput> inputs) throws IOException, InterruptedException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Добавь хотя бы одну строку плана.");
        }
        LocalDate today = selfin.today();
        var categories = categoriesById();
        Map<DateRange, List<PlanEvent>> periods = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (RowInput input : inputs) {
            if (input == null) {
                throw new IllegalArgumentException("В списке есть пустая строка.");
            }
            var category = categories.get(input.categoryId());
            var plan = planOf(input, category, today);
            boolean separatePayment = Boolean.TRUE.equals(input.separatePayment());
            if (!separatePayment && rows.stream().anyMatch(row -> row.plan.equals(plan))) {
                throw new IllegalArgumentException("Две строки совпадают. Уточни, это отдельные платежи или одна запись.");
            }
            PlanSource source = input.sourceId() == null ? null : sources.get(input.sourceId());
            if (input.sourceId() != null && (source == null || !plan.type().equals(source.event().type())
                    || !plan.categoryId().equals(source.event().categoryId())
                    || !source.event().date().isBefore(plan.date()))) {
                throw new IllegalArgumentException("Подтверждение источника требует подходящей записи из истории.");
            }

            var matches = matches(plan, periodEvents(periods, plan));
            String display = lineOf(plan, category, source);
            if (!separatePayment) {
                var sameRequest = rows.stream()
                        .filter(row -> !row.separatePayment && overlapping(row.plan, plan))
                        .map(row -> row.display)
                        .toList();
                if (!matches.isEmpty() || !sameRequest.isEmpty()) {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("proposed", display);
                    conflict.put("existing", conflictViews(matches));
                    if (!sameRequest.isEmpty()) {
                        conflict.put("sameRequest", sameRequest);
                    }
                    conflicts.add(conflict);
                }
            }
            rows.add(new Row(plan, category, display, matches, separatePayment));
        }
        if (!conflicts.isEmpty()) {
            return Map.of("status", "clarify", "conflicts", conflicts);
        }

        var draft = new Draft(List.copyOf(rows));
        UUID id = UUID.randomUUID();
        drafts.put(id, draft);
        return Map.of("status", "prepared", "draftId", id, "lines", draft.lines());
    }

    Map<String, Object> save(UUID draftId, Confirmation confirmation) throws IOException, InterruptedException {
        var draft = draftId == null ? null : drafts.get(draftId);
        if (draft == null) {
            throw new IllegalArgumentException("Список недоступен. Сначала подготовь его и сверь журнал.");
        }

        synchronized (draft) {
            if (draft.closed != null) {
                return Map.of("status", draft.closed);
            }
            if (!draft.approved) {
                if (!confirmation.confirm(draft.lines())) {
                    draft.closed = "declined";
                    return Map.of("status", draft.closed);
                }
                draft.approved = true;
            }
            synchronized (writes) {
                boolean firstSave = !draft.started;
                if (firstSave) {
                    if (!stillCurrent(draft.rows)) {
                        draft.closed = "stale";
                        return Map.of("status", draft.closed,
                                "message", "Данные Selfin изменились. Подготовь список заново для подтверждения.");
                    }
                    draft.started = true;
                }
                LocalDate retryDate = !firstSave && draft.rows.stream().anyMatch(Row::pending) ? selfin.today() : null;
                Map<UUID, Category> retryCategories = null;
                Map<DateRange, List<PlanEvent>> retryPeriods = new HashMap<>();
                for (Row row : draft.rows) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (row.outcome.equals("unknown")) {
                        if (retryDate != null && row.plan.date().isBefore(retryDate)) {
                            row.message = "Дата плана уже прошла. Проверь исход в журнале Selfin.";
                        } else {
                            saveRow(row);
                        }
                    } else if (row.outcome.equals("not_sent")) {
                        if (!firstSave) {
                            if (retryCategories == null) {
                                retryCategories = categoriesById();
                            }
                            if (!unsentRowIsCurrent(row, draft.rows, retryDate, retryCategories, retryPeriods)) {
                                row.outcome = "stale";
                                row.message = "Данные Selfin изменились. Подготовь эту строку для нового подтверждения.";
                                continue;
                            }
                        }
                        saveRow(row);
                    }
                }
            }
            return Map.of("status", "processed", "rows", draft.rows.stream().map(Row::receipt).toList());
        }
    }

    private boolean stillCurrent(List<Row> rows) throws IOException, InterruptedException {
        LocalDate today = selfin.today();
        var categories = categoriesById();
        Map<DateRange, List<PlanEvent>> periods = new HashMap<>();
        for (Row row : rows) {
            PlanInput plan = row.plan;
            if (plan.date().isBefore(today) || !row.category.equals(categories.get(plan.categoryId()))
                    || !row.matches.equals(matches(plan, periodEvents(periods, plan)))) {
                return false;
            }
        }
        return true;
    }

    private boolean unsentRowIsCurrent(Row row, List<Row> rows, LocalDate today,
                                       Map<UUID, Category> categories, Map<DateRange, List<PlanEvent>> periods)
            throws IOException, InterruptedException {
        PlanInput plan = row.plan;
        if (plan.date().isBefore(today) || !row.category.equals(categories.get(plan.categoryId()))) {
            return false;
        }
        // An unchanged occurrence saved by this confirmation is an expected change to the journal.
        var current = matches(plan, periodEvents(periods, plan)).stream()
                .filter(event -> rows.stream().noneMatch(saved -> unchangedSavedOccurrence(saved, event)))
                .toList();
        return row.matches.equals(current);
    }

    private static boolean unchangedSavedOccurrence(Row saved, PlanEvent event) {
        PlanInput plan = saved.plan;
        boolean fromRow = plan.recurring() == null ? event.id().equals(saved.eventId)
                : saved.recurringRuleId != null && saved.recurringRuleId.equals(event.recurringRuleId());
        return fromRow && samePaymentDetails(plan, event)
                && event.date().equals(occurrence(plan, YearMonth.from(event.date()), scheduleEnd(plan)));
    }

    private void saveRow(Row row) {
        try {
            PlanEvent saved = selfin.createPlan(row.key, row.plan);
            if (saved != null && saved.id() != null && row.plan.date().equals(saved.date())
                    && samePaymentDetails(row.plan, saved)) {
                row.eventId = saved.id();
                row.recurringRuleId = saved.recurringRuleId();
                row.outcome = "saved";
                return;
            }
            row.outcome = "unknown";
            row.message = "Selfin вернул ответ с другими реквизитами. Проверь исход той же попытки.";
        } catch (SelfinClient.ApiException error) {
            switch (error.status()) {
                case 400, 401, 403, 404, 422 -> {
                    // A request denied on replay does not resolve a previously lost response.
                    if (!row.outcome.equals("unknown")) {
                        row.outcome = "rejected";
                    }
                    row.message = error.accessDenied()
                            ? "Selfin не предоставил доступ. Проверь вход тестового пользователя."
                            : "Selfin отклонил запрос записи (HTTP " + error.status() + ").";
                }
                default -> {
                    row.outcome = "unknown";
                    row.message = "Исход записи пока неясен (HTTP " + error.status() + ").";
                }
            }
        } catch (IOException error) {
            row.outcome = "unknown";
            row.message = "Ответ Selfin потерялся. Проверь исход той же попытки.";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            row.outcome = "unknown";
            row.message = "Запрос прервался. Проверь исход той же попытки.";
        }
    }

    private Map<UUID, Category> categoriesById() throws IOException, InterruptedException {
        Map<UUID, Category> categories = new HashMap<>();
        selfin.categories().stream().filter(PlanTools::planCategory)
                .forEach(c -> categories.put(c.id(), c));
        return categories;
    }

    private List<PlanEvent> periodEvents(Map<DateRange, List<PlanEvent>> periods, PlanInput plan)
            throws IOException, InterruptedException {
        YearMonth month = YearMonth.from(plan.date());
        // The service can already contain future payments beyond its rolling recurrence horizon.
        LocalDate end = plan.recurring() == null ? month.atEndOfMonth() : scheduleEnd(plan);
        DateRange period = new DateRange(month.atDay(1), end);
        var events = periods.get(period);
        if (events == null) {
            events = selfin.events(period.start(), period.end());
            periods.put(period, events);
        }
        return events;
    }

    private static PlanInput planOf(RowInput input, Category category, LocalDate today) {
        String name = nameOf(input.name());
        if (input.date() == null || input.date().isBefore(today)) {
            throw new IllegalArgumentException("Для плана укажи дату на сегодня или позже.");
        }
        if (category == null || input.type() == null || !input.type().equals(category.type())) {
            throw new IllegalArgumentException("Выбери действующую категорию нужного направления.");
        }
        BigDecimal amount = amountOf(input.amount());
        String character = input.character() == null ? category.priority() : input.character();
        if (character == null || !CHARACTERS.containsKey(character)) {
            throw new IllegalArgumentException("Выбери характер: бронь, ожидание или хотелка.");
        }
        return new PlanInput(input.date(), category.id(), category.type(),
                amount, character, name,
                recurringOf(input.monthly(), input.date(), today));
    }

    private static Recurring recurringOf(MonthlyRepeat monthly, LocalDate firstDate, LocalDate today) {
        if (monthly == null) {
            return null;
        }
        Integer day = monthly.dayOfMonth();
        if (day == null || day < 1 || day > 31) {
            throw new IllegalArgumentException("Укажи день ежемесячного повтора от 1 до 31.");
        }
        if (!fallsOn(firstDate, day)) {
            throw new IllegalArgumentException("Дата первого платежа не соответствует дню ежемесячного повтора.");
        }
        var recurring = new Recurring("MONTHLY", day, firstDate, monthly.endDate());
        if (firstDate.isAfter(lastMaterializedDate(recurring, today))) {
            throw new IllegalArgumentException(monthly.endDate() == null
                    ? "Для бессрочной серии выбери первый платёж в ближайшие 36 месяцев по календарю Selfin."
                    : "Конец серии должен быть не раньше первого платежа.");
        }
        return recurring;
    }

    private static LocalDate lastMaterializedDate(Recurring recurring, LocalDate today) {
        return recurring.endDate() == null ? today.plusMonths(36) : recurring.endDate();
    }

    private static boolean fallsOn(LocalDate date, int day) {
        return date.getDayOfMonth() == Math.min(day, YearMonth.from(date).lengthOfMonth());
    }

    private static boolean overlapping(PlanInput left, PlanInput right) {
        if (!left.type().equals(right.type())) {
            return false;
        }
        String leftName = normalize(left.description());
        boolean sameName = leftName.isEmpty() ? left.description().equalsIgnoreCase(right.description())
                : leftName.equals(normalize(right.description()));
        if (!sameName && (!left.categoryId().equals(right.categoryId())
                || left.plannedAmount().compareTo(right.plannedAmount()) != 0)) {
            return false;
        }
        YearMonth first = YearMonth.from(left.date().isAfter(right.date()) ? left.date() : right.date());
        LocalDate leftEnd = scheduleEnd(left);
        LocalDate rightEnd = scheduleEnd(right);
        YearMonth last = YearMonth.from(leftEnd.isBefore(rightEnd) ? leftEnd : rightEnd);
        // Different days can coincide only when both fall on the last day of a short month.
        if (!sameName && left.recurring() != null && right.recurring() != null
                && left.recurring().dayOfMonth() != right.recurring().dayOfMonth()
                && Math.min(left.recurring().dayOfMonth(), right.recurring().dayOfMonth()) < 28) {
            return false;
        }
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            var leftDate = occurrence(left, month, leftEnd);
            var rightDate = occurrence(right, month, rightEnd);
            if (leftDate != null && rightDate != null && (sameName || leftDate.equals(rightDate))) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate scheduleEnd(PlanInput plan) {
        return plan.recurring() == null ? plan.date()
                : Objects.requireNonNullElse(plan.recurring().endDate(), LAST_JOURNAL_DATE);
    }

    private static LocalDate occurrence(PlanInput plan, YearMonth month, LocalDate end) {
        if (plan.recurring() == null) {
            return month.equals(YearMonth.from(plan.date())) ? plan.date() : null;
        }
        LocalDate date = month.atDay(Math.min(plan.recurring().dayOfMonth(), month.lengthOfMonth()));
        return !date.isBefore(plan.date()) && !date.isAfter(end) ? date : null;
    }

    private static boolean samePaymentDetails(PlanInput plan, PlanEvent event) {
        return "PLAN".equals(event.eventKind()) && event.plannedAmount() != null
                && plan.categoryId().equals(event.categoryId()) && plan.type().equals(event.type())
                && plan.priority().equals(event.priority()) && plan.description().equals(event.description())
                && plan.plannedAmount().compareTo(event.plannedAmount()) == 0
                && sameRecurrence(plan.recurring(), event);
    }

    private static boolean sameRecurrence(Recurring recurring, PlanEvent event) {
        if (recurring == null) {
            return event.recurringRuleId() == null;
        }
        return event.recurringRuleId() != null && recurring.frequency().equals(event.recurringFrequency())
                && Objects.equals(recurring.dayOfMonth(), event.recurringDayOfMonth())
                && event.recurringMonthOfYear() == null
                && recurring.startDate().equals(event.recurringStartDate())
                && Objects.equals(recurring.endDate(), event.recurringEndDate());
    }

    static BigDecimal amountOf(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("100000000000000000")) >= 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Сумма должна быть положительной и с точностью до копеек.");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    static String nameOf(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                        || Character.getType(c) == Character.LINE_SEPARATOR
                        || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("Укажи название платежа одной строкой, до 255 символов.");
        }
        return value.strip();
    }

    static boolean planCategory(Category c) {
        return c.type() != null && TYPES.contains(c.type()) && !"FUND_TRANSFER".equals(c.systemKind());
    }

    static boolean currentPlan(PlanEvent event, String type) {
        return "PLAN".equals(event.eventKind()) && event.type() != null && TYPES.contains(event.type())
                && (type == null || type.equals(event.type()));
    }

    private static List<PlanEvent> matches(PlanInput plan, List<PlanEvent> events) {
        return events.stream().filter(e -> currentPlan(e, plan.type()))
                .filter(e -> hasName(e, plan.description()) || (plan.categoryId().equals(e.categoryId())
                        && (plan.recurring() == null ? plan.date().equals(e.date())
                                : fallsOn(e.date(), plan.recurring().dayOfMonth()))
                        && e.plannedAmount() != null
                        && plan.plannedAmount().compareTo(e.plannedAmount()) == 0))
                .sorted(Comparator.comparing(PlanEvent::date).thenComparing(PlanEvent::id))
                .toList();
    }

    private static List<Map<String, Object>> conflictViews(List<PlanEvent> matches) {
        Map<UUID, Map<String, Object>> displayed = new LinkedHashMap<>();
        for (PlanEvent event : matches) {
            UUID id = event.recurringRuleId() == null ? event.id() : event.recurringRuleId();
            displayed.putIfAbsent(id, eventView(event));
        }
        return List.copyOf(displayed.values());
    }

    private static boolean hasName(PlanEvent event, String name) {
        String target = normalize(name);
        if (target.isEmpty()) {
            return name.equalsIgnoreCase(event.description()) || name.equalsIgnoreCase(event.categoryName());
        }
        String searchable = normalize(event.description()) + " " + normalize(event.categoryName());
        return Arrays.stream(target.split(" ")).allMatch(searchable::contains);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static Map<String, Object> eventView(PlanEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", event.id());
        view.put("name", displayName(event));
        view.put("date", event.date());
        view.put("type", event.type());
        view.put("categoryId", event.categoryId());
        view.put("kind", event.eventKind());
        if (event.recurringRuleId() != null) {
            view.put("recurringRuleId", event.recurringRuleId());
            view.put("recurringFrequency", event.recurringFrequency());
            view.put("recurringDayOfMonth", event.recurringDayOfMonth());
            view.put("recurringMonthOfYear", event.recurringMonthOfYear());
            view.put("recurringStartDate", event.recurringStartDate());
            view.put("recurringEndDate", event.recurringEndDate());
        }
        BigDecimal amount = "FACT".equals(event.eventKind()) ? event.factAmount() : event.plannedAmount();
        if (amount != null) {
            view.put("amount", amount.toPlainString());
        }
        return view;
    }

    private static String lineOf(PlanInput plan, Category category, PlanSource source) {
        Recurring recurring = plan.recurring();
        String schedule = recurring == null ? "₽ · " + plan.date()
                : "₽ за платёж · каждый месяц, %d-е число · первый платёж %s · %s".formatted(
                        recurring.dayOfMonth(), plan.date(), recurring.endDate() == null
                                ? "без конца" : "до " + recurring.endDate() + " включительно");
        String line = "%s · %s · %s %s · %s · %s".formatted(
                plan.description(), "EXPENSE".equals(plan.type()) ? "расход" : "доход",
                plan.plannedAmount().toPlainString(), schedule,
                oneLine(category.name()), CHARACTERS.get(plan.priority()));
        if (source == null) {
            return line;
        }
        var event = source.event();
        String name = displayName(event);
        return line + " (источник при подготовке: %s «%s», %s ₽, %s)".formatted(
                source.fact() ? "факт" : "план", oneLine(name),
                (source.fact() ? event.factAmount() : event.plannedAmount()).toPlainString(), event.date());
    }

    static String oneLine(String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+", " ");
    }

    static String displayName(PlanEvent event) {
        return event.description() == null || event.description().isBlank() ? event.categoryName() : event.description();
    }

    record ContextQuery(String name, String type, YearMonth month, Boolean includeHistory) {}

    record RowInput(String name, String type, BigDecimal amount, LocalDate date, UUID categoryId,
                    String character, Boolean separatePayment, UUID sourceId, MonthlyRepeat monthly) {}

    record MonthlyRepeat(Integer dayOfMonth, LocalDate endDate) {}

    private record PlanSource(PlanEvent event, boolean fact) {}

    private record DateRange(LocalDate start, LocalDate end) {}

    @FunctionalInterface
    interface Confirmation {
        boolean confirm(List<String> lines);
    }

    private static final class Draft {
        private final List<Row> rows;
        private boolean approved;
        private boolean started;
        private String closed;

        private Draft(List<Row> rows) {
            this.rows = rows;
        }

        private List<String> lines() {
            return rows.stream().map(row -> row.display).toList();
        }
    }

    private static final class Row {
        private final PlanInput plan;
        private final Category category;
        private final String display;
        private final List<PlanEvent> matches;
        private final boolean separatePayment;
        private final UUID key = UUID.randomUUID();
        private String outcome = "not_sent";
        private UUID eventId;
        private UUID recurringRuleId;
        private String message;

        private Row(PlanInput plan, Category category, String display, List<PlanEvent> matches, boolean separatePayment) {
            this.plan = plan;
            this.category = category;
            this.display = display;
            this.matches = matches;
            this.separatePayment = separatePayment;
        }

        private boolean pending() {
            return outcome.equals("unknown") || outcome.equals("not_sent");
        }

        private Map<String, Object> receipt() {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("line", display);
            receipt.put("status", outcome);
            if (eventId != null) {
                receipt.put("eventId", eventId);
                if (recurringRuleId != null) {
                    receipt.put("recurringRuleId", recurringRuleId);
                }
            } else if (message != null) {
                receipt.put("message", message);
            } else if (outcome.equals("not_sent")) {
                receipt.put("message", "Запрос этой строки не отправлен. Повтори сохранение того же списка.");
            }
            return receipt;
        }
    }
}
