package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.SandboxRef;
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
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Примерка «что если» на кармашке (спека sandbox §3–§4): тот же вход, что GET /pocket,
 * минус выключенные FIXED-элементы, плюс развёрнутая синтетика tryOn — и движок дважды.
 * Никакой собственной математики поверх {@link PocketEngine} и {@link SandboxLayout}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PocketSandboxService {

    /** Описание ad-hoc траты в траектории/drivenBy. */
    private static final String ADHOC_DESCRIPTION = "Примерка";

    private final PocketInputAssembler assembler;
    private final FinancialEventRepository eventRepository;
    private final TargetFundRepository fundRepository;

    public SandboxResponseDto simulate(SandboxRequestDto req, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(req.scope());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }

        PocketInputAssembler.Assembled base = assembler.build(scope, asOfDate);
        PocketResultDto baseline = PocketEngine.calculate(base.input());

        // ── валидация §9 ────────────────────────────────────────────────────
        Set<SandboxRef> excludeSet = new HashSet<>(req.exclude());
        for (SandboxRef ref : excludeSet) {
            if (!base.baselineRefs().containsKey(ref)) {
                throw badRequest("exclude ref не сидит в baseline: " + ref.id());
            }
        }
        List<ResolvedTryOn> tryOns = new ArrayList<>();
        for (TryOnDto t : req.tryOn()) {
            tryOns.add(validateTryOn(t, base, excludeSet, asOfDate));
        }

        // ── fitted-вход: события − excluded + синтетика tryOn ───────────────
        Set<EventSnapshot> removed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (SandboxRef ref : excludeSet) {
            removed.addAll(base.baselineRefs().get(ref));
        }
        List<EventSnapshot> fittedEvents = new ArrayList<>();
        for (EventSnapshot e : base.input().events()) {
            if (!removed.contains(e)) fittedEvents.add(e);
        }
        for (ResolvedTryOn t : tryOns) {
            fittedEvents.addAll(t.synthetics);
        }
        // tryOn-refs выкидываются из wishlist-выборки fitted: включённая хотелка режет
        // траекторию синтетикой и не должна одновременно висеть в кандидатах (§4)
        Set<UUID> tryOnEventIds = new HashSet<>();
        for (ResolvedTryOn t : tryOns) {
            if (t.dto.ref() != null && t.dto.ref().type() == SandboxRef.RefType.EVENT) {
                tryOnEventIds.add(t.dto.ref().id());
            }
        }
        List<EventSnapshot> fittedWishlist = base.input().wishlistEvents().stream()
                .filter(e -> !tryOnEventIds.contains(e.id()))
                .toList();

        PocketInput in = base.input();
        PocketInput fittedInput = new PocketInput(in.asOfDate(), in.checkpointAmount(),
                in.checkpointDate(), fittedEvents, fittedWishlist, in.overdueEvents(),
                in.scope(), in.horizonEnd(), in.fallbackKind(), in.bufferAmount(),
                in.unplannedForecast(), in.forecastContributors());
        PocketResultDto fitted = PocketEngine.calculate(fittedInput);

        // ── дельта-векторы (§4): tryOn в порядке запроса, затем exclude ─────
        List<ItemDeltaDto> itemDeltas = new ArrayList<>();
        for (ResolvedTryOn t : tryOns) {
            itemDeltas.add(new ItemDeltaDto(t.dto.ref(), sparseDays(t.synthetics, true)));
        }
        for (SandboxRef ref : req.exclude()) {
            itemDeltas.add(new ItemDeltaDto(ref, sparseDays(base.baselineRefs().get(ref), false)));
        }

        return new SandboxResponseDto(baseline, fitted, itemDeltas,
                buildItems(base, asOfDate));
    }

    // ── валидация и раскладка одного tryOn ──────────────────────────────────

    private record ResolvedTryOn(TryOnDto dto, List<EventSnapshot> synthetics) {}

    private ResolvedTryOn validateTryOn(TryOnDto t, PocketInputAssembler.Assembled base,
                                        Set<SandboxRef> excludeSet, LocalDate asOfDate) {
        if (t.amount() == null || t.amount().signum() <= 0) {
            throw badRequest("tryOn.amount должен быть > 0");
        }
        if (t.date() == null || !t.date().isAfter(asOfDate)) {
            throw badRequest("tryOn.date обязательна и строго в будущем");
        }
        boolean credit = t.creditRate() != null || t.creditTermMonths() != null;
        int stretch = t.stretchMonths() != null ? t.stretchMonths() : 0;
        if (stretch < 0) throw badRequest("stretchMonths не может быть отрицательным");
        if (credit) {
            if (stretch >= 1) throw badRequest("растяжка и кредит взаимоисключающи");
            if (t.creditRate() == null || t.creditTermMonths() == null || t.creditTermMonths() < 1) {
                throw badRequest("кредиту нужны ставка и срок ≥ 1");
            }
        } else if (stretch >= 1) {
            int max = SandboxLayout.maxStretchMonths(asOfDate, t.date());
            if (stretch > max) {
                throw badRequest("stretchMonths вне диапазона [0.." + Math.max(max, 0) + "]");
            }
        }

        String description = ADHOC_DESCRIPTION;
        if (t.ref() != null) {
            description = resolveRef(t.ref());
            if (base.baselineRefs().containsKey(t.ref()) && !excludeSet.contains(t.ref())) {
                throw badRequest("элемент уже сидит в baseline — нужен парный exclude: " + t.ref().id());
            }
        }

        List<EventSnapshot> synthetics;
        if (credit) {
            synthetics = SandboxLayout.layoutCredit(description, t.amount(), t.date(),
                    t.creditRate(), t.creditTermMonths());
        } else if (stretch >= 1) {
            synthetics = SandboxLayout.layoutSavings(description, t.amount(), t.date(), stretch,
                    asOfDate, plannedIncomeDates(base.input()),
                    ru.selfin.backend.dto.pocket.SyntheticKind.TRY_ON);
        } else {
            synthetics = SandboxLayout.layoutOneOff(description, t.amount(), t.date());
        }
        return new ResolvedTryOn(t, synthetics);
    }

    /** Живой, не-DISMISSED, неконвертированный wishlist-элемент; иначе 400. Возвращает имя. */
    private String resolveRef(SandboxRef ref) {
        if (ref.id() == null || ref.type() == null) throw badRequest("ref без type/id");
        if (ref.type() == SandboxRef.RefType.EVENT) {
            FinancialEvent e = eventRepository.findById(ref.id())
                    .filter(x -> !x.isDeleted())
                    .orElseThrow(() -> badRequest("неизвестный ref: " + ref.id()));
            if (e.getWishlistStatus() == null || e.getWishlistStatus() == WishlistStatus.DISMISSED
                    || e.getConvertedToEventId() != null || e.getConvertedToFundId() != null) {
                throw badRequest("ref не является живой хотелкой: " + ref.id());
            }
            return e.getDescription() != null && !e.getDescription().isBlank()
                    ? e.getDescription() : ADHOC_DESCRIPTION;
        }
        TargetFund f = fundRepository.findById(ref.id())
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> badRequest("неизвестный ref: " + ref.id()));
        if (f.getWishlistStatus() == null || f.getWishlistStatus() == WishlistStatus.DISMISSED
                || f.getConvertedToEventId() != null || f.getConvertedToFundId() != null) {
            throw badRequest("ref не является живой копилкой: " + ref.id());
        }
        return f.getName();
    }

    /** Даты плановых доходов из уже выбранных событий траектории (§5) — без похода в БД. */
    private static List<LocalDate> plannedIncomeDates(PocketInput input) {
        return input.events().stream()
                .filter(e -> e.type() == EventType.INCOME && e.wishlistStatus() == null
                        && e.factAmount() == null
                        && e.eventKind() == EventKind.PLAN && e.status() == EventStatus.PLANNED
                        && e.date() != null)
                .map(EventSnapshot::date)
                .sorted()
                .toList();
    }

    /** Агрегирует синтетику/убранные события в разреженный вектор по датам (знак: расход −, возврат +). */
    private static List<DayDeltaDto> sparseDays(List<EventSnapshot> events, boolean negate) {
        TreeMap<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (EventSnapshot e : events) {
            BigDecimal amount = e.plannedAmount() != null ? e.plannedAmount() : BigDecimal.ZERO;
            BigDecimal signed = e.type() == EventType.INCOME ? amount : amount.negate();
            if (!negate) signed = signed.negate(); // возврат excluded: знак обращается
            byDate.merge(e.date(), signed, BigDecimal::add);
        }
        return byDate.entrySet().stream()
                .map(en -> new DayDeltaDto(en.getKey(), en.getValue()))
                .toList();
    }

    // ── items (§4): список окна с дефолтами ─────────────────────────────────

    private List<SandboxItemDto> buildItems(PocketInputAssembler.Assembled base, LocalDate asOfDate) {
        List<SandboxItemDto> items = new ArrayList<>();
        for (EventSnapshot e : base.input().wishlistEvents()) {
            if (e.wishlistStatus() == WishlistStatus.DISMISSED || e.converted()) continue;
            SandboxRef ref = SandboxRef.event(e.id());
            items.add(new SandboxItemDto(ref, "WISHLIST",
                    e.description(), e.plannedAmount(), e.date(),
                    e.date() != null ? Math.max(SandboxLayout.maxStretchMonths(asOfDate, e.date()), 0) : null,
                    0, null, null,
                    e.wishlistStatus() != null ? e.wishlistStatus().name() : null,
                    base.baselineRefs().containsKey(ref)));
        }
        for (TargetFund f : fundRepository.findAllWishlistFunds()) {
            if (f.getWishlistStatus() == WishlistStatus.DISMISSED
                    || f.getConvertedToEventId() != null || f.getConvertedToFundId() != null) continue;
            SandboxRef ref = SandboxRef.fund(f.getId());
            boolean credit = f.getPurchaseType() == FundPurchaseType.CREDIT;
            BigDecimal remaining = f.getTargetAmount() != null
                    ? f.getTargetAmount().subtract(f.getCurrentBalance()) : null;
            Integer max = f.getTargetDate() != null
                    ? Math.max(SandboxLayout.maxStretchMonths(asOfDate, f.getTargetDate()), 0) : null;
            items.add(new SandboxItemDto(ref, credit ? "CREDIT" : "SAVINGS",
                    f.getName(), remaining, f.getTargetDate(),
                    max, credit ? 0 : (max != null ? max : 0),
                    f.getCreditRate(), f.getCreditTermMonths(),
                    f.getWishlistStatus() != null ? f.getWishlistStatus().name() : null,
                    base.baselineRefs().containsKey(ref)));
        }
        return items;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
