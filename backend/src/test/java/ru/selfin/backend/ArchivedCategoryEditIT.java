package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ANO-178: an archived category remains attached to an editable plan. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ArchivedCategoryEditIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void retainsArchivedCategoryWhenChangingAmountAndDate() throws Exception {
        String categoryId = category();
        var body = plan(categoryId);
        String eventId = create(body);
        archive(categoryId);
        LocalDate newDate = LocalDate.now().plusDays(2);
        body.put("date", newDate.toString());
        body.put("plannedAmount", 600);

        mvc.perform(put("/api/v1/events/" + eventId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.categoryName", startsWith("Проба архива ")))
                .andExpect(jsonPath("$.plannedAmount").value(600))
                .andExpect(jsonPath("$.date").value(newDate.toString()));
        mvc.perform(get("/api/v1/events").param("startDate", newDate.toString())
                        .param("endDate", newDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + eventId + "')].categoryId", hasItem(categoryId)))
                .andExpect(jsonPath("$[?(@.id == '" + eventId + "')].plannedAmount", hasItem(600.0)));
    }

    @Test
    void rejectsNewAssignmentsToArchivedCategory() throws Exception {
        String archivedId = category();
        archive(archivedId);
        String eventId = create(plan(category()));
        var body = plan(archivedId);
        mvc.perform(put("/api/v1/events/" + eventId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/events").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void canSwitchFromArchivedCategoryToActiveCategory() throws Exception {
        String archivedId = category();
        String eventId = create(plan(archivedId));
        archive(archivedId);
        String activeId = category();
        mvc.perform(put("/api/v1/events/" + eventId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plan(activeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(activeId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"THIS", "FOLLOWING", "ALL"})
    void editsRecurringPlanWithItsArchivedCategory(String scope) throws Exception {
        String categoryId = category();
        var body = plan(categoryId);
        LocalDate start = LocalDate.parse((String) body.get("date"));
        body.put("recurring", Map.of("frequency", "MONTHLY", "dayOfMonth", start.getDayOfMonth(),
                "startDate", start.toString(), "endDate", start.plusMonths(1).toString()));
        String eventId = create(body);
        archive(categoryId);
        body.remove("recurring"); // The edit form sends scope, not the series configuration.
        body.put("plannedAmount", 600);
        mvc.perform(put("/api/v1/events/" + eventId).param("scope", scope)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.plannedAmount").value(600));
    }

    private String category() throws Exception {
        String result = mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Проба архива " + UUID.randomUUID(),
                                "type", "EXPENSE", "priority", "HIGH"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("id").asText();
    }

    private void archive(String categoryId) throws Exception {
        mvc.perform(delete("/api/v1/categories/" + categoryId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", not(hasItem(categoryId))));
    }

    private Map<String, Object> plan(String categoryId) {
        return new HashMap<>(Map.of("date", LocalDate.now().plusDays(1).toString(),
                "categoryId", categoryId, "type", "EXPENSE", "plannedAmount", 178, "priority", "HIGH"));
    }

    private String create(Map<String, Object> body) throws Exception {
        String result = mvc.perform(post("/api/v1/events").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("id").asText();
    }
}
