package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.FundTransaction;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис управления целевыми фондами накоплений (копилками) и «кармашком».
 *
 * <p><b>Кармашек</b> — свободные деньги, которые не распределены никуда.
 * Отражает реальный незапланированный остаток: «если всё пойдёт по плану,
 * сколько у меня будет свободных денег для распределения».
 *
 * <p>Алгоритм расчёта кармашка зависит от наличия {@code BalanceCheckpoint}:
 * <ul>
 *   <li><b>Чекпоинт есть</b> — стартуем от реального остатка на счёте,
 *       прибавляем эффективные суммы всех событий начиная с даты чекпоинта:</li>
 * </ul>
 * <pre>
 *   кармашек = checkpoint.amount
 *            + Σ(factAmount EXECUTED INCOME, date ≥ checkpoint.date)
 *            − Σ(factAmount EXECUTED EXPENSE, date ≥ checkpoint.date)
 *            − Σ(балансы целевых фондов)
 * </pre>
 * <ul>
 *   <li><b>Чекпоинта нет</b> — только события (обратная совместимость):</li>
 * </ul>
 * <pre>
 *   кармашек = Σ(factAmount всех EXECUTED INCOME) − Σ(factAmount всех EXECUTED EXPENSE) − Σ(балансы фондов)
 * </pre>
 * Учитываются только фактически исполненные события (EXECUTED, factAmount != null).
 * Перевод в копилку автоматически уменьшает кармашек (баланс фонда растёт).
 *
 * <p>Пополнение фондов идемпотентно: повторный вызов с тем же {@code idempotencyKey}
 * вернёт результат первого успешного перевода без двойного зачисления.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TargetFundService {

    private final TargetFundRepository fundRepository;
    private final FundTransactionRepository transactionRepository;
    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final CategoryRepository categoryRepository;

    /** Системное имя фонда-кармашка. */
    private static final String POCKET_NAME = "POCKET";

    /**
     * Возвращает обзор всех фондов: баланс кармашка и список активных целевых фондов
     * с рассчитанным прогнозом достижения цели.
     * <p>
     * Баланс кармашка рассчитывается от последнего {@code BalanceCheckpoint}:
     * если чекпоинт есть — к его сумме прибавляются только события начиная с даты чекпоинта;
     * если чекпоинта нет — суммируются все события (обратная совместимость).
     * Из итога вычитаются балансы всех активных целевых фондов.
     *
     * @return DTO обзора фондов
     */
    public FundsOverviewDto getOverview() {
        List<TargetFund> funds = fundRepository.findAllByDeletedFalseOrderByPriorityAsc();

        BigDecimal fundBalances = funds.stream()
                .filter(f -> !POCKET_NAME.equals(f.getName()))
                .map(TargetFund::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pocketBalance = calcPocketBalance(fundBalances);

        List<TargetFundDto> fundDtos = funds.stream()
                .filter(f -> !POCKET_NAME.equals(f.getName()))
                .map(this::toDto)
                .toList();
        return new FundsOverviewDto(pocketBalance, fundDtos);
    }

    /**
     * Вычисляет баланс кармашка с учётом последнего {@code BalanceCheckpoint}.
     * <p>
     * Если чекпоинт зафиксирован — стартуем от реального остатка на счёте
     * и прибавляем только исполненные (EXECUTED) суммы событий начиная с даты чекпоинта.
     * Если чекпоинта нет — суммируем только исполненные (EXECUTED) события целиком.
     * В обоих случаях из результата вычитаются балансы целевых фондов.
     *
     * @param fundBalances суммарный баланс всех активных целевых фондов
     * @return баланс кармашка
     */
    private BigDecimal calcPocketBalance(BigDecimal fundBalances) {
        Optional<BalanceCheckpoint> latestCheckpoint = checkpointRepository.findTopByOrderByDateDesc();

        BigDecimal income;
        BigDecimal expense;

        if (latestCheckpoint.isPresent()) {
            LocalDate fromDate = latestCheckpoint.get().getDate();
            BigDecimal checkpointAmount = latestCheckpoint.get().getAmount();
            income = eventRepository.sumFactExecutedByTypeFromDate(EventType.INCOME, fromDate);
            expense = eventRepository.sumFactExecutedByTypeFromDate(EventType.EXPENSE, fromDate);
            return checkpointAmount.add(income).subtract(expense).subtract(fundBalances);
        } else {
            income = eventRepository.sumFactExecutedByType(EventType.INCOME);
            expense = eventRepository.sumFactExecutedByType(EventType.EXPENSE);
            return income.subtract(expense).subtract(fundBalances);
        }
    }

    /**
     * Создаёт новый целевой фонд.
     * Если {@code priority} не указан — назначается значение 100 (низкий приоритет).
     *
     * @param dto данные нового фонда (название, целевая сумма, приоритет)
     * @return созданный фонд
     */
    @Transactional
    public TargetFundDto create(TargetFundCreateDto dto) {
        TargetFund fund = TargetFund.builder()
                .name(dto.name())
                .targetAmount(dto.targetAmount())
                .priority(dto.priority() != null ? dto.priority() : 100)
                .targetDate(dto.targetDate())
                .purchaseType(dto.purchaseType() != null ? dto.purchaseType() : FundPurchaseType.SAVINGS)
                .creditRate(dto.creditRate())
                .creditTermMonths(dto.creditTermMonths())
                .build();
        return toDto(fundRepository.save(fund));
    }

    /**
     * Обновляет название, целевую сумму, срок достижения и приоритет фонда.
     *
     * @param id  идентификатор фонда
     * @param dto новые данные фонда
     * @return обновлённый фонд
     * @throws ResourceNotFoundException если фонд не найден или удалён
     */
    @Transactional
    public TargetFundDto update(UUID id, TargetFundCreateDto dto) {
        TargetFund fund = fundRepository.findById(id)
                .filter(f -> !f.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", id));
        fund.setName(dto.name());
        fund.setTargetAmount(dto.targetAmount());
        fund.setTargetDate(dto.targetDate());
        if (dto.priority() != null) fund.setPriority(dto.priority());
        if (dto.purchaseType() != null) fund.setPurchaseType(dto.purchaseType());
        fund.setCreditRate(dto.creditRate());
        fund.setCreditTermMonths(dto.creditTermMonths());
        return toDto(fundRepository.save(fund));
    }

    /**
     * Помечает фонд как удалённый (soft delete).
     * Транзакции фонда при этом сохраняются.
     *
     * @param id идентификатор фонда
     * @throws ResourceNotFoundException если фонд не найден
     */
    @Transactional
    public void delete(UUID id) {
        TargetFund fund = fundRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", id));
        fund.setDeleted(true);
        fundRepository.save(fund);
    }

    /**
     * Идемпотентное пополнение фонда.
     * Проверяет кэш транзакций по {@code idempotencyKey}: если перевод уже выполнен —
     * возвращает текущее состояние фонда без повторного зачисления.
     * Если ключ новый — делегирует в {@link #doTransfer}.
     *
     * @param fundId          идентификатор целевого фонда
     * @param idempotencyKey  UUID клиента для защиты от двойного зачисления
     * @param amount          положительная сумма пополнения
     * @return обновлённый фонд
     * @throws ResourceNotFoundException если фонд не найден или удалён
     */
    @Transactional
    public TargetFundDto transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount) {
        // Идемпотентность: повторный запрос с тем же ключом возвращает закэшированный результат
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(tx -> toDto(tx.getFund()))
                .orElseGet(() -> doTransfer(fundId, idempotencyKey, amount));
    }

    /**
     * Выполняет фактический перевод: увеличивает баланс фонда, при достижении цели
     * переводит статус в {@link FundStatus#REACHED}, сохраняет транзакцию в историю.
     * Изменение логируется в аудит-лог.
     *
     * @param fundId         идентификатор фонда
     * @param idempotencyKey UUID для записи транзакции
     * @param amount         сумма пополнения
     * @return обновлённый фонд
     * @throws ResourceNotFoundException если фонд не найден или удалён
     */
    private TargetFundDto doTransfer(UUID fundId, UUID idempotencyKey, BigDecimal amount) {
        TargetFund fund = fundRepository.findById(fundId)
                .filter(f -> !f.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", fundId));

        BigDecimal oldBalance = fund.getCurrentBalance();
        BigDecimal newBalance = oldBalance.add(amount);
        fund.setCurrentBalance(newBalance);
        if (fund.getTargetAmount() != null && newBalance.compareTo(fund.getTargetAmount()) >= 0) {
            fund.setStatus(FundStatus.REACHED);
        }
        fundRepository.save(fund);

        // Сохраняем транзакцию для истории и расчёта прогноза
        FundTransaction tx = FundTransaction.builder()
                .fund(fund)
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .transactionDate(LocalDate.now())
                .build();
        transactionRepository.save(tx);

        // Создаём EXECUTED событие типа FUND_TRANSFER для видимости в бюджете
        Category category = getOrCreateFundTransferCategory();
        FinancialEvent transferEvent = FinancialEvent.builder()
                .type(EventType.FUND_TRANSFER)
                .status(EventStatus.EXECUTED)
                .factAmount(amount)
                .date(LocalDate.now())
                .category(category)
                .targetFundId(fund.getId())
                .description("В копилку: " + fund.getName())
                .build();
        eventRepository.save(transferEvent);

        log.info("fund_transfer fund_id={} amount={} balance_before={} balance_after={} key={}",
                fundId, amount, oldBalance, newBalance, idempotencyKey);

        return toDto(fund);
    }

    /**
     * Выполняет перевод в фонд для уже существующего FUND_TRANSFER события.
     * Не создаёт новое FinancialEvent — оно уже есть.
     * Идемпотентен по idempotencyKey.
     *
     * @param fundId          идентификатор фонда
     * @param amount          сумма пополнения
     * @param idempotencyKey  ключ идемпотентности события
     */
    @Transactional
    public void doTransferForEvent(UUID fundId, BigDecimal amount, UUID idempotencyKey) {
        if (transactionRepository.existsByIdempotencyKey(idempotencyKey)) return;

        TargetFund fund = fundRepository.findById(fundId)
                .filter(f -> !f.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", fundId));

        BigDecimal newBalance = fund.getCurrentBalance().add(amount);
        fund.setCurrentBalance(newBalance);
        if (fund.getTargetAmount() != null && newBalance.compareTo(fund.getTargetAmount()) >= 0) {
            fund.setStatus(FundStatus.REACHED);
        }
        fundRepository.save(fund);

        FundTransaction tx = FundTransaction.builder()
                .fund(fund)
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .transactionDate(LocalDate.now())
                .build();
        transactionRepository.save(tx);

        log.info("fund_transfer_for_event fund_id={} amount={} balance_after={} key={}",
                fundId, amount, newBalance, idempotencyKey);
    }

    /**
     * Возвращает или создаёт системную категорию "Переводы в копилки".
     */
    Category getOrCreateFundTransferCategory() {
        return categoryRepository.findByNameAndDeletedFalse("Переводы в копилки")
                .orElseGet(() -> {
                    Category c = Category.builder()
                            .name("Переводы в копилки")
                            .type(CategoryType.EXPENSE)
                            .build();
                    return categoryRepository.save(c);
                });
    }

    /**
     * Конвертирует entity фонда в DTO, попутно вычисляя прогноз даты достижения цели.
     *
     * @param f entity фонда
     * @return DTO с рассчитанным {@code estimatedCompletionDate}
     * @see #calcEstimatedCompletion(TargetFund)
     */
    public TargetFundDto toDto(TargetFund f) {
        return new TargetFundDto(
                f.getId(), f.getName(), f.getTargetAmount(),
                f.getCurrentBalance(), f.getStatus(), f.getPriority(),
                f.getTargetDate(), calcEstimatedCompletion(f),
                f.getPurchaseType(), f.getCreditRate(), f.getCreditTermMonths());
    }

    /**
     * Прогноз даты достижения цели фонда на основе среднемесячного пополнения
     * за последние 3 месяца.
     *
     * <p>Возвращает {@code null} если:
     * <ul>
     *   <li>у фонда нет целевой суммы (кармашек или открытый фонд)</li>
     *   <li>статус фонда не {@link FundStatus#FUNDING}</li>
     *   <li>остаток до цели уже достигнут (≤ 0)</li>
     *   <li>за последние 3 месяца не было пополнений</li>
     *   <li>среднее пополнение равно нулю</li>
     * </ul>
     *
     * @param fund entity фонда
     * @return ориентировочная дата достижения цели или {@code null}
     */
    private LocalDate calcEstimatedCompletion(TargetFund fund) {
        if (fund.getTargetAmount() == null || fund.getStatus() != FundStatus.FUNDING) {
            return null;
        }
        BigDecimal remaining = fund.getTargetAmount().subtract(fund.getCurrentBalance());
        if (remaining.compareTo(BigDecimal.ZERO) <= 0)
            return null;

        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3);
        List<FundTransaction> recent = transactionRepository
                .findByFundIdAndDeletedFalseAndTransactionDateAfter(fund.getId(), threeMonthsAgo);

        if (recent.isEmpty())
            return null;

        BigDecimal totalIn = recent.stream()
                .map(FundTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Среднее в месяц (за 3 месяца)
        BigDecimal avgMonthly = totalIn.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        if (avgMonthly.compareTo(BigDecimal.ZERO) <= 0)
            return null;

        long monthsLeft = remaining.divide(avgMonthly, 0, RoundingMode.CEILING).longValue();
        return LocalDate.now().plusMonths(monthsLeft);
    }
}
