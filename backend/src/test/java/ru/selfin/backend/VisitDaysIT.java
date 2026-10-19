package ru.selfin.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.controller.WritesData;
import ru.selfin.backend.service.Consent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Дни визита (ANO-102, шаг 13 плана спринта 6): «открывал» ставит любой запрос вошедшего, «записывал» — удачный
 * ответ ручки, которая пишет данные; {@code /me} отдаёт день прошлого визита для строки о перерыве. Сторож
 * разметки: меняющая ручка без {@link WritesData} роняет тест.
 *
 * <p>{@code jdbc} — роль владельца таблиц: проверки мимо RLS, владелец потока — синтетический, тот же, что у
 * вошедшего {@code MockMvc}.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-19-break-hint-design.md
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class VisitDaysIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlers;

    private final LocalDate today = LocalDate.now();

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM visit_days");
        jdbc.update("DELETE FROM categories WHERE user_id IN (SELECT id FROM users WHERE external_id = 'old-consent')");
        jdbc.update("DELETE FROM users WHERE external_id IN ('old-consent', 'fresh-consent')");
    }

    /** Отметки «записывал» за сегодня: строки нет — пусто, «открывал» без записи — {@code false}. */
    private List<Boolean> todayWrote() {
        return jdbc.queryForList("SELECT wrote FROM visit_days WHERE day = ?", Boolean.class, today);
    }

    @Test
    @DisplayName("чтение — «открывал», но не «записывал»")
    void read_marksOpenedOnly() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());

        assertThat(todayWrote()).containsExactly(false);
    }

    @Test
    @DisplayName("удачная запись — «записывал», в той же строке дня")
    void successfulWrite_marksWrote() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Проба дней визита\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(todayWrote()).containsExactly(true);
    }

    @Test
    @DisplayName("отказ записи и примерка — не «записывал»: ни данных, ни ввода")
    void failedWriteAndSandbox_doNotMarkWrote() throws Exception {
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/pocket/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"MONTHS:6\",\"tryOn\":[],\"exclude\":[]}"))
                .andExpect(status().isOk());

        assertThat(todayWrote()).containsExactly(false);
    }

    @Test
    @DisplayName("согласие прежней редакции — дней визита нет: эту цель добавила редакция 2026-10-19 (ревью Codex на #187)")
    void oldConsent_noVisitDays() throws Exception {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, external_id, consent_version, consent_at) VALUES (?, 'old-consent', "
                + "'2026-10-18.2', now()), (?, 'fresh-consent', ?, now())", old, fresh, Consent.CURRENT);

        mvc.perform(get("/api/v1/categories").with(oidcLogin().idToken(t -> t.subject("old-consent"))))
                .andExpect(status().isOk());
        // И запись: «записывал» пишется своим запросом, условие в нём своё.
        mvc.perform(post("/api/v1/categories").with(oidcLogin().idToken(t -> t.subject("old-consent")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Проба прежней редакции\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/categories").with(oidcLogin().idToken(t -> t.subject("fresh-consent"))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT user_id FROM visit_days", UUID.class))
                .as("пишется только принявшему нынешнюю редакцию").containsExactly(fresh);
    }

    @Test
    @DisplayName("/me: lastVisit — последний день визита до сегодняшнего; первый визит — null")
    void me_lastVisitIsDayBeforeToday() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isOk()).andExpect(jsonPath("$.lastVisit").isEmpty());

        // Прошедшие визиты — строками ролью владельца таблиц, владелец потока — тот же, что у вошедшего.
        jdbc.update("INSERT INTO visit_days (day) VALUES (?), (?)", today.minusDays(20), today.minusDays(10));

        mvc.perform(get("/api/v1/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastVisit").value(today.minusDays(10).toString()));
    }

    @Test
    @DisplayName("каждая меняющая ручка размечена @WritesData: новая без разметки не пройдёт")
    void everyChangingEndpointIsMarked() {
        Map<String, Boolean> marks = new TreeMap<>();
        List<String> unmarked = new java.util.ArrayList<>();
        handlers.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/v1/"))
                .forEach(path -> info.getMethodsCondition().getMethods().stream()
                        .filter(m -> m != RequestMethod.GET)
                        .forEach(m -> {
                            WritesData mark = method.getMethodAnnotation(WritesData.class);
                            if (mark == null) unmarked.add(m + " " + path);
                            else marks.put(m + " " + path, mark.value());
                        })));

        assertThat(unmarked).as("меняющие ручки без разметки — пишет или нет").isEmpty();
        assertThat(marks).as("сторож не холостой: меняющих ручек 40").hasSize(40);
        assertThat(marks).as("не пишут: примерка, пересчёт, согласие до входа")
                .containsEntry("POST /api/v1/pocket/sandbox", false)
                .containsEntry("POST /api/v1/wishlist/simulation/recompute", false)
                .containsEntry("POST /api/v1/consent", false);
        assertThat(marks.values().stream().filter(writes -> !writes)).as("не пишут только эти три").hasSize(3);
    }
}
