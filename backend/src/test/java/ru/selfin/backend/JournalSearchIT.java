package ru.selfin.backend;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.Consent;
import ru.selfin.backend.service.OwnerService;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Поиск по журналу (срез 3, п. 9, ANO-126): {@code GET /events/search} — по всем месяцам, свежие первыми, до 200
 * строк, сколько нашлось и сколько записано. Удалённое, закрытый записями план и чужое не находятся.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JournalSearchIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired OwnerService owners;

    @Test
    @DisplayName("слова, число и чипы; свежие первыми; записанное со знаком; удалённое, закрытый план и чужое — нет")
    void search() throws Exception {
        RequestPostProcessor me = person("search-me");
        RequestPostProcessor other = person("search-other");
        List<String> expense = categories(me, "EXPENSE");
        String food = expense.get(0);
        String other2 = expense.get(1);
        String income = categories(me, "INCOME").get(0);

        String older = create(me, "/api/v1/events/facts", fact("2025-03-01", food, "EXPENSE", "1240", "Пятерочка"));
        String newer = create(me, "/api/v1/events/facts", fact("2025-10-05", food, "EXPENSE", "980", "Пятёрочка у дома"));
        create(me, "/api/v1/events/facts", fact("2025-10-06", other2, "EXPENSE", "300", "Лента"));
        create(me, "/api/v1/events/facts", fact("2025-10-07", income, "INCOME", "50000", "Зарплата"));
        String gone = create(me, "/api/v1/events/facts", fact("2025-10-08", food, "EXPENSE", "777", "Пятерочка"));
        mockMvc.perform(delete("/api/v1/events/" + gone).with(me)).andExpect(status().is2xxSuccessful());
        String rent = create(me, "/api/v1/events", """
                {"date":"2025-09-12","categoryId":"%s","type":"EXPENSE","plannedAmount":25000,"priority":"HIGH",\
                "description":"Аренда"}""".formatted(other2));
        // Мобильник закрыт записью целиком — план несёт она; в поиске одна строка, а не две.
        String phone = create(me, "/api/v1/events", """
                {"date":"2025-09-15","categoryId":"%s","type":"EXPENSE","plannedAmount":500,"priority":"HIGH",\
                "description":"Мобильник"}""".formatted(other2));
        String phoneFact = create(me, "/api/v1/events/" + phone + "/facts", """
                {"date":"2025-09-15","factAmount":500,"description":"Мобильник"}""");
        // Обсуждаемая хотелка — не строка журнала (Р4, ANO-206): поиск её не находит.
        create(me, "/api/v1/events/wishlist", """
                {"description":"Велосипед","plannedAmount":40000,"date":"2025-09-09"}""");
        create(other, "/api/v1/events/facts",
                fact("2025-10-09", categories(other, "EXPENSE").get(0), "EXPENSE", "1240", "Пятерочка"));

        Map<String, Object> words = search(me, Map.of("q", "пятёр"));
        assertThat(ids(words)).containsExactly(newer, older);
        assertThat((Integer) words.get("total")).isEqualTo(2);
        assertThat(((Number) words.get("recorded")).doubleValue()).isEqualTo(-2220.0);

        assertThat(ids(search(me, Map.of("q", "1 240")))).containsExactly(older);
        assertThat(ids(search(me, Map.of("q", "мобил")))).containsExactly(phoneFact);
        assertThat(ids(search(me, Map.of("q", "аренда")))).containsExactly(rent);
        assertThat(ids(search(me, Map.of("q", "аренда", "factsOnly", "true")))).isEmpty();
        assertThat(ids(search(me, Map.of("q", "велосипед")))).isEmpty();
        assertThat(ids(search(me, Map.of("q", "пятёр", "from", "2025-04-01", "to", "2025-12-31")))).containsExactly(newer);
        assertThat(ids(search(me, Map.of("q", "пятёр", "categoryId", other2)))).isEmpty();
        assertThat(ids(search(me, Map.of("q", "аренда", "priority", "MEDIUM")))).isEmpty();

        Map<String, Object> all = search(me, Map.of("factsOnly", "true"));
        assertThat((Integer) all.get("total")).isEqualTo(5);
        assertThat(((Number) all.get("recorded")).doubleValue()).isEqualTo(50000.0 - 1240 - 980 - 300 - 500);

        assertThat(ids(search(other, Map.of("q", "пятёр")))).hasSize(1).doesNotContain(older, newer);
    }

    @Test
    @DisplayName("найдено больше 200 — в ответе 200 свежих, число и записанное — по всем")
    void limit() throws Exception {
        RequestPostProcessor me = person("search-many");
        String food = categories(me, "EXPENSE").get(0);
        for (int i = 0; i < 201; i++) {
            create(me, "/api/v1/events/facts", fact("2025-01-01", food, "EXPENSE", "10", "Кофе"));
        }
        Map<String, Object> found = search(me, Map.of("q", "кофе"));
        assertThat(ids(found)).hasSize(200);
        assertThat((Integer) found.get("total")).isEqualTo(201);
        assertThat(((Number) found.get("recorded")).doubleValue()).isEqualTo(-2010.0);
    }

    private static String fact(String date, String category, String type, String amount, String name) {
        return """
                {"date":"%s","categoryId":"%s","type":"%s","factAmount":%s,"description":"%s"}"""
                .formatted(date, category, type, amount, name);
    }

    private Map<String, Object> search(RequestPostProcessor who, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/events/search").with(who);
        params.forEach(request::param);
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$");
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> found) {
        return ((List<Map<String, Object>>) found.get("rows")).stream().map(r -> (String) r.get("id")).toList();
    }

    private RequestPostProcessor person(String sub) {
        owners.resolve(sub, Consent.current(Clock.systemUTC()));
        return oidcLogin().idToken(t -> t.subject(sub));
    }

    private List<String> categories(RequestPostProcessor who, String type) throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories").with(who))
                .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$[?(@.type == '%s' && @.isSystem == false)].id".formatted(type));
    }

    private String create(RequestPostProcessor who, String uri, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(uri).with(who)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        String answer = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(answer, "$.id");
    }
}
