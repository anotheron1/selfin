package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.FundTransaction;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Сервис управления целевыми фондами накоплений (копилками) и «кармашком».
 *
 * <p><b>Кармашек</b> — системный фонд с именем {@value #POCKET_NAME}, не имеющий
 * целевой суммы. Служит буфером свободных средств: разница между фактическими
 * доходами и расходами, а также переводы из других фондов.
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

    /** Системное имя фонда-кармашка. */
    private static final String POCKET_NAME = "POCKET";

    /**
     * Возвращает обзор всех фондов: баланс кармашка и список активных целевых фондов
     * с рассчитанным прогнозом достижения цели.
     * Кармашек фильтруется из списка фондов и выводится отдельной строкой.
     *
     * @return DTO обзора фондов
     */
    public FundsOverviewDto getOverview() {
        List<TargetFund> funds = fundRepository.findAllByDeletedFalseOrderByPriorityAsc();
        BigDecimal pocketBalance = funds.stream()
                .filter(f -> POCKET_NAME.equals(f.getName()))
                .map(TargetFund::getCurrentBalance)
                .findFirst().orElse(BigDecimal.ZERO);
        List<TargetFundDto> fundDtos = funds.stream()
                .filter(f -> !POCKET_NAME.equals(f.getName()))
                .map(this::toDto)
                .toList();
        return new FundsOverviewDto(pocketBalance, fundDtos);
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
                .build();
        return toDto(fundRepository.save(fund));
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

        log.info("fund_transfer fund_id={} amount={} balance_before={} balance_after={} key={}",
                fundId, amount, oldBalance, newBalance, idempotencyKey);

        return toDto(fund);
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
                calcEstimatedCompletion(f));
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
