package ru.selfin.mcp;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static ru.selfin.mcp.SelfinClient.Category;
import static ru.selfin.mcp.SelfinClient.FactEvent;
import static ru.selfin.mcp.SelfinClient.FactInput;
import static ru.selfin.mcp.SelfinClient.FactPreview;
import static ru.selfin.mcp.SelfinClient.PlanEvent;

final class FactTools {
    private static final String EXPENSE = "EXPENSE";

    private final SelfinClient selfin;
    private final Map<UUID, Draft> drafts;
    private final Object writes;

    FactTools(SelfinClient selfin) {
        this(selfin, new State());
    }

    FactTools(SelfinClient selfin, State state) {
        this.selfin = selfin;
        drafts = state.drafts;
        writes = state.writes;
    }

    static final class State {
        private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
        private final Object writes = new Object();
    }

    Map<String, Object> prepare(Request request) throws IOException, InterruptedException {
        if ((request.planId() == null) != (request.planDate() == null)) {
            throw new IllegalArgumentException("Для оплаты по плану выбери конкретную строку и её плановую дату.");
        }
        BigDecimal amount = PlanTools.amountOf(request.amount());
        LocalDate date = factDate(request.date(), selfin.today());
        var state = readState(request, date, amount);
        if (!Boolean.TRUE.equals(request.separatePurchase()) && !state.similar().isEmpty()) {
            return Map.of("status", "clarify", "existing", state.similar().stream()
                    .map(e -> Map.of("id", e.id(), "name", PlanTools.displayName(e),
                            "date", e.date(), "amount", e.factAmount().toPlainString()))
                    .toList());
        }
        UUID draftId = UUID.randomUUID();
        var draft = new Draft(request, state, confirmationLine(state, request.planId() != null));
        drafts.put(draftId, draft);
        return Map.of("status", "prepared", "draftId", draftId, "line", draft.line);
    }

    Map<String, Object> save(UUID draftId, Predicate<String> confirmation) throws IOException, InterruptedException {
        Draft draft = draftId == null ? null : drafts.get(draftId);
        if (draft == null) {
            throw new IllegalArgumentException("Черновик недоступен. Сверь журнал, прежде чем готовить новую запись.");
        }
        synchronized (draft) {
            boolean retry = draft.result != null && "unknown".equals(draft.result.get("status"));
            if (draft.result != null && !retry) {
                return draft.result;
            }
            if (!draft.approved) {
                if (!confirmation.test(draft.line)) {
                    draft.result = Map.of("status", "declined");
                    return draft.result;
                }
                draft.approved = true;
            }
            synchronized (writes) {
                if (!retry && !stillCurrent(draft)) {
                    draft.result = outcome("stale", "Данные Selfin изменились. Подготовь запись для нового подтверждения.");
                } else {
                    draft.result = write(draft, retry);
                }
                return draft.result;
            }
        }
    }

    private boolean stillCurrent(Draft draft) throws IOException, InterruptedException {
        try {
            var fact = draft.state.fact();
            return draft.state.equals(readState(draft.request, fact.date(), fact.factAmount()));
        } catch (IllegalArgumentException changed) {
            return false;
        }
    }

