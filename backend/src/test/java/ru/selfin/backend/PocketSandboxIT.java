package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Контрактные IT примерки POST /pocket/sandbox (спека sandbox §10):
 * структура, направление удара, exclude, дельта-инвариант §4 день-в-день, валидация.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PocketSandboxIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TargetFundRepository fundRepo;
    @Autowired PlatformTransactionManager txManager;

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> work.get());
    }

    private JsonNode sandbox(String jsonBody) throws Exception {
        String body = mockMvc.perform(post("/api/v1/pocket/sandbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private UUID createFixedSavingsFund(String name, long target, LocalDate targetDate) {
        return inTx(() -> fundRepo.save(TargetFund.builder()
                .name(name)
                .targetAmount(BigDecimal.valueOf(target))
                .currentBalance(BigDecimal.ZERO)
                .targetDate(targetDate)
                .purchaseType(FundPurchaseType.SAVINGS)
                .status(FundStatus.FUNDING)
                .wishlistStatus(WishlistStatus.FIXED)
                .build()).getId());
    }

    private void hardDeleteFund(UUID id) {
        inTx(() -> { fundRepo.deleteById(id); return null; });
    }

    /** Дельта-инвариант §4: fitted[d] = baseline[d] + префикс-сумма дельт по дням. */
    private void assertPrefixInvariant(JsonNode r) {
        JsonNode base = r.get("baseline").get("trajectory");
        JsonNode fitted = r.get("fitted").get("trajectory");
        assertThat(fitted.size()).isEqualTo(base.size());
        for (int i = 0; i < base.size(); i++) {
            String date = base.get(i).get("date").asText();
            assertThat(fitted.get(i).get("date").asText()).isEqualTo(date);
            BigDecimal prefix = BigDecimal.ZERO;
            for (JsonNode item : r.get("itemDeltas")) {
                for (JsonNode day : item.get("days")) {
                    if (day.get("date").asText().compareTo(date) <= 0) {
                        prefix = prefix.add(day.get("delta").decimalValue());
                    }
                }
            }
            assertThat(fitted.get(i).get("balance").decimalValue())
                    .as("день %s", date)
                    .isEqualByComparingTo(base.get(i).get("balance").decimalValue().add(prefix));
        }
    }

    @Test
    void emptySandbox_baselineEqualsFitted_structureOk() throws Exception {
        JsonNode r = sandbox("""
                {"scope":"MONTHS:3","tryOn":[],"exclude":[]}
                """);
        assertThat(r.get("baseline").get("pocket").decimalValue())
                .isEqualByComparingTo(r.get("fitted").get("pocket").decimalValue());
        assertThat(r.get("itemDeltas")).isEmpty();
        assertThat(r.get("items").isArray()).isTrue();
        assertThat(r.get("baseline").get("trajectory").isArray()).isTrue();
        assertThat(r.get("fitted").get("breakdown").isArray()).isTrue();
    }

    @Test
    void adhocTryOn_cutsFittedPocket_invariantHolds() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        JsonNode r = sandbox("""
                {"scope":"MONTHS:3","tryOn":[{"amount":12000,"date":"%s"}],"exclude":[]}
                """.formatted(d));
        BigDecimal base = r.get("baseline").get("pocket").decimalValue();
        BigDecimal fitted = r.get("fitted").get("pocket").decimalValue();
        assertThat(base.subtract(fitted)).isEqualByComparingTo(new BigDecimal("12000"));
        assertPrefixInvariant(r);
    }

    @Test
    void includedFund_thenExcluded_pocketRestores_invariantHolds() throws Exception {
        // Копилка в baseline (после §6); exclude «возвращает» деньги
        UUID fundId = createFixedSavingsFund("IT-sandbox-копилка", 60_000,
                LocalDate.now().plusMonths(2));
        try {
            JsonNode with = sandbox("""
                    {"scope":"MONTHS:3","tryOn":[],"exclude":[]}
                    """);
            JsonNode without = sandbox("""
                    {"scope":"MONTHS:3","tryOn":[],
                     "exclude":[{"type":"FUND","id":"%s"}]}
                    """.formatted(fundId));

            BigDecimal withPocket = with.get("baseline").get("pocket").decimalValue();
            BigDecimal withoutFitted = without.get("fitted").get("pocket").decimalValue();
            assertThat(withoutFitted.subtract(withPocket))
                    .isEqualByComparingTo(new BigDecimal("60000.00"));
            // items: копилка с inBaseline=true
            boolean found = false;
            for (JsonNode item : without.get("items")) {
                if (fundId.toString().equals(item.get("ref").get("id").asText())) {
                    assertThat(item.get("inBaseline").asBoolean()).isTrue();
                    assertThat(item.get("kind").asText()).isEqualTo("SAVINGS");
                    found = true;
                }
            }
            assertThat(found).isTrue();
            assertPrefixInvariant(without);
        } finally {
            hardDeleteFund(fundId);
        }
    }

    @Test
    void tryOnBaselineElementWithoutExclude_400() throws Exception {
        UUID fundId = createFixedSavingsFund("IT-sandbox-дубль", 50_000,
                LocalDate.now().plusMonths(2));
        try {
            mockMvc.perform(post("/api/v1/pocket/sandbox")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"scope":"MONTHS:3",
                                     "tryOn":[{"ref":{"type":"FUND","id":"%s"},
                                               "amount":50000,"date":"%s"}],
                                     "exclude":[]}
                                    """.formatted(fundId, LocalDate.now().plusMonths(2))))
                    .andExpect(status().isBadRequest());
        } finally {
            hardDeleteFund(fundId);
        }
    }

    @Test
    void validation_pastDateAndGarbageScope_400() throws Exception {
        mockMvc.perform(post("/api/v1/pocket/sandbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"MONTHS:3","tryOn":[{"amount":100,"date":"%s"}],"exclude":[]}
                                """.formatted(LocalDate.now())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/pocket/sandbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"GARBAGE","tryOn":[],"exclude":[]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
