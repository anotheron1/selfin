package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.SandboxRef;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.dto.pocket.sandbox.DayDeltaDto;
import ru.selfin.backend.dto.pocket.sandbox.ItemDeltaDto;
import ru.selfin.backend.dto.pocket.sandbox.SandboxItemDto;
import ru.selfin.backend.dto.pocket.sandbox.SandboxRequestDto;
import ru.selfin.backend.dto.pocket.sandbox.SandboxResponseDto;
import ru.selfin.backend.dto.pocket.sandbox.TryOnDto;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты PocketSandboxService (спека sandbox §3–§4, §9): подмена входа, валидация,
 * дельта-векторы, префикс-инвариант. Ассемблер мокается (вход строится руками,
 * как в PocketEngineTest), движок настоящий.
 */
class PocketSandboxServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    private PocketInputAssembler assembler;
    private FinancialEventRepository eventRepository;
    private TargetFundRepository fundRepository;
    private PocketSandboxService service;

    @BeforeEach
    void setUp() {
        assembler = mock(PocketInputAssembler.class);
        eventRepository = mock(FinancialEventRepository.class);
        fundRepository = mock(TargetFundRepository.class);
        when(fundRepository.findAllWishlistFunds()).thenReturn(List.of());
        service = new PocketSandboxService(assembler, eventRepository, fundRepository);
    }

    // ── фикстуры ────────────────────────────────────────────────────────────

    private void assembled(PocketInput input, Map<SandboxRef, List<EventSnapshot>> refs) {
        when(assembler.build(any(), any()))
                .thenReturn(new PocketInputAssembler.Assembled(input, refs));
    }

    /** Базовый вход: чекпоинт 10 000 на 1.03, скоуп MONTHS:3 до 1.06. */
    private PocketEngineTest.PocketInputBuilder base() {
        return PocketEngineTest.PocketInputBuilder.create()
                .monthsScope(3, LocalDate.of(2026, 6, 1));
    }

    private static EventSnapshot contribution(LocalDate date, long amount, String name) {
        return new EventSnapshot(null, date, EventType.EXPENSE, EventKind.PLAN, EventStatus.PLANNED,
                Priority.MEDIUM, BigDecimal.valueOf(amount), null, null, false, name,
                SyntheticKind.SAVINGS_CONTRIBUTION);
    }

    /** OPEN-хотелка в репозитории (для резолюции ref). */
    private UUID openEventInRepo(String description) {
        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(FinancialEvent.builder()
                .id(id).type(EventType.EXPENSE).eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED).priority(Priority.LOW)
                .plannedAmount(new BigDecimal("8500"))
                .wishlistStatus(WishlistStatus.OPEN).description(description)
                .build()));
        return id;
    }

    private UUID fixedSavingsFundInRepo(String name, long target, long balance, LocalDate targetDate) {
        UUID id = UUID.randomUUID();
        when(fundRepository.findById(id)).thenReturn(Optional.of(TargetFund.builder()
                .id(id).name(name)
                .targetAmount(BigDecimal.valueOf(target))
                .currentBalance(BigDecimal.valueOf(balance))
                .targetDate(targetDate)
                .purchaseType(FundPurchaseType.SAVINGS)
                .wishlistStatus(WishlistStatus.FIXED)
                .build()));
        return id;
    }

    private static TryOnDto adhoc(long amount, LocalDate date) {
        return new TryOnDto(null, BigDecimal.valueOf(amount), date, null, null, null);
    }

    private static SandboxRequestDto req(List<TryOnDto> tryOn, List<SandboxRef> exclude) {
        return new SandboxRequestDto("MONTHS:3", tryOn, exclude);
    }

    private static Map<LocalDate, BigDecimal> byDate(PocketResultDto r) {
        Map<LocalDate, BigDecimal> m = new LinkedHashMap<>();
        for (PocketResultDto.TrajectoryPoint p : r.trajectory()) m.put(p.date(), p.balance());
        return m;
    }

    /** Префикс-инвариант §4: fitted[d] = baseline[d] + Σ по items Σ days≤d. */
    private static void assertPrefixInvariant(SandboxResponseDto resp) {
        Map<LocalDate, BigDecimal> base = byDate(resp.baseline());
        Map<LocalDate, BigDecimal> fitted = byDate(resp.fitted());
        assertThat(fitted.keySet()).isEqualTo(base.keySet());
        for (LocalDate d : base.keySet()) {
            BigDecimal prefix = BigDecimal.ZERO;
            for (ItemDeltaDto item : resp.itemDeltas()) {
                for (DayDeltaDto day : item.days()) {
                    if (!day.date().isAfter(d)) prefix = prefix.add(day.delta());
                }
            }
            assertThat(fitted.get(d))
                    .as("день %s", d)
                    .isEqualByComparingTo(base.get(d).add(prefix));
        }
    }

    // ── happy paths ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Пустая примерка: baseline = fitted, дельт нет, items собраны")
    void emptySandbox_baselineEqualsFitted() {
        assembled(base().build(), Map.of());
        SandboxResponseDto r = service.simulate(req(List.of(), List.of()), TODAY);

        assertThat(r.fitted().pocket()).isEqualByComparingTo(r.baseline().pocket());
        assertThat(r.itemDeltas()).isEmpty();
        assertThat(r.items()).isNotNull();
    }

    @Test
    @DisplayName("Ad-hoc разовая внутри горизонта: fitted.pocket = baseline − сумма; дельта разреженная")
    void adhocOneOff_cutsPocket() {
        assembled(base().build(), Map.of());
        SandboxResponseDto r = service.simulate(
                req(List.of(adhoc(3_000, LocalDate.of(2026, 3, 10))), List.of()), TODAY);

        assertThat(r.baseline().pocket()).isEqualByComparingTo("10000");
        assertThat(r.fitted().pocket()).isEqualByComparingTo("7000");
        assertThat(r.itemDeltas()).hasSize(1);
        assertThat(r.itemDeltas().get(0).ref()).isNull();
        assertThat(r.itemDeltas().get(0).days()).containsExactly(
                new DayDeltaDto(LocalDate.of(2026, 3, 10), new BigDecimal("-3000")));
        assertPrefixInvariant(r);
    }

    @Test
    @DisplayName("Растяжка OPEN-хотелки n=2: взносы в первые доходы месяцев, префикс-инвариант держится")
    void stretchedTryOn_prefixInvariantHolds() {
        // Доход 10.04 в траектории — день взноса апреля; май без дохода → 1-е
        PocketInput input = base()
                .events(new EventSnapshot(UUID.randomUUID(), LocalDate.of(2026, 4, 10),
                        EventType.INCOME, EventKind.PLAN, EventStatus.PLANNED, Priority.MEDIUM,
                        BigDecimal.valueOf(50_000), null, null, false, "зп"))
                .build();
        assembled(input, Map.of());
        UUID openId = openEventInRepo("Рюкзак");

        SandboxResponseDto r = service.simulate(req(List.of(
                new TryOnDto(SandboxRef.event(openId), new BigDecimal("8500"),
                        LocalDate.of(2026, 5, 20), 2, null, null)), List.of()), TODAY);

        assertThat(r.itemDeltas()).hasSize(1);
        assertThat(r.itemDeltas().get(0).days()).extracting(DayDeltaDto::date).containsExactly(
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 1));
        assertPrefixInvariant(r);
    }

    @Test
    @DisplayName("Растяжка с целью за горизонтом: взносы за горизонтом не бьют по кармашку скоупа")
    void stretchBeyondHorizon_zeroImpactOnShortScope() {
        assembled(base().horizon(LocalDate.of(2026, 3, 15)).build(), Map.of());
        UUID openId = openEventInRepo("Рюкзак");

        SandboxResponseDto r = service.simulate(req(List.of(
                new TryOnDto(SandboxRef.event(openId), new BigDecimal("8500"),
                        LocalDate.of(2026, 8, 20), 5, null, null)), List.of()), TODAY);

        assertThat(r.fitted().pocket()).isEqualByComparingTo(r.baseline().pocket());
    }

    @Test
    @DisplayName("Exclude FIXED-копилки: fitted.pocket больше baseline, дельта положительная")
    void excludeFixedFund_returnsMoney() {
        UUID fundId = UUID.randomUUID();
        List<EventSnapshot> contribs = List.of(
                contribution(LocalDate.of(2026, 3, 20), 16_000, "Горнолыжка"),
                contribution(LocalDate.of(2026, 4, 20), 16_000, "Горнолыжка"));
        List<EventSnapshot> events = new ArrayList<>(contribs);
        assembled(base().events(events.toArray(EventSnapshot[]::new)).build(),
                Map.of(SandboxRef.fund(fundId), contribs));

        SandboxResponseDto r = service.simulate(
                req(List.of(), List.of(SandboxRef.fund(fundId))), TODAY);

        assertThat(r.baseline().pocket()).isEqualByComparingTo("-22000"); // 10000 − 32000
        assertThat(r.fitted().pocket()).isEqualByComparingTo("10000");
        assertThat(r.itemDeltas()).hasSize(1);
        assertThat(r.itemDeltas().get(0).days()).containsExactly(
                new DayDeltaDto(LocalDate.of(2026, 3, 20), new BigDecimal("16000")),
                new DayDeltaDto(LocalDate.of(2026, 4, 20), new BigDecimal("16000")));
        assertPrefixInvariant(r);
    }

    @Test
    @DisplayName("Покрутить FIXED = exclude + tryOn парой: работает, инвариант держится")
    void retuneFixed_excludePlusTryOn() {
        UUID fundId = fixedSavingsFundInRepo("Горнолыжка", 80_000, 0, LocalDate.of(2026, 5, 20));
        List<EventSnapshot> contribs = List.of(
                contribution(LocalDate.of(2026, 4, 1), 40_000, "Горнолыжка"),
                contribution(LocalDate.of(2026, 5, 1), 40_000, "Горнолыжка"));
        assembled(base().events(contribs.toArray(EventSnapshot[]::new)).build(),
                Map.of(SandboxRef.fund(fundId), contribs));

        SandboxResponseDto r = service.simulate(req(
                List.of(new TryOnDto(SandboxRef.fund(fundId), new BigDecimal("60000"),
                        LocalDate.of(2026, 5, 20), 2, null, null)),
                List.of(SandboxRef.fund(fundId))), TODAY);

        // Было −70 000 к минимуму (80 000 взносов), стало 60 000 взносами → fitted выше baseline
        assertThat(r.fitted().pocket()).isGreaterThan(r.baseline().pocket());
        assertThat(r.itemDeltas()).hasSize(2); // tryOn затем exclude
        assertPrefixInvariant(r);
    }

    @Test
    @DisplayName("Кредит: PMT-серия режет fitted, выдача-покупка нетто 0")
    void creditTryOn_pmtSeries() {
        assembled(base().build(), Map.of());
        UUID openId = openEventInRepo("Машина-мини");

        SandboxResponseDto r = service.simulate(req(List.of(
                new TryOnDto(SandboxRef.event(openId), new BigDecimal("300000"),
                        LocalDate.of(2026, 3, 10), null, new BigDecimal("12.0"), 3)), List.of()), TODAY);

        BigDecimal pmt = SandboxLayout.monthlyPmt(new BigDecimal("300000"), new BigDecimal("12.0"), 3);
        assertThat(r.itemDeltas().get(0).days()).extracting(DayDeltaDto::date).containsExactly(
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 10));
        assertThat(r.itemDeltas().get(0).days()).extracting(DayDeltaDto::delta)
                .allSatisfy(d -> assertThat(d).isEqualByComparingTo(pmt.negate()));
        assertPrefixInvariant(r);
    }

    @Test
    @DisplayName("tryOn OPEN-хотелки чистит её из fitted-кандидатов и WISHLIST_INFO")
    void tryOnOpenEvent_removedFromFittedCandidates() {
        UUID openId = openEventInRepo("Рюкзак");
        EventSnapshot candidate = new EventSnapshot(openId, null, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.LOW, new BigDecimal("8500"), null,
                WishlistStatus.OPEN, false, "Рюкзак");
        assembled(base().wishlist(candidate).build(), Map.of());

        SandboxResponseDto r = service.simulate(req(List.of(
                new TryOnDto(SandboxRef.event(openId), new BigDecimal("8500"),
                        LocalDate.of(2026, 3, 10), null, null, null)), List.of()), TODAY);

        assertThat(r.baseline().wishlistCandidates()).extracting(PocketResultDto.WishlistCandidate::id)
                .contains(openId);
        assertThat(r.fitted().wishlistCandidates()).extracting(PocketResultDto.WishlistCandidate::id)
                .doesNotContain(openId);
        assertThat(r.fitted().breakdown()).noneMatch(l -> l.type() == BreakdownType.WISHLIST_INFO);
    }

    @Test
    @DisplayName("Позитивный край §9: FIXED-копилка, выпавшая из baseline по краям §6, принимает tryOn без exclude")
    void fundOutOfBaselineByEdges_tryOnWithoutExcludeOk() {
        UUID fundId = fixedSavingsFundInRepo("Протухла", 50_000, 0, LocalDate.of(2026, 2, 10));
        assembled(base().build(), Map.of()); // копилки нет в baselineRefs (протухшая цель)

        SandboxResponseDto r = service.simulate(req(List.of(
                new TryOnDto(SandboxRef.fund(fundId), new BigDecimal("50000"),
                        LocalDate.of(2026, 4, 10), null, null, null)), List.of()), TODAY);

        assertThat(r.fitted().pocket()).isLessThan(r.baseline().pocket());
    }

    // ── items ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("items: дефолты per kind + операциональный inBaseline")
    void items_defaultsAndInBaseline() {
        UUID fundId = UUID.randomUUID();
        TargetFund fund = TargetFund.builder()
                .id(fundId).name("Горнолыжка")
                .targetAmount(new BigDecimal("80000"))
                .currentBalance(new BigDecimal("20000"))
                .targetDate(LocalDate.of(2026, 5, 20))
                .purchaseType(FundPurchaseType.SAVINGS)
                .wishlistStatus(WishlistStatus.FIXED)
                .build();
        TargetFund credit = TargetFund.builder()
                .id(UUID.randomUUID()).name("Машина")
                .targetAmount(new BigDecimal("1300000"))
                .currentBalance(BigDecimal.ZERO)
                .purchaseType(FundPurchaseType.CREDIT)
                .creditRate(new BigDecimal("18.0")).creditTermMonths(60)
                .wishlistStatus(WishlistStatus.FIXED)
                .build();
        when(fundRepository.findAllWishlistFunds()).thenReturn(List.of(fund, credit));

        UUID openId = UUID.randomUUID();
        EventSnapshot candidate = new EventSnapshot(openId, null, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.LOW, new BigDecimal("8500"), null,
                WishlistStatus.OPEN, false, "Рюкзак");
        List<EventSnapshot> contribs = List.of(
                contribution(LocalDate.of(2026, 4, 1), 30_000, "Горнолыжка"));
        assembled(base().wishlist(candidate).events(contribs.toArray(EventSnapshot[]::new)).build(),
                Map.of(SandboxRef.fund(fundId), contribs));

        SandboxResponseDto r = service.simulate(req(List.of(), List.of()), TODAY);

        SandboxItemDto event = r.items().stream()
                .filter(i -> i.ref().equals(SandboxRef.event(openId))).findFirst().orElseThrow();
        assertThat(event.kind()).isEqualTo("WISHLIST");
        assertThat(event.amount()).isEqualByComparingTo("8500");
        assertThat(event.date()).isNull();
        assertThat(event.stretchMonthsDefault()).isZero();
        assertThat(event.inBaseline()).isFalse();

        SandboxItemDto savings = r.items().stream()
                .filter(i -> i.ref().equals(SandboxRef.fund(fundId))).findFirst().orElseThrow();
        assertThat(savings.kind()).isEqualTo("SAVINGS");
        assertThat(savings.amount()).isEqualByComparingTo("60000"); // остаток
        assertThat(savings.stretchMonthsMax()).isEqualTo(2);        // апр, май
        assertThat(savings.stretchMonthsDefault()).isEqualTo(2);
        assertThat(savings.inBaseline()).isTrue();

        SandboxItemDto cr = r.items().stream()
                .filter(i -> "CREDIT".equals(i.kind())).findFirst().orElseThrow();
        assertThat(cr.creditRate()).isEqualByComparingTo("18.0");
        assertThat(cr.creditTermMonths()).isEqualTo(60);
        assertThat(cr.inBaseline()).isFalse();
    }

    // ── валидация §9 ────────────────────────────────────────────────────────

    private void expect400(SandboxRequestDto request) {
        assertThatThrownBy(() -> service.simulate(request, TODAY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("400: tryOn без даты / дата не в будущем / сумма ≤ 0")
    void validation_dateAndAmount() {
        assembled(base().build(), Map.of());
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"), null, null, null, null)), List.of()));
        expect400(req(List.of(adhoc(100, TODAY)), List.of()));
        expect400(req(List.of(adhoc(100, TODAY.minusDays(1))), List.of()));
        expect400(req(List.of(adhoc(0, LocalDate.of(2026, 3, 10))), List.of()));
        expect400(req(List.of(adhoc(-5, LocalDate.of(2026, 3, 10))), List.of()));
    }

    @Test
    @DisplayName("400: растяжка вне диапазона и растяжка вместе с кредитом")
    void validation_stretchAndCredit() {
        assembled(base().build(), Map.of());
        // max для 20.05 = 2 (апр, май) → 3 недопустимо
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), 3, null, null)), List.of()));
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), -1, null, null)), List.of()));
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), 2, new BigDecimal("12.0"), 12)), List.of()));
        // кредит без срока / с нулевым сроком
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), null, new BigDecimal("12.0"), null)), List.of()));
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), null, new BigDecimal("12.0"), 0)), List.of()));
    }

    @Test
    @DisplayName("400: неизвестный / DISMISSED / конвертированный ref")
    void validation_refs() {
        assembled(base().build(), Map.of());
        when(eventRepository.findById(any())).thenReturn(Optional.empty());
        expect400(req(List.of(new TryOnDto(SandboxRef.event(UUID.randomUUID()),
                new BigDecimal("100"), LocalDate.of(2026, 3, 10), null, null, null)), List.of()));

        UUID dismissed = UUID.randomUUID();
        when(eventRepository.findById(dismissed)).thenReturn(Optional.of(FinancialEvent.builder()
                .id(dismissed).wishlistStatus(WishlistStatus.DISMISSED).build()));
        expect400(req(List.of(new TryOnDto(SandboxRef.event(dismissed),
                new BigDecimal("100"), LocalDate.of(2026, 3, 10), null, null, null)), List.of()));

        UUID converted = UUID.randomUUID();
        when(fundRepository.findById(converted)).thenReturn(Optional.of(TargetFund.builder()
                .id(converted).name("x").wishlistStatus(WishlistStatus.FIXED)
                .convertedToFundId(UUID.randomUUID()).build()));
        expect400(req(List.of(new TryOnDto(SandboxRef.fund(converted),
                new BigDecimal("100"), LocalDate.of(2026, 3, 10), null, null, null)), List.of()));
    }

    @Test
    @DisplayName("400: tryOn baseline-элемента без парного exclude; exclude не из baseline")
    void validation_doubleCountAndForeignExclude() {
        UUID fundId = fixedSavingsFundInRepo("Горнолыжка", 80_000, 0, LocalDate.of(2026, 5, 20));
        List<EventSnapshot> contribs = List.of(
                contribution(LocalDate.of(2026, 4, 1), 40_000, "Горнолыжка"));
        assembled(base().events(contribs.toArray(EventSnapshot[]::new)).build(),
                Map.of(SandboxRef.fund(fundId), contribs));

        // tryOn на сидящий в baseline без exclude → 400
        expect400(req(List.of(new TryOnDto(SandboxRef.fund(fundId), new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), null, null, null)), List.of()));
        // exclude ref, которого нет в baseline → 400
        expect400(req(List.of(), List.of(SandboxRef.fund(UUID.randomUUID()))));
    }

    @Test
    @DisplayName("400: мусорный скоуп")
    void validation_scope() {
        assembled(base().build(), Map.of());
        expect400(new SandboxRequestDto("GARBAGE", List.of(), List.of()));
    }

    @Test
    @DisplayName("400: дубликаты refs в exclude и в tryOn (ревью PR B, findings 1-2)")
    void validation_duplicateRefs() {
        UUID fundId = fixedSavingsFundInRepo("Горнолыжка", 80_000, 0, LocalDate.of(2026, 5, 20));
        List<EventSnapshot> contribs = List.of(
                contribution(LocalDate.of(2026, 4, 1), 40_000, "Горнолыжка"));
        assembled(base().events(contribs.toArray(EventSnapshot[]::new)).build(),
                Map.of(SandboxRef.fund(fundId), contribs));

        // дубликат в exclude задваивал бы положительный вектор → инвариант §4 ломался бы
        expect400(req(List.of(), List.of(SandboxRef.fund(fundId), SandboxRef.fund(fundId))));
        // дубликат ref в tryOn молча задваивал бы элемент (обход защиты §9)
        UUID openId = openEventInRepo("Рюкзак");
        TryOnDto t = new TryOnDto(SandboxRef.event(openId), new BigDecimal("8500"),
                LocalDate.of(2026, 4, 10), null, null, null);
        expect400(req(List.of(t, t), List.of()));
    }

    @Test
    @DisplayName("400: отрицательная кредитная ставка")
    void validation_negativeCreditRate() {
        assembled(base().build(), Map.of());
        expect400(req(List.of(new TryOnDto(null, new BigDecimal("100"),
                LocalDate.of(2026, 5, 20), null, new BigDecimal("-1"), 12)), List.of()));
    }
}
