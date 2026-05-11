package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.capital.*;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.CapitalItem;
import ru.selfin.backend.model.CapitalRevaluation;
import ru.selfin.backend.model.enums.CapitalItemKind;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Доменный сервис модуля «Капитал».
 *
 * <p>Все мутации (CRUD по items и revaluations) — {@code @Transactional}.
 * Чтения (summary, trajectory, list, history) — readOnly на классе.
 *
 * <p>{@code liquidAt(date)} вычисляется здесь же как приватный метод: переиспользует
 * существующие репозитории. Формула «безопасная», без зависимости от инварианта
 * FUND_TRANSFER ↔ FundTransaction 1:1:
 * <pre>
 *   liquid(t) = AccountBalance(t) + Σ FundBalance(t)
 *   AccountBalance(t) = checkpoint(≤t).amount
 *                     + Σ INCOME факт events ∈ [checkpoint.date, t]
 *                     − Σ EXPENSE факт events ∈ [checkpoint.date, t]
 *                     − Σ FUND_TRANSFER факт events ∈ [checkpoint.date, t]
 *   Σ FundBalance(t) = Σ FundTransaction.amount, transaction_date ≤ t, не deleted
 * </pre>
 *
 * <p>Диапазон событий начинается от {@code checkpoint.date} включительно — это
 * соответствует конвенции {@link TargetFundService#calcPocketBalance()} и гарантирует,
 * что Capital и Dashboard показывают одно и то же значение жидкой части.
 * Если когда-нибудь окажется, что чекпоинт хранит баланс на конец дня (то есть события
 * этой даты в нём уже учтены) — нужно будет одновременно сдвинуть и эту, и
 * {@link TargetFundService#calcPocketBalance()} формулы на {@code +1 day}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CapitalService {

    /** Безопасная нижняя граница для JPA date binding, когда checkpoint'а нет. */
    private static final LocalDate EPOCH_SENTINEL = LocalDate.of(1970, 1, 1);

    private final CapitalItemRepository itemRepo;
    private final CapitalRevaluationRepository revRepo;
    private final BalanceCheckpointRepository checkpointRepo;
    private final FinancialEventRepository eventRepo;
    private final FundTransactionRepository fundTxRepo;

    // === CRUD: items ===

    @Transactional
    public CapitalItemDto create(CapitalItemCreateDto dto) {
        CapitalItem item = CapitalItem.builder()
                .kind(dto.kind())
                .name(dto.name())
                .description(dto.description())
                .build();
        item = itemRepo.save(item);

        LocalDate valuedAt = dto.initialValuedAt() != null ? dto.initialValuedAt() : LocalDate.now();
        CapitalRevaluation rev = CapitalRevaluation.builder()
                .itemId(item.getId())
                .value(dto.initialValue())
                .valuedAt(valuedAt)
                .build();
        revRepo.save(rev);

        return toItemDto(item, rev);
    }

    public List<CapitalItemDto> list(CapitalItemKind kind, boolean includeArchived) {
        return itemRepo.findAllActive(kind).stream()
                .map(this::loadAndMap)
                .filter(dto -> includeArchived || !dto.isArchived())
                .toList();
    }

    public CapitalItemDto get(UUID id) {
        CapitalItem item = itemRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", id));
        return loadAndMap(item);
    }

    @Transactional
    public CapitalItemDto update(UUID id, CapitalItemUpdateDto dto) {
        CapitalItem item = itemRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", id));
        if (dto.name() != null) item.setName(dto.name());
        if (dto.description() != null) item.setDescription(dto.description());
        return loadAndMap(itemRepo.save(item));
    }

    @Transactional
    public void delete(UUID id) {
        CapitalItem item = itemRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", id));
        item.setDeleted(true);
        itemRepo.save(item);
    }

    // === CRUD: revaluations ===

    @Transactional
    public CapitalRevaluationDto addRevaluation(UUID itemId, CapitalRevaluationCreateDto dto) {
        CapitalItem item = itemRepo.findActiveById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalItem", itemId));

        LocalDate valuedAt = dto.valuedAt() != null ? dto.valuedAt() : LocalDate.now();
        CapitalRevaluation rev = CapitalRevaluation.builder()
                .itemId(item.getId())
                .value(dto.value())
                .valuedAt(valuedAt)
                .note(dto.note())
                .build();
        return toRevDto(revRepo.save(rev));
    }

    public List<CapitalRevaluationDto> getHistory(UUID itemId) {
        return revRepo.findHistoryByItemId(itemId).stream().map(this::toRevDto).toList();
    }

    @Transactional
    public CapitalRevaluationDto updateRevaluation(UUID id, CapitalRevaluationUpdateDto dto) {
        CapitalRevaluation rev = revRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalRevaluation", id));
        if (dto.value() != null) rev.setValue(dto.value());
        if (dto.valuedAt() != null) rev.setValuedAt(dto.valuedAt());
        if (dto.note() != null) rev.setNote(dto.note());
        return toRevDto(revRepo.save(rev));
    }

    @Transactional
    public void deleteRevaluation(UUID id) {
        CapitalRevaluation rev = revRepo.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CapitalRevaluation", id));
        rev.setDeleted(true);
        revRepo.save(rev);
    }

    // === aggregates ===

    public CapitalSummaryDto summary() {
        LocalDate today = LocalDate.now();

        BigDecimal liquid = liquidAt(today);
        Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(today);
        BigDecimal assetsTotal = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
        BigDecimal liabilitiesTotal = sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO);
        BigDecimal total = liquid.add(assetsTotal).subtract(liabilitiesTotal);

        List<CapitalItemDto> items = list(null, true); // все, включая архивные — UI решит

        BigDecimal capitalMonthAgo   = capitalAt(today.minusMonths(1));
        BigDecimal capitalQuarterAgo = capitalAt(today.minusMonths(3));
        BigDecimal capitalYearAgo    = capitalAt(today.minusYears(1));

        return new CapitalSummaryDto(
                total, liquid, assetsTotal, liabilitiesTotal, items,
                new CapitalSummaryDto.Deltas(
                        total.subtract(capitalMonthAgo),
                        total.subtract(capitalQuarterAgo),
                        total.subtract(capitalYearAgo)));
    }

    public CapitalTrajectoryDto trajectory(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate effectiveTo = to != null ? to : today;
        LocalDate effectiveFrom = from != null
                ? from
                : revRepo.findEarliestValuedAt()
                    .orElseGet(() -> checkpointRepo.findTopByOrderByDateAsc()
                        .map(BalanceCheckpoint::getDate)
                        .orElse(today));
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be <= to");
        }

        List<LocalDate> points = buildMonthEndPoints(effectiveFrom, effectiveTo);
        if (points.isEmpty() || !points.get(points.size() - 1).equals(today)) {
            points.add(today);
        }

        List<CapitalTrajectoryDto.Point> result = new ArrayList<>();
        for (LocalDate t : points) {
            BigDecimal liquid = liquidAt(t);
            Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(t);
            BigDecimal assets = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
            BigDecimal liabilities = sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO);
            result.add(new CapitalTrajectoryDto.Point(
                    t, liquid.add(assets).subtract(liabilities), liquid, assets, liabilities));
        }
        return new CapitalTrajectoryDto(result);
    }

    // === helpers ===

    private BigDecimal capitalAt(LocalDate t) {
        BigDecimal liquid = liquidAt(t);
        Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(t);
        BigDecimal assets = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
        BigDecimal liabilities = sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO);
        return liquid.add(assets).subtract(liabilities);
    }

    private Map<CapitalItemKind, BigDecimal> sumByKindAt(LocalDate t) {
        Map<CapitalItemKind, BigDecimal> result = new HashMap<>();
        for (CapitalSnapshotProjection p : revRepo.snapshotAt(t)) {
            result.merge(p.getKind(), p.getValue(), BigDecimal::add);
        }
        return result;
    }

    private BigDecimal liquidAt(LocalDate t) {
        Optional<BalanceCheckpoint> latest = checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(t);
        BigDecimal start = latest.map(BalanceCheckpoint::getAmount).orElse(BigDecimal.ZERO);
        // Конвенция: события включаются начиная с даты чекпоинта (см. Javadoc класса
        // и TargetFundService.calcPocketBalance — обе формулы должны двигаться вместе).
        LocalDate fromDate = latest.map(BalanceCheckpoint::getDate).orElse(EPOCH_SENTINEL);

        BigDecimal income       = eventRepo.sumFactByTypeBetween(EventType.INCOME,        fromDate, t);
        BigDecimal expense      = eventRepo.sumFactByTypeBetween(EventType.EXPENSE,       fromDate, t);
        BigDecimal fundTransfer = eventRepo.sumFactByTypeBetween(EventType.FUND_TRANSFER, fromDate, t);
        BigDecimal accountBalance = start.add(income).subtract(expense).subtract(fundTransfer);

        BigDecimal pocketBalance = fundTxRepo.sumByTransactionDateLessThanEqual(t);

        return accountBalance.add(pocketBalance);
    }

    private List<LocalDate> buildMonthEndPoints(LocalDate from, LocalDate to) {
        List<LocalDate> result = new ArrayList<>();
        YearMonth ym = YearMonth.from(from);
        YearMonth toYm = YearMonth.from(to);
        while (!ym.isAfter(toYm)) {
            LocalDate eom = ym.atEndOfMonth();
            if (!eom.isBefore(from) && !eom.isAfter(to)) result.add(eom);
            ym = ym.plusMonths(1);
        }
        return result;
    }

    private CapitalItemDto loadAndMap(CapitalItem item) {
        List<CapitalRevaluation> history = revRepo.findHistoryByItemId(item.getId());
        CapitalRevaluation last = history.isEmpty() ? null : history.get(0);
        return toItemDto(item, last);
    }

    private CapitalItemDto toItemDto(CapitalItem item, CapitalRevaluation last) {
        BigDecimal currentValue = last != null ? last.getValue() : BigDecimal.ZERO;
        LocalDate lastValuedAt = last != null ? last.getValuedAt() : null;
        boolean isArchived = currentValue.signum() == 0;
        return new CapitalItemDto(
                item.getId(), item.getKind(), item.getName(), item.getDescription(),
                item.getCreatedAt().toInstant(ZoneOffset.UTC),
                currentValue, lastValuedAt, isArchived);
    }

    private CapitalRevaluationDto toRevDto(CapitalRevaluation r) {
        return new CapitalRevaluationDto(
                r.getId(), r.getItemId(), r.getValue(), r.getValuedAt(), r.getNote(),
                r.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
