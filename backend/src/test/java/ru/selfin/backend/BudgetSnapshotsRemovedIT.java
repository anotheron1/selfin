package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-121: снимков бюджета больше нет — вариант А владельца, 28.09.
 *
 * <p>Снимок писался, но не читался: ручка отдавала только даты, сравнения «план изначальный
 * vs факт», которое обещала подпись в «Настройках», не было ни на одном экране. Функция ушла
 * вместе с блоком; накопленные снимки остаются в {@code budget_snapshots} — миграции нет.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-29-budget-snapshots-removed-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BudgetSnapshotsRemovedIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("ANO-121: списка снимков нет — 404")
    void snapshotList_isGone() throws Exception {
        mockMvc.perform(get("/api/v1/snapshots"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ANO-121: снимок не создаётся — 404")
    void snapshotCreation_isGone() throws Exception {
        mockMvc.perform(post("/api/v1/snapshots"))
                .andExpect(status().isNotFound());
    }
}
