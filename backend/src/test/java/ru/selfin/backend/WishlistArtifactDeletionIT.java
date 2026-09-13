package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.service.FinancialEventService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ANO-103, вторая половина. Спека {@code 2026-05-29-wishlist-planning-design.md:66}:
 * «сконвертированный артефакт остаётся (или удаляется по явному выбору)».
 *
 * <p>Первая половина скобок — поведение по умолчанию, вторая — флаг {@code deleteArtifact}.
 * План правки: {@code docs/superpowers/plans/2026-09-12-wishlist-return-artifact.md}.
 *
 * <p>Уровень интеграционный, потому что утверждения — про состояние двух таблиц после
 * транзакции, а не про возвращаемое значение метода.
 */
@SpringBootTest
@Testcontainers
class WishlistArtifactDeletionIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired FinancialEventService eventService;
    @Autowired FinancialEventRepository eventRepository;
    @Autowired TargetFundRepository fundRepository;
    @Autowired CategoryRepository categoryRepository;

    @Test
    @DisplayName("без флага артефакт остаётся и ссылка цела — обратная совместимость")
    void noFlag_keepsArtifactAndLink() {
        FinancialEvent artifact = savePlan("артефакт остаётся", null);
        FinancialEvent wish = saveWish(artifact.getId());

        eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN);

        assertThat(eventRepository.findById(artifact.getId()).orElseThrow().isDeleted())
                .as("артефакт обязан пережить возврат без явного выбора").isFalse();
        assertThat(eventRepository.findById(wish.getId()).orElseThrow().getConvertedToEventId())
                .as("ссылка на артефакт — то, ради чего возврат и задумывался")
                .isEqualTo(artifact.getId());
    }

    @Test
    @DisplayName("с флагом план помечается удалённым и ссылка очищается")
    void withFlag_softDeletesPlanAndClearsLink() {
        FinancialEvent artifact = savePlan("артефакт под удаление", null);
        FinancialEvent wish = saveWish(artifact.getId());

        eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN, true);

        assertThat(eventRepository.findById(artifact.getId()).orElseThrow().isDeleted())
                .as("явный выбор обязан сработать").isTrue();
        FinancialEvent reloaded = eventRepository.findById(wish.getId()).orElseThrow();
        assertThat(reloaded.getConvertedToEventId())
                .as("ссылка в никуда хуже, чем отсутствие ссылки").isNull();
        assertThat(reloaded.getWishlistStatus()).isEqualTo(WishlistStatus.OPEN);
    }

    @Test
    @DisplayName("план с фактами удалить нельзя: 409, траты не осиротеют")
    void planWithFacts_isRefused() {
        FinancialEvent artifact = savePlan("план с тратами", null);
        saveFactFor(artifact);
        FinancialEvent wish = saveWish(artifact.getId());

        assertThatThrownBy(() -> eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        assertThat(eventRepository.findById(artifact.getId()).orElseThrow().isDeleted())
                .as("факты были настоящими тратами — их родитель обязан уцелеть").isFalse();
    }

    @Test
    @DisplayName("копилка с деньгами удалению не подлежит: это был бы ANO-86 по второму адресу")
    void fundWithMoney_isRefused() {
        TargetFund fund = saveFund(new BigDecimal("20000"));
        FinancialEvent wish = saveWishToFund(fund.getId());

        assertThatThrownBy(() -> eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        assertThat(fundRepository.findById(fund.getId()).orElseThrow().isDeleted())
                .as("удалить копилку с деньгами значит повторить ANO-86").isFalse();
    }

    @Test
    @DisplayName("пустая копилка удаляется — отказ именно про деньги, а не про копилки вообще")
    void emptyFund_isDeleted() {
        TargetFund fund = saveFund(BigDecimal.ZERO);
        FinancialEvent wish = saveWishToFund(fund.getId());

        assertThatCode(() -> eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN, true))
                .doesNotThrowAnyException();

        assertThat(fundRepository.findById(fund.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("отказ атомарен: статус тоже не сменился, полусостояния не возникает")
    void refusal_rollsBackStatusToo() {
        FinancialEvent artifact = savePlan("план с тратами", null);
        saveFactFor(artifact);
        FinancialEvent wish = saveWish(artifact.getId());

        assertThatThrownBy(() -> eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN, true))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(eventRepository.findById(wish.getId()).orElseThrow().getWishlistStatus())
                .as("либо оба действия, либо ни одного — иначе наружу нечем сообщить о половине")
                .isEqualTo(WishlistStatus.FIXED);
    }

    @Test
    @DisplayName("возврат БЕЗ флага работает и тогда, когда удаление отклонено — человек не заперт")
    void refusedDeletion_doesNotBlockPlainReturn() {
        FinancialEvent artifact = savePlan("план с тратами", null);
        saveFactFor(artifact);
        FinancialEvent wish = saveWish(artifact.getId());

        assertThatThrownBy(() -> eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN, true))
                .isInstanceOf(ResponseStatusException.class);

        assertThatCode(() -> eventService.setWishlistStatus(wish.getId(), WishlistStatus.OPEN))
                .as("отказ касается удаления, а не возврата — иначе ANO-103 не закрыт")
                .doesNotThrowAnyException();
        assertThat(eventRepository.findById(wish.getId()).orElseThrow().getWishlistStatus())
                .isEqualTo(WishlistStatus.OPEN);
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    private Category expenseCategory() {
        return categoryRepository.findAll().stream()
                .filter(c -> !c.isDeleted())
                .findFirst().orElseThrow();
    }

    private FinancialEvent savePlan(String description, WishlistStatus status) {
        return eventRepository.save(FinancialEvent.builder()
                .idempotencyKey(UUID.randomUUID())
                .eventKind(EventKind.PLAN)
                .type(EventType.EXPENSE)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("10000"))
                .date(LocalDate.now())
                .category(expenseCategory())
                .description(description)
                .priority(Priority.LOW)
                .wishlistStatus(status)
                .build());
    }

    private void saveFactFor(FinancialEvent plan) {
        eventRepository.save(FinancialEvent.builder()
                .idempotencyKey(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .parentEventId(plan.getId())
                .type(EventType.EXPENSE)
                .status(EventStatus.EXECUTED)
                .factAmount(new BigDecimal("4000"))
                .date(LocalDate.now())
                .category(plan.getCategory())
                .description("настоящая трата")
                .priority(Priority.LOW)
                .build());
    }

    /** Хотелка в состоянии «сконвертирована в план». */
    private FinancialEvent saveWish(UUID artifactId) {
        FinancialEvent wish = savePlan("хотелка", WishlistStatus.FIXED);
        wish.setConvertedToEventId(artifactId);
        return eventRepository.save(wish);
    }

    /** Хотелка в состоянии «сконвертирована в копилку». */
    private FinancialEvent saveWishToFund(UUID fundId) {
        FinancialEvent wish = savePlan("хотелка в копилку", WishlistStatus.FIXED);
        wish.setConvertedToFundId(fundId);
        return eventRepository.save(wish);
    }

    private TargetFund saveFund(BigDecimal balance) {
        return fundRepository.save(TargetFund.builder()
                .name("Копилка " + UUID.randomUUID())
                .targetAmount(new BigDecimal("100000"))
                .currentBalance(balance)
                .build());
    }
}
