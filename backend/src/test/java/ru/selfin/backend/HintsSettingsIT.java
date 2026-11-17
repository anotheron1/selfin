package ru.selfin.backend;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Подсказки первого дня (срез 2, п. 12): закрытые и выключатель — на сервере, ключ «hints» в настройках человека,
 * чтобы телефон и компьютер видели одно.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HintsSettingsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;

    @Test
    void hints_onByDefault_putThenGet_badBody400() throws Exception {
        mockMvc.perform(get("/api/v1/settings/hints"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"off":false,"closed":[]}""", true));

        mockMvc.perform(put("/api/v1/settings/hints").contentType(MediaType.APPLICATION_JSON).content("""
                        {"off":false,"closed":["number","slope"]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/hints"))
                .andExpect(content().json("""
                        {"off":false,"closed":["number","slope"]}""", true));

        // «Скрыть все подсказки» — выключатель; закрытые не теряются.
        mockMvc.perform(put("/api/v1/settings/hints").contentType(MediaType.APPLICATION_JSON).content("""
                        {"off":true,"closed":["number","slope"]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/hints"))
                .andExpect(content().json("""
                        {"off":true,"closed":["number","slope"]}""", true));

        mockMvc.perform(put("/api/v1/settings/hints").contentType(MediaType.APPLICATION_JSON).content("""
                        {"off":false}"""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/settings/hints").contentType(MediaType.APPLICATION_JSON).content("""
                        {"off":false,"closed":["<script>"]}"""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/settings/hints"))
                .andExpect(content().json("""
                        {"off":true,"closed":["number","slope"]}""", true));
    }
}
