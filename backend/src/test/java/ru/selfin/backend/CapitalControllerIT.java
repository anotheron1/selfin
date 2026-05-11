package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.repository.CapitalItemRepository;
import ru.selfin.backend.repository.CapitalRevaluationRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CapitalControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;
    @Autowired CapitalItemRepository itemRepo;
    @Autowired CapitalRevaluationRepository revRepo;

    @AfterEach
    void cleanDb() {
        revRepo.deleteAll();
        itemRepo.deleteAll();
    }

    @Test
    void createItem_thenAppearsInList() throws Exception {
        String body = """
                {"kind":"ASSET","name":"Квартира","initialValue":8500000}
                """;
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.kind").value("ASSET"))
                .andExpect(jsonPath("$.currentValue").value(8500000))
                .andExpect(jsonPath("$.isArchived").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/v1/capital/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
    }

    @Test
    void createItem_negativeInitialValue_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":-1}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createItem_futureValuedAt_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":1000,"initialValuedAt":"2099-01-01"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addRevaluation_thenItemCurrentValueUpdates() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Volvo","initialValue":1400000}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":1200000,"note":"Авито"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capital/items/" + id))
                .andExpect(jsonPath("$.currentValue").value(1200000));
    }

    @Test
    void addRevaluation_zeroValue_archivesItem() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Old car","initialValue":500000}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":0}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capital/items"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());

        mockMvc.perform(get("/api/v1/capital/items?includeArchived=true"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].isArchived").value(true));
    }

    @Test
    void deleteItem_returns404OnSubsequentGet() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Test","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(delete("/api/v1/capital/items/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/capital/items/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void getItem_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/capital/items/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void addRevaluation_onSoftDeletedItem_returns404() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(delete("/api/v1/capital/items/" + id)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":200}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void addRevaluation_futureDate_returns400() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/capital/items/" + id + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":200,"valuedAt":"2099-01-01"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRevaluation_softDeletes_excludesFromHistory() throws Exception {
        String created = mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"X","initialValue":100}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String itemId = om.readTree(created).get("id").asText();

        String revBody = mockMvc.perform(post("/api/v1/capital/items/" + itemId + "/revaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":200}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String revId = om.readTree(revBody).get("id").asText();

        mockMvc.perform(delete("/api/v1/capital/revaluations/" + revId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/capital/items/" + itemId + "/revaluations"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value(100));
    }

    @Test
    void trajectory_fromGreaterThanTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/capital/trajectory?from=2026-05-01&to=2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summary_emptyDb_returnsZeroes() throws Exception {
        mockMvc.perform(get("/api/v1/capital/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.assetsTotal").value(0))
                .andExpect(jsonPath("$.liabilitiesTotal").value(0))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void summary_withAssetAndLiability_computesTotal() throws Exception {
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"ASSET","name":"Квартира","initialValue":8500000}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"LIABILITY","name":"Ипотека","initialValue":4800000}
                        """)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capital/summary"))
                .andExpect(jsonPath("$.assetsTotal").value(8500000))
                .andExpect(jsonPath("$.liabilitiesTotal").value(4800000))
                .andExpect(jsonPath("$.total").value(3700000));
    }

    @Test
    void trajectory_withRetroactiveRevaluation_buildsHistory() throws Exception {
        String pastDate = java.time.LocalDate.now().minusMonths(6).withDayOfMonth(1).toString();
        mockMvc.perform(post("/api/v1/capital/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"kind":"ASSET","name":"Backfill","initialValue":1000000,"initialValuedAt":"%s"}
                        """, pastDate)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capital/trajectory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()").value(org.hamcrest.Matchers.greaterThan(1)));
    }
}