    private Snapshot readState(Request request, LocalDate date, BigDecimal amount)
            throws IOException, InterruptedException {
        var forDay = selfin.events(date, date);
        var plan = request.planId() == null ? null : linkedPlan(request, forDay, date);
        Category category;
        if (plan == null) {
            category = selfin.categories().stream()
                    .filter(PlanTools::planCategory)
                    .filter(c -> EXPENSE.equals(c.type()) && c.id().equals(request.categoryId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Выбери действующую расходную категорию."));
        } else {
            category = new Category(plan.categoryId(), plan.categoryName(), plan.type(), plan.priority(), null);
            if (request.categoryId() != null && !request.categoryId().equals(category.id())) {
                throw new IllegalArgumentException("Для записи по плану используется его категория.");
            }
        }

        String planName = plan != null && plan.description() != null && !plan.description().isBlank()
                ? plan.description() : category.name();
        String description = request.name() == null ? PlanTools.oneLine(planName) : request.name();
        String priority = request.character() == null ? category.priority() : request.character();
        if (priority == null || !PlanTools.CHARACTERS.containsKey(priority)) {
            throw new IllegalArgumentException("Выбери характер: бронь, ожидание или хотелка.");
        }
        var fact = new FactInput(date, category.id(), EXPENSE, amount, priority, PlanTools.nameOf(description));
        var link = plan == null ? selfin.factPreview(fact) : new FactPreview(plan.id(),
                planName, plan.date(), plan.plannedAmount(),
                Objects.requireNonNullElse(plan.linkedFactsAmount(),
                        Objects.requireNonNullElse(plan.factAmount(), BigDecimal.ZERO)));
        var similar = forDay.stream()
                .filter(e -> date.equals(e.date()) && EXPENSE.equals(e.type()) && category.id().equals(e.categoryId()))
                .filter(e -> e.factAmount() != null && amount.compareTo(e.factAmount()) == 0)
                .sorted(Comparator.comparing(PlanEvent::id))
                .toList();
        return new Snapshot(fact, category, link, similar);
    }

    private PlanEvent linkedPlan(Request request, List<PlanEvent> forDay, LocalDate date)
            throws IOException, InterruptedException {
        LocalDate planDate = request.planDate();
        List<PlanEvent> candidates = planDate.equals(date) ? forDay : selfin.events(planDate, planDate);
        return candidates.stream()
                .filter(e -> request.planId().equals(e.id()) && planDate.equals(e.date()))
                .filter(e -> PlanTools.currentPlan(e, EXPENSE) && e.plannedAmount() != null)
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Выбери конкретную плановую строку расхода в журнале Selfin."));
    }

    private Map<String, Object> write(Draft draft, boolean retry) {
        try {
            FactInput fact = draft.state.fact();
            UUID requestedPlan = draft.request.planId();
            FactEvent saved = selfin.createFact(draft.key, fact, requestedPlan);
            if (!matches(fact, requestedPlan, saved)) {
                return outcome("unknown", "Selfin вернул запись с другими реквизитами. Проверь исход той же попытки.");
            }
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("status", "saved");
            receipt.put("eventId", saved.id());
            receipt.put("name", saved.description());
            receipt.put("date", saved.date());
            receipt.put("amount", saved.factAmount().toPlainString());
            receipt.put("category", draft.state.category().name());
            receipt.put("parentEventId", saved.parentEventId());
            FactPreview preview = draft.state.link();
            boolean changed = !Objects.equals(preview == null ? null : preview.id(), saved.parentEventId());
            if (saved.parentEventId() != null) {
                String parentName = saved.parentPlanDescription();
                if (parentName == null && !changed) {
                    parentName = preview.name();
                }
                if (parentName != null) {
                    receipt.put("parentName", parentName);
                }
            }
            if (changed) {
                receipt.put("message", "Связь с ожиданием изменилась при записи. Здесь указан фактический результат.");
            }
            return receipt;
        } catch (SelfinClient.ApiException error) {
            boolean denied = Set.of(400, 401, 403, 404, 422).contains(error.status());
            String message = error.accessDenied() ? "Selfin не предоставил доступ."
                    : (denied ? "Selfin отклонил запись" : "Исход записи пока неясен") + " (HTTP " + error.status() + ").";
            return outcome(denied && !retry ? "rejected" : "unknown", message);
        } catch (IOException error) {
            return outcome("unknown", "Ответ Selfin потерялся. Проверь исход той же попытки.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return outcome("unknown", "Запрос прервался. Проверь исход той же попытки.");
        }
    }

    private static boolean matches(FactInput fact, UUID requestedPlan, FactEvent saved) {
        return saved != null && saved.id() != null && "FACT".equals(saved.eventKind()) && !saved.onCredit()
                && EXPENSE.equals(saved.type()) && fact.date().equals(saved.date())
                && fact.categoryId().equals(saved.categoryId()) && fact.priority().equals(saved.priority())
                && fact.description().equals(saved.description()) && saved.factAmount() != null
                && fact.factAmount().compareTo(saved.factAmount()) == 0
                && (requestedPlan == null || requestedPlan.equals(saved.parentEventId()));
    }

    private static String confirmationLine(Snapshot state, boolean linked) {
        FactInput fact = state.fact();
        var link = state.link();
        String relation = link == null ? "самостоятельная трата"
                : "%s «%s», плановая дата %s, записано %s ₽ из %s ₽".formatted(
                        linked ? "по плану" : "по ожиданию", PlanTools.oneLine(link.name()), link.date(),
                        link.recorded().toPlainString(), link.plannedAmount().toPlainString());
        return "%s · расход · %s ₽ · %s · %s · %s · %s".formatted(fact.description(),
                fact.factAmount().toPlainString(), fact.date(), PlanTools.oneLine(state.category().name()),
                PlanTools.CHARACTERS.get(fact.priority()), relation);
    }

    private static LocalDate factDate(String value, LocalDate today) {
        try {
            LocalDate date = switch (value == null ? "today" : value) {
                case "today" -> today;
                case "yesterday" -> today.minusDays(1);
                default -> LocalDate.parse(value);
            };
            if (date.isAfter(today)) {
                throw new IllegalArgumentException("Запись возможна на сегодня или прошедшую дату. Для будущей даты есть план.");
            }
            return date;
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Укажи фактическую дату траты.");
        }
    }

    private static Map<String, Object> outcome(String status, String message) {
        return Map.of("status", status, "message", message);
    }

    record Request(String name, BigDecimal amount, String date, UUID categoryId, String character,
                   UUID planId, LocalDate planDate, Boolean separatePurchase) {}

    private record Snapshot(FactInput fact, Category category, FactPreview link, List<PlanEvent> similar) {}

    private static final class Draft {
        private final UUID key = UUID.randomUUID();
        private final Request request;
        private final Snapshot state;
        private final String line;
        private boolean approved;
        private Map<String, Object> result;

        private Draft(Request request, Snapshot state, String line) {
            this.request = request;
            this.state = state;
            this.line = line;
        }
    }
}
