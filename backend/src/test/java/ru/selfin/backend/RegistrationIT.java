package ru.selfin.backend;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.Consent;
import ru.selfin.backend.service.OwnerService;

import javax.sql.DataSource;
import java.time.Clock;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Регистрация (ANO-151): новый человек, дав согласие (ANO-196), получает стартовый набор — ядро категорий v4 и счёт
 * по умолчанию — одной транзакцией со своей строкой {@code users}.
 *
 * <p>{@code jdbc} — роль владельца таблиц, мимо RLS: тест видит строки любого человека.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-15-starter-set-design.md
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RegistrationIT {

    /** Набор — имена из спеки, а не из кода: тест сверяет код с решением, а не код с самим собой. */
    static final String[] STARTER_ONLY = {"Жильё и быт", "Связь и подписки", "Образование", "Обязательные платежи",
            "Продукты", "Кафе и доставка", "Забота о себе", "Покупки", "Досуг и поездки", "Подарки и помощь",
            "Подработка"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired @FlywayDataSource DataSource tableOwner;
    @Autowired OwnerService owners;

    @Test
    @DisplayName("новый человек — ядро категорий v4, бронь у четырёх и у зарплаты; второй запрос второго набора не заводит")
    void newPersonGetsCoreCategories() throws Exception {
        consent("core-person");
        for (int request = 1; request <= 2; request++) {
            List<Map<String, Object>> categories = JsonPath.read(ok(get("/api/v1/categories"), "core-person"), "$");
            assertThat(categories).as("запрос %d", request)
                    .extracting(c -> c.get("name"), c -> c.get("type"), c -> c.get("priority"))
                    .containsExactlyInAnyOrder(
                            tuple("Жильё и быт", "EXPENSE", "HIGH"),
                            tuple("Связь и подписки", "EXPENSE", "HIGH"),
                            tuple("Образование", "EXPENSE", "HIGH"),
                            tuple("Обязательные платежи", "EXPENSE", "HIGH"),
                            tuple("Транспорт", "EXPENSE", "MEDIUM"),
                            tuple("Продукты", "EXPENSE", "MEDIUM"),
                            tuple("Кафе и доставка", "EXPENSE", "MEDIUM"),
                            tuple("Здоровье", "EXPENSE", "MEDIUM"),
                            tuple("Забота о себе", "EXPENSE", "MEDIUM"),
                            tuple("Покупки", "EXPENSE", "MEDIUM"),
                            tuple("Досуг и поездки", "EXPENSE", "MEDIUM"),
                            tuple("Подарки и помощь", "EXPENSE", "MEDIUM"),
                            tuple("Прочее", "EXPENSE", "MEDIUM"),
                            tuple("Зарплата", "INCOME", "HIGH"),
                            tuple("Подработка", "INCOME", "MEDIUM"),
                            tuple("Прочий доход", "INCOME", "MEDIUM"));
            assertThat(categories).as("без прогноза (решение 05.09), без основного дохода, не системные")
                    .allSatisfy(c -> assertThat(c).containsEntry("forecastEnabled", false)
                            .containsEntry("primaryIncome", false).containsEntry("isSystem", false));
        }
    }

    @Test
    @DisplayName("новый человек — счёт «Основная карта» по умолчанию: остаток вводится без выбора счёта")
    void newPersonGetsDefaultAccount() throws Exception {
        consent("account-person");
        List<Map<String, Object>> accounts = JsonPath.read(ok(get("/api/v1/accounts"), "account-person"), "$");
        assertThat(accounts).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("name", "Основная карта").containsEntry("kind", "DEBIT")
                .containsEntry("trackBalance", true).containsEntry("isDefault", true));

        String checkpoint = ok(post("/api/v1/balance-checkpoints").contentType(MediaType.APPLICATION_JSON).content("""
                {"date":"%s","amount":15000}""".formatted(LocalDate.now().minusDays(1))), "account-person");
        assertThat(JsonPath.<String>read(checkpoint, "$.accountName")).isEqualTo("Основная карта");
    }

    @Test
    @DisplayName("синтетический владелец при связи набора не получает: данные у него свои")
    void syntheticOwnerGetsNoSet() throws Exception {
        long before = liveCategories(OwnerService.SYNTHETIC_OWNER);
        // Пользователь MockMvc по умолчанию — selfin.owner-sub: его первый запрос связывает синтетического владельца.
        String body = mvc.perform(get("/api/v1/categories")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].name")).doesNotContain(STARTER_ONLY);
        assertThat(liveCategories(OwnerService.SYNTHETIC_OWNER)).isEqualTo(before);
    }

    @Test
    @DisplayName("одной транзакцией: пока набор первого заведения не лёг, второе заведение того же человека ждёт")
    void secondFirstRequestWaitsForTheSet() throws Exception {
        String sub = "racing-person";
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (Connection lock = tableOwner.getConnection()) {
            lock.setAutoCommit(false);
            // Счёт набора не вставится, пока замок не снят: первый запрос встанет внутри своей транзакции.
            lock.createStatement().execute("LOCK TABLE accounts IN EXCLUSIVE MODE");
            CompletableFuture<UUID> first = CompletableFuture.supplyAsync(() -> owners.resolve(sub, Consent.current(Clock.systemUTC())), threads);
            awaitLockWait("insert into accounts", first);

            CompletableFuture<Long> second = CompletableFuture.supplyAsync(
                    () -> liveCategories(owners.resolve(sub, Consent.current(Clock.systemUTC()))), threads);
            awaitLockWait("insert into users", second);
            assertThat(second).as("второй ждёт строку первого, а не читает пустого человека").isNotDone();

            lock.rollback();
            assertThat(second.get(10, TimeUnit.SECONDS)).as("второй увидел человека уже с набором").isEqualTo(16L);
            assertThat(owners.resolve(sub)).isEqualTo(first.get(10, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }

    // ── помощники ───────────────────────────────────────────────────────────────

    private String ok(MockHttpServletRequestBuilder request, String sub) throws Exception {
        return mvc.perform(request.with(oidcLogin().idToken(t -> t.subject(sub))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    /** Человек заводится с согласием (ANO-196) — так его заводит обработчик входа после галочки. */
    private void consent(String sub) {
        owners.resolve(sub, Consent.current(Clock.systemUTC()));
    }

    private long liveCategories(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM categories WHERE user_id = ? AND NOT is_deleted",
                Long.class, owner);
    }

    /** Ждёт, пока запрос встанет на замке на этой вставке; закончился раньше — значит, ждать ему было нечего. */
    private void awaitLockWait(String statement, CompletableFuture<?> request) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && !request.isDone(); attempt++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?
                    """, Long.class, statement + "%");
            if (waiting > 0) return;
            Thread.sleep(50);
        }
        fail("запрос не встал на «%s»: %s", statement, request.isDone() ? "закончился сразу" : "не дошёл за 10 с");
    }
}
