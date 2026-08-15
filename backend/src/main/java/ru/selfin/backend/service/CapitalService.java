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
 * <p>Два публичных числа ликвидности, и разница между ними — вклад (спека
 * 2026-08-12-accounts-skeleton-design.md §4.3–§4.4, ANO-9 Task 2.3, ANO-46):
 * <pre>
 *   ликвид(t)        = кассовыйЛиквид(t) + semiLiquidAt(t)   ← экран Капитала
 *   кассовыйЛиквид(t)= freeMoneyAt(t) + noAnchorFallbackAt(t)
 *                    + Σ балансов копилок БЕЗ account_id     ← прогнозы: /strategy, /wishlist
 *   обязательства(t) = creditDebtAt(t) + Σ CapitalItem(LIABILITY)
 * </pre>
 * Кто на каком числе сидит и почему — в Javadoc {@link #cashLiquidAt}.
 * {@code freeMoneyAt}/{@code semiLiquidAt}/{@code creditDebtAt} — {@link AccountBalanceService},
 * единственное место правила «остаток счёта на дату». {@code CapitalService} больше НЕ дублирует
 * это правило инлайн (раньше дублировал — через {@code sumFactByTypeBetween}, у которого не
 * было фильтра {@code wishlistStatus}, см. историю в Javadoc {@link #liquidAt}).
 *
 * <p>Копилки С {@code account_id} в ликвид отдельно не добавляются: их деньги уже внутри баланса
 * своего счёта (учтены через {@code freeMoneyAt}/{@code semiLiquidAt}) — прибавить их ещё раз
 * значило бы задвоить (спека §3.3, §4.4). Фильтр {@code fund.account_id IS NULL} живёт в запросе
 * {@link FundTransactionRepository#sumEnvelopeFundsByTransactionDateLessThanEqual}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CapitalService {

    private final CapitalItemRepository itemRepo;
    private final CapitalRevaluationRepository revRepo;
    private final BalanceCheckpointRepository checkpointRepo;
    private final FundTransactionRepository fundTxRepo;
    private final AccountBalanceService accountBalanceService;

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
        BigDecimal liabilitiesTotal = liabilitiesAt(today, sums);
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
            BigDecimal liabilities = liabilitiesAt(t, sums);
            result.add(new CapitalTrajectoryDto.Point(
                    t, liquid.add(assets).subtract(liabilities), liquid, assets, liabilities));
        }
        return new CapitalTrajectoryDto(result);
    }

    /**
     * Самая ранняя дата переоценки капитала. Null если переоценок нет.
     * Используется StrategyTimelineService.firstActivityMonth().
     */
    public Optional<LocalDate> findEarliestRevaluationDate() {
        return revRepo.findEarliestValuedAt();
    }

    // === helpers ===

    private BigDecimal capitalAt(LocalDate t) {
        BigDecimal liquid = liquidAt(t);
        Map<CapitalItemKind, BigDecimal> sums = sumByKindAt(t);
        BigDecimal assets = sums.getOrDefault(CapitalItemKind.ASSET, BigDecimal.ZERO);
        BigDecimal liabilities = liabilitiesAt(t, sums);
        return liquid.add(assets).subtract(liabilities);
    }

    private Map<CapitalItemKind, BigDecimal> sumByKindAt(LocalDate t) {
        Map<CapitalItemKind, BigDecimal> result = new HashMap<>();
        for (CapitalSnapshotProjection p : revRepo.snapshotAt(t)) {
            result.merge(p.getKind(), p.getValue(), BigDecimal::add);
        }
        return result;
    }

    /**
     * Обязательства на дату {@code t} = ручные {@code CapitalItem(LIABILITY)} + долг по
     * кредитным счетам, посчитанный от лимита и доступного (спека §4.4). Единая точка для
     * {@code summary}/{@code capitalAt}/{@code trajectory} — обе части обязаны считаться
     * согласованно во всех трёх местах, а не разъезжаться.
     */
    private BigDecimal liabilitiesAt(LocalDate t, Map<CapitalItemKind, BigDecimal> sums) {
        return sums.getOrDefault(CapitalItemKind.LIABILITY, BigDecimal.ZERO)
                .add(accountBalanceService.creditDebtAt(t));
    }

    /**
     * Жидкий баланс на дату {@code t} (спека §4.4, ANO-9 Task 2.3):
     * <pre>
     *   ликвид(t) = freeMoneyAt(t) + semiLiquidAt(t) + noAnchorFallbackAt(t)
     *             + Σ балансов копилок БЕЗ account_id
     * </pre>
     * Публичный API для согласования с другими сервисами (например, StrategyTimelineService
     * использует этот метод для seed {@code balanceConfirmed}).
     *
     * <p><b>История правки.</b> До ANO-9 Task 2.3 счёт считался инлайн через
     * {@code checkpointRepo.findTopByDateLessThanEqualOrderByDateDesc(t)} +
     * {@code eventRepo.sumFactByTypeBetween(...)} — второй запрос НЕ фильтровал
     * {@code wishlistStatus}, в отличие от {@link AccountBalanceService#balanceAt} /
     * {@link PocketEngine#calculate}, которые факты по хотелкам из баланса исключают
     * сознательно. Из-за этого факт по хотелке (нечастый, но реальный случай — см.
     * {@code FinancialEventService}, хотелки конвертируются с сохранением факта) молча
     * уменьшал/увеличивал ликвид капитала, хотя кармашек его игнорировал — капитал и кармашек
     * показывали разную ликвидность на одних и тех же данных. Переход на
     * {@code accountBalanceService.freeMoneyAt} убирает дублирование правила отбора фактов
     * (теперь оно живёт только в {@link AccountBalanceService#factsDelta}) и синхронизирует
     * это поведение с кармашком.
     *
     * <p><b>{@code noAnchorFallbackAt} — правка того же ревью, что нашла дефект выше.</b>
     * Первая версия перехода на {@code freeMoneyAt} без этого слагаемого молча ЗАВЕЛА новый
     * дефект вместо старого: {@code balanceAt} без чекпоинта возвращает ноль (§6 спеки — счёт
     * без чекпоинта «молчит»), а не сумму фактов с нулевой базы, как раньше делал
     * {@code EPOCH_SENTINEL}-фолбэк старой формулы и как до сих пор делает
     * {@link PocketEngine#calculate} для кармашка (ANO-28: у пользователя без единого введённого
     * остатка вся история фактов не должна исчезнуть из числа). На реальных данных (факт дохода
     * 90 000, чекпоинта нет) ликвид капитала обнулялся вместо 90 000 — капитал и кармашек снова
     * разошлись, ровно там, где пользователь новый. {@link AccountBalanceService#noAnchorFallbackAt}
     * возвращает эту сумму ТОЛЬКО когда у дефолтного счёта нет чекпоинта — иначе ноль, без
     * двойного счёта поверх {@code freeMoneyAt}.
     */
    public BigDecimal liquidAt(LocalDate t) {
        return cashLiquidAt(t).add(accountBalanceService.semiLiquidAt(t));
    }

    /**
     * Кассовый ликвид на дату {@code t} — то же, что {@link #liquidAt}, но <b>БЕЗ вкладов</b>
     * (ANO-46, решение пользователя 2026-08-15):
     * <pre>
     *   кассовыйЛиквид(t) = freeMoneyAt(t) + noAnchorFallbackAt(t)
     *                     + Σ балансов копилок БЕЗ account_id
     * </pre>
     *
     * <p><b>Зачем два числа.</b> Спека §4.3 сознательно держит вклад вне основного числа
     * кармашка: он показывается отдельной сноской мелким шрифтом, потому что вкладом не
     * планируют жить. Но кассовый график стратегии и зоны риска хотелок сидели на
     * {@link #liquidAt} — и вклад молча оказывался в кумулятивном балансе как обычные
     * деньги. Одна сущность считалась по-разному на соседних экранах: человек, проедающий
     * 10 000 в месяц при вкладе 1 500 000, не получал предупреждения о разрыве ближайшие
     * 12 лет, хотя кармашек показывал ему только остаток карты.
     *
     * <p>Формулировка решения: «Вклад, конечно, может помочь пережить месяц, но это должно
     * быть осознанным решением пользователя, а не предполагаемое действие». Поэтому вклад
     * остался ровно в одном месте — на экране Капитала ({@link #liquidAt}, §4.4), где он и
     * есть имущество. Все прогнозные потребители переведены сюда:
     * {@link BaselineTimelineBuilder} (кассовый график /strategy) и
     * {@link WishlistSimulationService} (в том числе «потолок кредита» — он падает, и это
     * принято осознанно).
     *
     * <p>Дефект не проявлялся до чанка 3 только потому, что DEPOSIT-счёт нечем было создать:
     * CRUD появляется в этом же чанке.
     */
    public BigDecimal cashLiquidAt(LocalDate t) {
        BigDecimal accountsCash = accountBalanceService.freeMoneyAt(t)
                .add(accountBalanceService.noAnchorFallbackAt(t));
        BigDecimal pocketBalance = fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(t);
        return accountsCash.add(pocketBalance);
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
