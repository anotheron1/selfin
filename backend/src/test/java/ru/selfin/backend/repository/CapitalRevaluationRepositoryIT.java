package ru.selfin.backend.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.CapitalRevaluation;
import ru.selfin.backend.model.enums.CapitalItemKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class CapitalRevaluationRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired CapitalItemRepository itemRepo;
    @Autowired CapitalRevaluationRepository revRepo;

    // @SpringBootTest reuses the DB across tests in this class. Tests like
    // findEarliestValuedAt_emptyDb_returnsEmpty assume an empty table — clean
    // explicitly between methods.
    @AfterEach
    void cleanDb() {
        revRepo.deleteAll();
        itemRepo.deleteAll();
    }

    @Test
    void snapshotAt_returnsLatestRevaluationForEachActiveItem() {
        CapitalItem flat = saveItem(CapitalItemKind.ASSET, "Квартира");
        CapitalItem car  = saveItem(CapitalItemKind.ASSET, "Volvo");
        CapitalItem mort = saveItem(CapitalItemKind.LIABILITY, "Ипотека");

        saveRev(flat.getId(), bd("8000000"), date(2026, 1, 1));
        saveRev(flat.getId(), bd("8500000"), date(2026, 4, 1));
        saveRev(car.getId(),  bd("1400000"), date(2026, 1, 1));
        saveRev(car.getId(),  bd("1200000"), date(2026, 3, 1));
        saveRev(mort.getId(), bd("5000000"), date(2026, 1, 1));
        saveRev(mort.getId(), bd("4800000"), date(2026, 3, 1));

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 3, 2));
        Map<UUID, BigDecimal> byId = snapshot.stream()
                .collect(Collectors.toMap(CapitalSnapshotProjection::getItemId, CapitalSnapshotProjection::getValue));

        assertThat(byId.get(flat.getId())).isEqualByComparingTo("8000000");
        assertThat(byId.get(car.getId())).isEqualByComparingTo("1200000");
        assertThat(byId.get(mort.getId())).isEqualByComparingTo("4800000");
    }

    @Test
    void snapshotAt_excludesItemsWithoutAnyRevaluationOnOrBeforeDate() {
        CapitalItem newCar = saveItem(CapitalItemKind.ASSET, "Новая машина");
        saveRev(newCar.getId(), bd("2000000"), date(2026, 5, 1));

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 4, 1));

        assertThat(snapshot).extracting(CapitalSnapshotProjection::getItemId).doesNotContain(newCar.getId());
    }

    @Test
    void snapshotAt_excludesSoftDeletedRevaluations() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Test");
        saveRev(item.getId(), bd("1000"), date(2026, 1, 1));
        CapitalRevaluation deleted = saveRev(item.getId(), bd("9999"), date(2026, 2, 1));
        deleted.setDeleted(true);
        revRepo.save(deleted);

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 3, 1));

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getValue()).isEqualByComparingTo("1000");
    }

    @Test
    void snapshotAt_excludesSoftDeletedItems() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Test");
        saveRev(item.getId(), bd("1000"), date(2026, 1, 1));
        item.setDeleted(true);
        itemRepo.save(item);

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 3, 1));

        assertThat(snapshot).extracting(CapitalSnapshotProjection::getItemId).doesNotContain(item.getId());
    }

    @Test
    void snapshotAt_tieBreaksByCreatedAtWhenSameValuedAt() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Tie-break test");
        saveRevAt(item.getId(), bd("100"), date(2026, 1, 1), LocalDateTime.now().minusMinutes(5));
        saveRevAt(item.getId(), bd("200"), date(2026, 1, 1), LocalDateTime.now());

        List<CapitalSnapshotProjection> snapshot = revRepo.snapshotAt(date(2026, 1, 1));

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getValue()).isEqualByComparingTo("200");
    }

    @Test
    void findHistoryByItemId_returnsNewestFirstAndExcludesDeleted() {
        CapitalItem item = saveItem(CapitalItemKind.ASSET, "Test");
        saveRev(item.getId(), bd("100"), date(2026, 1, 1));
        saveRev(item.getId(), bd("200"), date(2026, 3, 1));
        CapitalRevaluation deleted = saveRev(item.getId(), bd("999"), date(2026, 2, 1));
        deleted.setDeleted(true);
        revRepo.save(deleted);

        List<CapitalRevaluation> history = revRepo.findHistoryByItemId(item.getId());

        assertThat(history).extracting(CapitalRevaluation::getValuedAt)
                .containsExactly(date(2026, 3, 1), date(2026, 1, 1));
    }

    @Test
    void findEarliestValuedAt_emptyDb_returnsEmpty() {
        assertThat(revRepo.findEarliestValuedAt()).isEmpty();
    }

    private CapitalItem saveItem(CapitalItemKind kind, String name) {
        return itemRepo.save(CapitalItem.builder().kind(kind).name(name).build());
    }

    private CapitalRevaluation saveRev(UUID itemId, BigDecimal value, LocalDate at) {
        return revRepo.save(CapitalRevaluation.builder()
                .itemId(itemId).value(value).valuedAt(at).build());
    }

    private CapitalRevaluation saveRevAt(UUID itemId, BigDecimal value, LocalDate at, LocalDateTime createdAt) {
        return revRepo.save(CapitalRevaluation.builder()
                .itemId(itemId).value(value).valuedAt(at).createdAt(createdAt).build());
    }

    private static LocalDate date(int y, int m, int d) { return LocalDate.of(y, m, d); }
    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
