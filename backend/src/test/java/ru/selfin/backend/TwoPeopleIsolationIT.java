package ru.selfin.backend;

import com.jayway.jsonpath.JsonPath;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.OwnerService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Изоляция как инвариант (ANO-148): два человека с пересекающимися данными, и ни одна ручка API не отдаёт
 * и не меняет чужое.
 *
 * <p>Оба заводят через API одно и то же — те же имена, даты, виды строк и ключи идемпотентности; суммы у второго
 * вдвое больше. Людей различает сервер, как в проде: у каждого свой {@code sub}, владельца находит фильтр после
 * авторизации. Третий в базе — синтетический владелец со стартовыми строками.
 *
 * <p>Оракул один на все ручки:
 * <ol>
 *   <li>ни в одном ответе нет чужого id — id строк других владельцев берутся из базы ролью владельца таблиц;</li>
 *   <li>чтения человека не меняются от действий другого — так видна и чужая сумма в итогах, где id нет;</li>
 *   <li>по чужому id — 404: чужого не существует.</li>
 * </ol>
 * Сторож полноты сверяет вызванные ручки с картой Spring: новая ручка без проверки тест не пройдёт.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-14-two-people-isolation-design.md
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TwoPeopleIsolationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    static final LocalDate TODAY = LocalDate.now();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlers;

    /** Вызванные ручки по роли вызова — «METHOD шаблон». */
    final Set<String> written = new TreeSet<>();
    final Set<String> read = new TreeSet<>();
    final Set<String> attacked = new TreeSet<>();
    final SoftAssertions softly = new SoftAssertions();

    @Test
    @DisplayName("ни одна ручка не отдаёт и не меняет чужое: два человека с пересекающимися данными")
    void noEndpointShowsOrChangesSomeoneElses() throws Exception {
        Person first = seed("first-person", 1);
        reads(first); // вхолостую: чтения достраивают бессрочные повторы
        Map<String, String> firstAlone = reads(first);

        Person second = seed("second-person", 2);
        reads(second);
        Map<String, String> secondBefore = reads(second);

        // Чужие id — и до ударов: удачный удар мог бы удалить строку, и её id выпал бы из списка.
        Map<String, String> foreignToFirst = foreignIds(first.owner);
        Map<String, String> foreignToSecond = foreignIds(second.owner);
        // Правило 1 держится на том, что чужие id читаются в обход RLS — ролью владельца таблиц, а не пулом приложения.
        softly.assertThat(foreignToFirst).as("в списке чужих для первого — строки второго").containsKey(second.id("account"));
        attack(second, first);
        attack(first, second);

        softly.assertThat(first.owner).as("людей различает сервер").isNotEqualTo(second.owner)
                .isNotEqualTo(OwnerService.SYNTHETIC_OWNER);
        unchanged(first, firstAlone);
        unchanged(second, secondBefore);
        noForeignIds(first, foreignToFirst);
        noForeignIds(second, foreignToSecond);
        everyEndpointChecked();
        softly.assertAll();
    }

    // ── данные ──────────────────────────────────────────────────────────────────

    /** Один и тот же набор у каждого — строки во всех 11 таблицах с владельцем. Суммы — умноженные на {@code k}. */
    private Person seed(String sub, int k) throws Exception {
        Person p = new Person(sub);
        p.owner = UUID.fromString(JsonPath.read(ok(p, GET, "/api/v1/me", null, null), "$.id"));
        p.save("category", ok(p, POST, "/api/v1/categories", """
                {"name":"Продукты","type":"EXPENSE"}""", null));
        p.save("income", ok(p, POST, "/api/v1/categories", """
                {"name":"Зарплата","type":"INCOME"}""", null));
        p.save("account", ok(p, POST, "/api/v1/accounts", """
                {"name":"Карта","kind":"DEBIT","trackBalance":true}""", null));
        ok(p, PATCH, "/api/v1/accounts/" + p.id("account") + "/default", null, null);
        p.save("checkpoint", ok(p, POST, "/api/v1/balance-checkpoints", """
                {"date":"%s","amount":%d,"accountId":"%s"}""".formatted(TODAY.minusDays(1), 50_000 * k, p.id("account")), null));
        p.save("item", ok(p, POST, "/api/v1/capital/items", """
                {"kind":"ASSET","name":"Квартира","initialValue":%d,"initialValuedAt":"%s"}"""
                .formatted(5_000_000 * k, TODAY.minusMonths(1)), null));
        p.save("revaluation", ok(p, POST, "/api/v1/capital/items/" + p.id("item") + "/revaluations", """
                {"value":%d,"valuedAt":"%s"}""".formatted(5_100_000 * k, TODAY.minusDays(1)), null));
        p.save("plan", ok(p, POST, "/api/v1/events", """
                {"date":"%s","categoryId":"%s","type":"EXPENSE","plannedAmount":%d,"priority":"HIGH"}"""
                .formatted(TODAY.plusDays(5), p.id("category"), 7_000 * k), key("plan")));
        ok(p, POST, "/api/v1/events", """
                {"date":"%s","categoryId":"%s","type":"INCOME","plannedAmount":%d}"""
                .formatted(TODAY.plusDays(10), p.id("income"), 100_000 * k), key("income"));
        LocalDate monthly = TODAY.plusMonths(1).withDayOfMonth(15);
        ok(p, POST, "/api/v1/events", """
                {"date":"%s","categoryId":"%s","type":"EXPENSE","plannedAmount":%d,
                 "recurring":{"frequency":"MONTHLY","dayOfMonth":15,"startDate":"%s"}}"""
                .formatted(monthly, p.id("category"), 2_000 * k, monthly), key("recurring"));
        ok(p, POST, "/api/v1/events/" + p.id("plan") + "/facts", """
                {"date":"%s","factAmount":%d}""".formatted(TODAY, 3_000 * k), key("fact"));
        p.save("standaloneFact", ok(p, POST, "/api/v1/events/facts", """
                {"date":"%s","categoryId":"%s","type":"EXPENSE","factAmount":%d}"""
                .formatted(TODAY, p.id("category"), 1_500 * k), key("standalone")));
        p.save("wish", ok(p, POST, "/api/v1/events/wishlist", """
                {"description":"Велосипед","plannedAmount":%d}""".formatted(30_000 * k), null));
        p.save("fund", ok(p, POST, "/api/v1/funds", """
                {"name":"Отпуск","targetAmount":%d,"targetDate":"%s"}""".formatted(100_000 * k, TODAY.plusMonths(6)), null));
        ok(p, POST, "/api/v1/funds/" + p.id("fund") + "/transfer", """
                {"amount":%d,"confirm":true}""".formatted(5_000 * k), key("transfer"));
        ok(p, POST, "/api/v1/funds", """
                {"name":"Подушка","targetAmount":%d,"accountId":"%s"}""".formatted(200_000 * k, p.id("account")), null);
        p.save("credit", ok(p, POST, "/api/v1/funds", """
                {"name":"Машина","targetAmount":%d,"targetDate":"%s","purchaseType":"CREDIT",
                 "creditRate":12,"creditTermMonths":12}""".formatted(120_000 * k, TODAY.plusMonths(6)), null));
        ok(p, PATCH, "/api/v1/funds/" + p.id("credit") + "/wishlist-status", """
                {"status":"OPEN"}""", null);
        // Кредит зафиксирован — график платежей с системной категорией «Кредит».
        ok(p, POST, "/api/v1/wishlist/items/" + p.id("credit") + "/fix", """
                {"sourceKind":"CREDIT","amount":%d,"date":"%s","stretchMonths":0,"creditRate":12,"creditTermMonths":12}"""
                .formatted(120_000 * k, TODAY.plusMonths(6)), null);
        // Копилка потрачена на цель — трата с системной категорией «Цели».
        p.save("repair", ok(p, POST, "/api/v1/funds", """
                {"name":"Ремонт","targetAmount":%d}""".formatted(50_000 * k), null));
        ok(p, POST, "/api/v1/funds/" + p.id("repair") + "/transfer", """
                {"amount":%d,"confirm":true}""".formatted(3_000 * k), key("repair"));
        ok(p, DELETE, "/api/v1/funds/" + p.id("repair") + "?money=SPENT", null, null);
        ok(p, PUT, "/api/v1/settings/wishlist", """
                {"capitalThresholdRub":%d}""".formatted(50_000 * k), null);
        ok(p, PUT, "/api/v1/settings/pocket", """
                {"bufferAmount":%d}""".formatted(10_000 * k), null);
        // Системные категории ищутся по имени — у каждого свои (ANO-150): чужую выдало бы правило 1.
        assertThat(JsonPath.<List<String>>read(ok(p, GET, "/api/v1/categories", null, null), "$[*].name"))
                .contains("Хотелки", "Переводы в копилки", "Кредит", "Цели");
        return p;
    }

    /** Ключ идемпотентности — общий у обоих: повтор чужого ключа не должен вернуть чужую запись. */
    private static UUID key(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(UTF_8));
    }

    // ── чтения ──────────────────────────────────────────────────────────────────

    /** Все чтения человека — ответ по каждому вызову. */
    private Map<String, String> reads(Person p) throws Exception {
        String period = "startDate=" + TODAY.minusMonths(1) + "&endDate=" + TODAY.plusMonths(6);
        String item = "/api/v1/capital/items/" + p.id("item");
        Map<String, String> answers = new LinkedHashMap<>();
        for (String uri : List.of("/api/v1/accounts", "/api/v1/accounts/" + p.id("account"),
                "/api/v1/analytics/dashboard", "/api/v1/analytics/report", "/api/v1/analytics/multi-month?" + period,
                "/api/v1/analytics/forecast", "/api/v1/analytics/forecast-readiness", "/api/v1/balance-checkpoints",
                "/api/v1/capital/items", item, item + "/revaluations", "/api/v1/capital/summary",
                "/api/v1/capital/trajectory", "/api/v1/categories", "/api/v1/events?" + period,
                "/api/v1/events?priority=LOW", "/api/v1/funds", "/api/v1/funds/planner", "/api/v1/hello",
                "/api/v1/me", "/api/v1/pocket", "/api/v1/settings/wishlist", "/api/v1/settings/pocket",
                "/api/v1/strategy/timeline", "/api/v1/version", "/api/v1/wishlist/simulation")) {
            answers.put("GET " + uri, readOk(p, GET, uri, null));
        }
        // Расчёты «что если» — POST, но ничего не пишут: те же чтения.
        answers.put("POST /api/v1/pocket/sandbox", readOk(p, POST, "/api/v1/pocket/sandbox", "{}"));
        answers.put("POST /api/v1/wishlist/simulation/recompute", readOk(p, POST,
                "/api/v1/wishlist/simulation/recompute", """
                        {"kind":"SAVINGS","amount":40000,"targetDate":"%s"}""".formatted(TODAY.plusMonths(6))));
        return answers;
    }

    private void unchanged(Person p, Map<String, String> before) throws Exception {
        Map<String, String> after = reads(p);
        for (var answer : before.entrySet()) {
            String now = after.get(answer.getKey());
            if (answer.getValue().equals(now)) continue;
            var diff = JSONCompare.compareJSON(answer.getValue(), now, JSONCompareMode.NON_EXTENSIBLE);
            softly.assertThat(diff.passed()).as("%s у %s изменился от действий другого: %s",
                    answer.getKey(), p.sub, diff.getMessage()).isTrue();
        }
    }

    /** Id строк других владельцев → таблица: во всех таблицах с {@code user_id} и в {@code users}. */
    private Map<String, String> foreignIds(UUID owner) {
        Map<String, String> foreign = new HashMap<>();
        for (String table : jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'user_id'""", String.class)) {
            jdbc.queryForList("SELECT id::text FROM " + table + " WHERE user_id <> ?", String.class, owner)
                    .forEach(id -> foreign.put(id, table));
        }
        jdbc.queryForList("SELECT id::text FROM users WHERE id <> ?", String.class, owner)
                .forEach(id -> foreign.put(id, "users"));
        return foreign;
    }

    /** Правило 1: чужих id нет ни в одном ответе — ни снятых до ударов, ни появившихся после. */
    private void noForeignIds(Person p, Map<String, String> beforeAttacks) {
        Map<String, String> foreign = new HashMap<>(beforeAttacks);
        foreign.putAll(foreignIds(p.owner));
        softly.assertThat(foreign).as("у других владельцев есть строки — иначе искать нечего").isNotEmpty();
        for (var answer : p.answers) {
            List<String> leaked = foreign.entrySet().stream().filter(id -> answer.getValue().contains(id.getKey()))
                    .map(id -> id.getValue() + " " + id.getKey()).toList();
            softly.assertThat(leaked).as("%s у %s — чужие id", answer.getKey(), p.sub).isEmpty();
        }
    }

    // ── удары по чужому id ──────────────────────────────────────────────────────

    /** Удар по ручке с id в пути: {@code %s} — id строки другого человека по роли; ответ — 404, если не указан иной. */
    private record Hit(HttpMethod method, String path, String role, String body, String answer) {
        Hit(HttpMethod method, String path, String role, String body) {
            this(method, path, role, body, "404");
        }
    }

    /** Каждая ручка с id в пути — по id другого человека. Удаления — последними, дочерние до родителей. */
    private void attack(Person who, Person victim) throws Exception {
        String yesterday = TODAY.minusDays(1).toString();
        List<Hit> hits = List.of(
                new Hit(GET, "/api/v1/accounts/%s", "account", null),
                new Hit(PUT, "/api/v1/accounts/%s", "account", """
                        {"name":"Чужая","kind":"DEBIT"}"""),
                new Hit(PATCH, "/api/v1/accounts/%s/default", "account", null),
                new Hit(PUT, "/api/v1/balance-checkpoints/%s", "checkpoint", """
                        {"date":"%s","amount":1}""".formatted(yesterday)),
                new Hit(GET, "/api/v1/capital/items/%s", "item", null),
                // История переоценок неизвестного предмета — пустой список; у чужого — так же.
                new Hit(GET, "/api/v1/capital/items/%s/revaluations", "item", null, "200 []"),
                new Hit(PUT, "/api/v1/capital/items/%s", "item", """
                        {"name":"Чужое"}"""),
                new Hit(POST, "/api/v1/capital/items/%s/revaluations", "item", """
                        {"value":1,"valuedAt":"%s"}""".formatted(yesterday)),
                new Hit(PUT, "/api/v1/capital/revaluations/%s", "revaluation", """
                        {"value":1}"""),
                new Hit(PUT, "/api/v1/categories/%s", "category", """
                        {"name":"Чужая","type":"EXPENSE"}"""),
                new Hit(PATCH, "/api/v1/categories/%s/priority", "category", null),
                new Hit(PUT, "/api/v1/events/%s", "plan", """
                        {"date":"%s","categoryId":"%s","type":"EXPENSE","plannedAmount":1}"""
                        .formatted(TODAY.plusDays(5), who.id("category"))),
                new Hit(PATCH, "/api/v1/events/%s/fact", "standaloneFact", """
                        {"factAmount":1}"""),
                new Hit(PATCH, "/api/v1/events/%s/priority", "plan", null),
                new Hit(POST, "/api/v1/events/%s/facts", "plan", """
                        {"date":"%s","factAmount":1}""".formatted(TODAY)),
                new Hit(PATCH, "/api/v1/events/%s/wishlist-params", "wish", """
                        {"plannedAmount":1}"""),
                new Hit(PATCH, "/api/v1/events/%s/wishlist-status", "wish", """
                        {"status":"DISMISSED"}"""),
                new Hit(POST, "/api/v1/wishlist/items/%s/convert", "wish", """
                        {"sourceKind":"WISHLIST","target":"PLAN_EVENT"}"""),
                new Hit(POST, "/api/v1/wishlist/items/%s/fix", "wish", """
                        {"sourceKind":"WISHLIST","amount":1}"""),
                new Hit(PUT, "/api/v1/funds/%s", "fund", """
                        {"name":"Чужая","targetAmount":1}"""),
                new Hit(POST, "/api/v1/funds/%s/transfer", "fund", """
                        {"amount":1,"confirm":true}"""),
                new Hit(PATCH, "/api/v1/funds/%s/wishlist-params", "credit", """
                        {"targetAmount":1}"""),
                new Hit(PATCH, "/api/v1/funds/%s/wishlist-status", "credit", """
                        {"status":"DISMISSED"}"""),
                new Hit(DELETE, "/api/v1/capital/revaluations/%s", "revaluation", null),
                new Hit(DELETE, "/api/v1/capital/items/%s", "item", null),
                new Hit(DELETE, "/api/v1/balance-checkpoints/%s", "checkpoint", null),
                new Hit(DELETE, "/api/v1/events/%s", "plan", null),
                new Hit(DELETE, "/api/v1/funds/%s?money=RETURN", "fund", null),
                new Hit(DELETE, "/api/v1/accounts/%s", "account", null),
                new Hit(DELETE, "/api/v1/categories/%s", "category", null));
        for (Hit hit : hits) {
            MockHttpServletResponse response = call(who, attacked, hit.method(),
                    hit.path().formatted(victim.id(hit.role())), hit.body(), null).getResponse();
            String answer = response.getStatus() == 404 ? "404"
                    : response.getStatus() + " " + response.getContentAsString(UTF_8);
            softly.assertThat(answer).as("%s %s у %s по id %s", hit.method(), hit.path(), who.sub, victim.sub)
                    .isEqualTo(hit.answer());
        }
    }

    // ── сторож полноты ──────────────────────────────────────────────────────────

    /** С id в пути — удар чужим id; {@code GET} без id — в чтениях; запись без id — вызвана. */
    private void everyEndpointChecked() {
        Set<String> api = new TreeSet<>();
        handlers.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/v1/"))
                .forEach(path -> info.getMethodsCondition().getMethods().forEach(m -> api.add(m + " " + path))));
        softly.assertThat(api).as("карта ручек").isNotEmpty();
        List<String> unchecked = api.stream().filter(endpoint -> endpoint.contains("{") ? !attacked.contains(endpoint)
                : endpoint.startsWith("GET ") ? !read.contains(endpoint)
                : !written.contains(endpoint) && !read.contains(endpoint)).toList();
        softly.assertThat(unchecked).as("ручки без проверки изоляции — добавить в тест").isEmpty();
    }

    // ── помощники ───────────────────────────────────────────────────────────────

    /** Человек теста: свой вход, id его строк по ролям и ответы ему — для поиска чужих id. */
    private static final class Person {
        final String sub;
        final Map<String, String> ids = new HashMap<>();
        final List<Map.Entry<String, String>> answers = new ArrayList<>();
        UUID owner;

        Person(String sub) {
            this.sub = sub;
        }

        RequestPostProcessor login() {
            return oidcLogin().idToken(token -> token.subject(sub));
        }

        void save(String role, String answer) {
            ids.put(role, JsonPath.read(answer, "$.id"));
        }

        String id(String role) {
            return ids.get(role);
        }
    }

    private MvcResult call(Person who, Set<String> role, HttpMethod method, String uri, String body, UUID key)
            throws Exception {
        MockHttpServletRequestBuilder request = request(method, uri).with(who.login())
                .header("Idempotency-Key", key != null ? key : UUID.randomUUID());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mvc.perform(request).andReturn();
        role.add(method + " " + result.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        return result;
    }

    /** Запись своих данных: без неё дальше проверять нечего — падает сразу. */
    private String ok(Person who, HttpMethod method, String uri, String body, UUID key) throws Exception {
        MockHttpServletResponse response = call(who, method == GET ? read : written, method, uri, body, key)
                .getResponse();
        String answer = response.getContentAsString(UTF_8);
        assertThat(response.getStatus()).as("%s %s у %s: %s", method, uri, who.sub, answer).isBetween(200, 299);
        who.answers.add(Map.entry(method + " " + uri, answer));
        return answer;
    }

    private String readOk(Person who, HttpMethod method, String uri, String body) throws Exception {
        MockHttpServletResponse response = call(who, read, method, uri, body, null).getResponse();
        String answer = response.getContentAsString(UTF_8);
        softly.assertThat(response.getStatus()).as("%s %s у %s: %s", method, uri, who.sub, answer).isEqualTo(200);
        who.answers.add(Map.entry(method + " " + uri, answer));
        return answer;
    }
}
