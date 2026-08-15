package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST-контракт счетов на настоящей базе (план Task 3.2).
 *
 * <p>Отдельно от юнит-тестов сервиса проверяет то, что моками не проверяется: что ограничения
 * V20 не срабатывают раньше валидации сервиса (иначе пользователь получал бы 500 вместо 400) и
 * что перестановка флага «по умолчанию» проходит мимо частичного уникального индекса
 * {@code uq_accounts_single_default} — порядок записи внутри транзакции проверить моком нельзя.
 *
 * <p>В базе после миграции V20 уже есть сид «Основная карта» — дефолтный отслеживаемый DEBIT.
 * Тесты исходят из его существования, а {@link #cleanDb()} возвращает его в исходное состояние.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AccountControllerIT {

    private static final String SEEDED_NAME = "Основная карта";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;
    @Autowired AccountRepository accountRepo;
    @Autowired BalanceCheckpointRepository checkpointRepo;

    private Account seeded() {
        return accountRepo.findAll().stream()
                .filter(a -> SEEDED_NAME.equals(a.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("V20 seed account is missing"));
    }

    @AfterEach
    void cleanDb() {
        UUID seededId = seeded().getId();
        checkpointRepo.findAll().stream()
                .filter(cp -> !seededId.equals(cp.getAccount().getId()))
                .forEach(checkpointRepo::delete);
        accountRepo.findAll().stream()
                .filter(a -> !seededId.equals(a.getId()))
                .forEach(accountRepo::delete);
        // Сид мог потерять флаг в тесте на смену дефолтного — вернуть, иначе следующий тест
        // окажется в системе без счёта-приёмника, чего после V20 быть не должно.
        Account s = seeded();
        if (!s.isDefaultAccount() || s.isDeleted()) {
            s.setDefaultAccount(true);
            s.setDeleted(false);
            accountRepo.saveAndFlush(s);
        }
    }

    private String createAccount(String json) throws Exception {
        String created = mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(created).get("id").asText();
    }

    @Test
    @DisplayName("Создание счёта: имя плюс природа, остальное по дефолту; счёт виден в списке")
    void create_thenAppearsInList() throws Exception {
        String id = createAccount("""
                {"name":"Бензиновая карта","kind":"DEBIT","trackBalance":false}
                """);

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());

        mockMvc.perform(get("/api/v1/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Бензиновая карта"))
                .andExpect(jsonPath("$.kind").value("DEBIT"))
                .andExpect(jsonPath("$.trackBalance").value(false))
                .andExpect(jsonPath("$.isDefault").value(false))
                // за остатком не следим — числа остатка нет, обещать его мы не можем (§5.3)
                .andExpect(jsonPath("$.balance").doesNotExist());
    }

    @Test
    @DisplayName("Миграция V20 отдаёт единственный дефолтный отслеживаемый счёт")
    void list_containsSeededDefaultAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.isDefault == true)].name").value(SEEDED_NAME))
                .andExpect(jsonPath("$[?(@.isDefault == true)].trackBalance").value(true));
    }

    @Test
    @DisplayName("§8: планка выше лимита — 400 от сервиса, а не 500 от CHECK-ограничения базы")
    void create_floorAboveLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Кредитка","kind":"CREDIT","creditLimit":100000,"availableFloor":120000}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("§8: вклад без слежения за остатком — 400, а не 500")
    void create_depositWithoutTracking_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Вклад","kind":"DEPOSIT","trackBalance":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("§3.1: кредитные поля у дебетового счёта — 400, а не 500")
    void create_creditFieldsOnDebit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Карта","kind":"DEBIT","creditLimit":100000}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Пустое имя — 400 от валидации DTO")
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","kind":"DEBIT"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("§8: удаление счёта по умолчанию — 409, сначала назначь другой")
    void delete_defaultAccount_returns409() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/" + seeded().getId()))
                .andExpect(status().isConflict());

        assertThat(seeded().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("§8: удаление обычного счёта — мягкое: пропадает из списка, чекпоинты остаются")
    void delete_regularAccount_softDeletesAndKeepsCheckpoints() throws Exception {
        String id = createAccount("""
                {"name":"Старая карта","kind":"DEBIT"}
                """);
        Account account = accountRepo.findById(UUID.fromString(id)).orElseThrow();
        BalanceCheckpoint cp = checkpointRepo.save(BalanceCheckpoint.builder()
                .date(LocalDate.now().minusDays(1))
                .amount(new BigDecimal("15000.00"))
                .account(account)
                .build());

        mockMvc.perform(delete("/api/v1/accounts/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());
        assertThat(accountRepo.findById(UUID.fromString(id)).orElseThrow().isDeleted()).isTrue();
        assertThat(checkpointRepo.findById(cp.getId())).isPresent();
    }

    @Test
    @DisplayName("Смена счёта по умолчанию проходит мимо частичного уникального индекса: "
            + "старый теряет флаг, новый получает, двух дефолтных не возникает ни на миг")
    void makeDefault_movesFlagWithoutViolatingUniqueIndex() throws Exception {
        String id = createAccount("""
                {"name":"Новая основная","kind":"DEBIT"}
                """);
        UUID seededId = seeded().getId();

        mockMvc.perform(patch("/api/v1/accounts/" + id + "/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(accountRepo.findById(UUID.fromString(id)).orElseThrow().isDefaultAccount()).isTrue();
        assertThat(accountRepo.findById(seededId).orElseThrow().isDefaultAccount()).isFalse();
        assertThat(accountRepo.findByDefaultAccountTrueAndDeletedFalse().orElseThrow().getId())
                .isEqualTo(UUID.fromString(id));
    }

    @Test
    @DisplayName("§8: назначить дефолтным кредитку — 400, сид остаётся приёмником")
    void makeDefault_creditAccount_returns400() throws Exception {
        String id = createAccount("""
                {"name":"Кредитка","kind":"CREDIT","creditLimit":200000}
                """);

        mockMvc.perform(patch("/api/v1/accounts/" + id + "/default"))
                .andExpect(status().isBadRequest());

        assertThat(accountRepo.findByDefaultAccountTrueAndDeletedFalse().orElseThrow().getName())
                .isEqualTo(SEEDED_NAME);
    }

    @Test
    @DisplayName("Кредитка отдаёт доступное и рассчитанный долг, а не введённое число долга")
    void creditAccount_reportsAvailableAndComputedDebt() throws Exception {
        String id = createAccount("""
                {"name":"Кредитка ТБанк","kind":"CREDIT","creditLimit":200000,"availableFloor":150000}
                """);
        Account credit = accountRepo.findById(UUID.fromString(id)).orElseThrow();
        checkpointRepo.save(BalanceCheckpoint.builder()
                .date(LocalDate.now().minusDays(1))
                .amount(new BigDecimal("62000.00")) // доступный остаток, НЕ долг
                .account(credit)
                .build());

        mockMvc.perform(get("/api/v1/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(62000.00))
                .andExpect(jsonPath("$.debt").value(138000.00))
                // доступное ниже планки — это долг к возврату, а не новый уровень планки
                .andExpect(jsonPath("$.floorSuggestion").doesNotExist());
    }

    @Test
    @DisplayName("Обновление счёта: PUT меняет имя и природу, ответ отражает новое состояние")
    void update_changesFields() throws Exception {
        String id = createAccount("""
                {"name":"Карта","kind":"DEBIT"}
                """);

        mockMvc.perform(put("/api/v1/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Наличные","kind":"CASH","trackBalance":false,"sortOrder":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Наличные"))
                .andExpect(jsonPath("$.kind").value("CASH"))
                .andExpect(jsonPath("$.trackBalance").value(false))
                .andExpect(jsonPath("$.sortOrder").value(5));
    }

    @Test
    @DisplayName("Несуществующий счёт — 404")
    void get_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
