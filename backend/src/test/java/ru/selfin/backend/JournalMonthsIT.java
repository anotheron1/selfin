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
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Месяцы с точкой в сетке года журнала (срез 3, п. 7, ANO-257): {@code GET /events/months?year=} — месяцы,
 * где журнал покажет хоть одну строку. Удалённое и чужое точку не ставит.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JournalMonthsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired OwnerService owners;

    @Test
    @DisplayName("месяцы года со строками: план и запись — да, по разу и по порядку; удалённая, хотелка вне плана, "
            + "другой год и чужая — нет")
    void monthsWithRows() throws Exception {
        RequestPostProcessor me = person("months-me");
        RequestPostProcessor other = person("months-other");
        String category = firstExpenseCategory(me);

        create(me, "/api/v1/events/facts", """
                {"date":"2025-12-31","categoryId":"%s","type":"EXPENSE","factAmount":250}""".formatted(category));
        create(me, "/api/v1/events", """
                {"date":"2025-01-01","categoryId":"%s","type":"EXPENSE","plannedAmount":1000,"priority":"HIGH"}""".formatted(category));
        create(me, "/api/v1/events", """
                {"date":"2026-03-20","categoryId":"%s","type":"EXPENSE","plannedAmount":300,"priority":"HIGH"}""".formatted(category));
        // Обсуждаемая хотелка с экрана «Примерить покупку» — не строка месяца: журнал её не покажет (Р4, ANO-206).
        create(me, "/api/v1/events/wishlist", """
                {"description":"Велосипед","plannedAmount":40000,"date":"2025-09-09"}""");
        String gone = create(me, "/api/v1/events", """
                {"date":"2025-05-05","categoryId":"%s","type":"EXPENSE","plannedAmount":500,"priority":"HIGH"}""".formatted(category));
        mockMvc.perform(delete("/api/v1/events/" + gone).with(me)).andExpect(status().is2xxSuccessful());
        create(me, "/api/v1/events", """
                {"date":"2026-03-03","categoryId":"%s","type":"EXPENSE","plannedAmount":700,"priority":"HIGH"}""".formatted(category));
        create(other, "/api/v1/events", """
                {"date":"2025-07-07","categoryId":"%s","type":"EXPENSE","plannedAmount":900,"priority":"HIGH"}"""
                .formatted(firstExpenseCategory(other)));

        assertThat(months(me, 2025)).containsExactly(1, 12);
        assertThat(months(other, 2025)).containsExactly(7);
        assertThat(months(me, 2026)).containsExactly(3);
        assertThat(months(me, 2024)).isEmpty();
        mockMvc.perform(get("/api/v1/events/months").with(me)).andExpect(status().isBadRequest());
    }

    private List<Integer> months(RequestPostProcessor who, int year) throws Exception {
        String body = mockMvc.perform(get("/api/v1/events/months").param("year", String.valueOf(year)).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$");
    }

    private RequestPostProcessor person(String sub) {
        owners.resolve(sub, Consent.current(Clock.systemUTC()));
        return oidcLogin().idToken(t -> t.subject(sub));
    }

    private String firstExpenseCategory(RequestPostProcessor who) throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories").with(who))
                .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.<List<String>>read(body, "$[?(@.type == 'EXPENSE' && @.isSystem == false)].id").get(0);
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
