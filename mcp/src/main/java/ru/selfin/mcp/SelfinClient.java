package ru.selfin.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class SelfinClient {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    private final HttpClient http;
    private final URI baseUrl;
    private final String accessToken;
    private final HttpRequest pocketRequest;

    SelfinClient(URI baseUrl, String accessToken) {
        String host = baseUrl.getHost();
        String scheme = baseUrl.getScheme();
        String path = baseUrl.getPath();
        if (host == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || !(path.isEmpty() || path.equals("/"))
                || !("https".equals(scheme) || ("http".equals(scheme) && LOOPBACK.contains(host)))) {
            throw new IllegalArgumentException("Invalid Selfin API origin");
        }
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("Empty Selfin access token");
        }

        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        pocketRequest = request("/pocket").GET().build();
    }

    Map<String, Object> readAvailableMoney() throws IOException, InterruptedException {
        var pocket = JSON.readValue(send(pocketRequest), Pocket.class);
        if (pocket == null) {
            throw new IOException("Empty forecast");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", pocket.pocket().toPlainString());
        summary.put("currency", "RUB");
        var horizon = pocket.horizon();
        summary.put("horizon", Map.of(
                "endDate", horizon.endDate().toString(),
                "label", horizon.label(),
                "fallback", horizon.fallback()));
        if (pocket.checkpointDate() != null) {
            summary.put("checkpointDate", pocket.checkpointDate().toString());
        }
        summary.put("planHasExpectations", pocket.planHasExpectations());
        summary.put("breakdown", pocket.breakdown().stream()
                .takeWhile(line -> !line.type().equals("POCKET"))
                .map(line -> Map.of("type", line.type(), "label", line.label(),
                        "amount", line.amount().toPlainString()))
                .toList());
        return summary;
    }

    LocalDate today() throws IOException, InterruptedException {
        var pocket = JSON.readValue(send(pocketRequest), PocketCalendar.class);
        if (pocket == null || pocket.trajectory() == null || pocket.trajectory().isEmpty()) {
            throw new IOException("Missing Selfin calendar");
        }
        var firstDay = pocket.trajectory().getFirst();
        if (firstDay == null || firstDay.date() == null) {
            throw new IOException("Missing Selfin calendar date");
        }
        return firstDay.date();
    }

    List<Category> categories() throws IOException, InterruptedException {
        return JSON.readValue(get("/categories"), new TypeRef<List<Category>>() {});
    }

    List<PlanEvent> events(LocalDate start, LocalDate end) throws IOException, InterruptedException {
        return JSON.readValue(get("/events?startDate=" + start + "&endDate=" + end),
                new TypeRef<List<PlanEvent>>() {});
    }

    PlanFactReport planFact(LocalDate monthStart) throws IOException, InterruptedException {
        var response = JSON.readValue(get("/analytics/report?date=" + monthStart), AnalyticsReport.class);
        if (response == null || response.planFact() == null) {
            throw new IOException("Missing Selfin plan-fact report");
        }
        return response.planFact();
    }

    FactSearch facts(LocalDate start, LocalDate end, String query, UUID categoryId)
            throws IOException, InterruptedException {
        String path = "/events/search?from=" + start + "&to=" + end + "&factsOnly=true";
        if (query != null && !query.isBlank()) {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
                    .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
            path += "&q=" + encoded;
        }
        if (categoryId != null) {
            path += "&categoryId=" + categoryId;
        }
        return JSON.readValue(get(path), FactSearch.class);
    }

    PlanEvent createPlan(UUID attemptKey, PlanInput plan) throws IOException, InterruptedException {
        return post("/events", attemptKey, plan, 200, PlanEvent.class);
    }

    FactPreview factPreview(FactInput input) throws IOException, InterruptedException {
        var response = exchange(request("/events/fact-preview?categoryId=" + input.categoryId()
                + "&date=" + input.date() + "&type=" + input.type() + "&priority=" + input.priority()).GET().build(),
                200, 204);
        if (response.statusCode() == 204) {
            return null;
        }
        var preview = JSON.readValue(response.body(), FactPreview.class);
        if (preview == null || preview.id() == null || preview.date() == null
                || preview.name() == null || preview.plannedAmount() == null || preview.recorded() == null) {
            throw new IOException("Missing Selfin fact preview");
        }
        return preview;
    }

    FactEvent createFact(UUID attemptKey, FactInput fact, UUID planId) throws IOException, InterruptedException {
        if (planId == null) {
            return post("/events/facts", attemptKey, fact, 201, FactEvent.class);
        }
        return post("/events/" + planId + "/facts", attemptKey,
                new LinkedFactInput(fact.date(), fact.factAmount(), fact.priority(), fact.description()),
                200, FactEvent.class);
    }

    SandboxForecast previewExpense(String scope, BigDecimal amount, LocalDate date)
            throws IOException, InterruptedException {
        return post("/pocket/sandbox", null,
                new SandboxInput(scope, List.of(new TrialExpense(amount, date)), List.of()),
                200, SandboxForecast.class);
    }

    private <T> T post(String path, UUID attemptKey, Object input, int status, Class<T> type)
            throws IOException, InterruptedException {
        var builder = request(path).header("Content-Type", "application/json");
        if (attemptKey != null) {
            builder.header("Idempotency-Key", attemptKey.toString());
        }
        var post = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(input))).build();
        return JSON.readValue(exchange(post, status).body(), type);
    }

    private String get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve("/api/v1" + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken);
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        return exchange(request, 200).body();
    }

    private HttpResponse<String> exchange(HttpRequest request, int... statuses) throws IOException, InterruptedException {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for (int status : statuses) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        throw new ApiException(response.statusCode());
    }

    record Category(UUID id, String name, String type, String priority, String systemKind) {}

    record PlanEvent(UUID id, LocalDate date, UUID categoryId, String categoryName, String type,
                     BigDecimal plannedAmount, BigDecimal factAmount, String status, String priority,
                     String description, String eventKind, UUID recurringRuleId, String wishlistStatus,
                     String recurringFrequency, Integer recurringDayOfMonth, Integer recurringMonthOfYear,
                     LocalDate recurringStartDate, LocalDate recurringEndDate,
                     int linkedFactsCount, BigDecimal linkedFactsAmount, boolean onCredit) {}

    private record AnalyticsReport(PlanFactReport planFact) {}

    record PlanFactReport(List<CategoryPlanFact> categories, BigDecimal totalPlannedIncome, BigDecimal totalFactIncome,
                          BigDecimal totalPlannedExpense, BigDecimal totalFactExpense) {}

    record CategoryPlanFact(String categoryName, String type, BigDecimal planned, BigDecimal fact) {}

    record FactSearch(List<PlanEvent> rows, int total) {}

    record PlanInput(LocalDate date, UUID categoryId, String type, BigDecimal plannedAmount,
                     String priority, String description, Recurring recurring) {}

    record Recurring(String frequency, int dayOfMonth, LocalDate startDate, LocalDate endDate) {}

    record FactInput(LocalDate date, UUID categoryId, String type, BigDecimal factAmount,
                     String priority, String description) {}

    private record LinkedFactInput(LocalDate date, BigDecimal factAmount, String priority, String description) {}

    record FactPreview(UUID id, String name, LocalDate date, BigDecimal plannedAmount, BigDecimal recorded) {}

    record FactEvent(UUID id, LocalDate date, UUID categoryId, String type, BigDecimal factAmount,
                     String priority, String description, String eventKind, boolean onCredit,
                     UUID parentEventId, String parentPlanDescription) {}

    private record SandboxInput(String scope, List<TrialExpense> tryOn, List<?> exclude) {}

    private record TrialExpense(BigDecimal amount, LocalDate date) {}

    record SandboxForecast(SandboxPocket baseline, SandboxPocket fitted) {}

    record SandboxPocket(BigDecimal pocket, Horizon horizon, LocalDate checkpointDate,
                         Boolean planHasExpectations, Minimum minPoint, List<SandboxDay> trajectory) {}

    record Minimum(LocalDate date, BigDecimal balance) {}

    record SandboxDay(LocalDate date, BigDecimal balance, BigDecimal income) {}

    private record PocketCalendar(List<TrajectoryDate> trajectory) {}

    private record TrajectoryDate(LocalDate date) {}

    private record Pocket(BigDecimal pocket, LocalDate checkpointDate, Horizon horizon,
                          Boolean planHasExpectations, List<BreakdownLine> breakdown) {
        private Pocket {
            Objects.requireNonNull(pocket);
            Objects.requireNonNull(horizon);
            Objects.requireNonNull(planHasExpectations);
            breakdown = List.copyOf(breakdown);
        }
    }

    record Horizon(LocalDate endDate, String label, Boolean fallback) {
        Horizon {
            Objects.requireNonNull(endDate);
            Objects.requireNonNull(label);
            Objects.requireNonNull(fallback);
        }
    }

    private record BreakdownLine(String type, String label, BigDecimal amount) {
        private BreakdownLine {
            Objects.requireNonNull(type);
            Objects.requireNonNull(label);
            Objects.requireNonNull(amount);
        }
    }

    static final class ApiException extends IOException {
        private final int status;

        ApiException(int status) {
            super("Selfin API returned HTTP " + status);
            this.status = status;
        }

        int status() {
            return status;
        }

        boolean accessDenied() {
            return status == 401 || status == 403;
        }
    }
}
