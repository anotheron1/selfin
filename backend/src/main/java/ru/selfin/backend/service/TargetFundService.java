package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.FundTransaction;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundMoneyDisposal;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.WishlistStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Сервис управления целевыми фондами накоплений (копилками): CRUD, переводы, прогноз достижения цели.
 *
 * <p>Расчёт кармашка (свободных денег) переехал в {@link PocketEngine}/{@link PocketService}
 * — единый источник правды для Funds, Dashboard и кассового календаря
 * (спека {@code docs/superpowers/specs/2026-07-02-pocket-core-design.md}).
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
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final AccountBalanceService accountBalanceService;
    private final WishlistArtifactService wishlistArtifactService;

    /** Системное имя фонда-кармашка. */
    private static final String POCKET_NAME = "POCKET";

    /**
     * Возвращает обзор фондов: список активных целевых фондов с прогнозом достижения цели.
     * Кармашек в обзор больше не входит — фронт берёт его из {@code GET /api/v1/pocket}.
     *
     * @return DTO обзора фондов
     */
    public FundsOverviewDto getOverview() {
        List<TargetFundDto> fundDtos = fundRepository.findAllByDeletedFalseOrderByPriorityAsc().stream()
                .filter(f -> !POCKET_NAME.equals(f.getName()))
                .map(this::toDto)
                .toList();
        return new FundsOverviewDto(fundDtos);
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
                .accountId(validateAccountLink(dto.accountId(), null))
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
        // Отвязка от счёта фиксирует накопленное на копилке. Пока копилка жила на счёте, её
        // собственное поле не двигалось (переводы запрещены), и без переноса цель после
        // отвязки прыгнула бы к протухшему числу — обычно к нулю (найдено ревью чанка 3).
        if (fund.getAccountId() != null && dto.accountId() == null) {
            fund.setCurrentBalance(accountBalanceService.fundBalanceAt(fund, LocalDate.now()));
        }
        fund.setAccountId(validateAccountLink(dto.accountId(), fund.getId()));
        return toDto(fundRepository.save(fund));
    }

    /**
     * Проверяет ссылку копилки на счёт (§3.3): счёт обязан существовать и быть живым, и на
     * одном счёте живёт не более одной цели. Уникальный индекс {@code uq_funds_one_per_account}
     * стережёт то же самое в базе, но пользователю нужен 409 с объяснением, а не 500.
     *
     * <p>Две цели на одной карте потребовали бы делить остаток между ними, то есть виртуальных
     * подконвертов внутри счёта — сознательно не делаем; кому нужны две, оставляет их
     * виртуальными конвертами.
     */
    private UUID validateAccountLink(UUID accountId, UUID selfId) {
        if (accountId == null) return null;
        Account account = accountRepository.findById(accountId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        // Цель на кредитке бессмысленна: там чекпоинт хранит ДОСТУПНЫЙ остаток, и «накоплено»
        // показало бы неизрасходованный лимит — деньги, которых нет. Конверт без слежения
        // ничем не лучше: его остаток не входит ни в свободные деньги, ни в капитал, и цель
        // копила бы число, не подтверждённое ничем (найдено ревью чанка 3).
        if (account.getKind() == AccountKind.CREDIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A goal cannot live on a credit account: its balance is available credit, not savings");
        }
        if (!account.isTrackBalance()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A goal needs a tracked balance: turn balance tracking on for this account first");
        }
        fundRepository.findAllByDeletedFalseOrderByPriorityAsc().stream()
                .filter(f -> accountId.equals(f.getAccountId()))
                .filter(f -> !f.getId().equals(selfId))
                .findAny()
                .ifPresent(f -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Account already holds the goal \"" + f.getName() + "\"");
                });
        return accountId;
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
        delete(id, null);
    }

    /**
     * Удаляет копилку, явно решая судьбу лежащих на ней денег (ANO-86, спека §4.3).
     *
     * <p>Раньше это была одна строка {@code setDeleted(true)}, и она убивала половину
     * взаимной компенсации: событие {@code FUND_TRANSFER} уже вычло деньги из остатка счёта,
     * движения копилки оставались живыми, и сумма оказывалась одновременно недоступной и
     * посчитанной в капитале. Блок 6.8 плана тестирования требовал «внятный вопрос, что
     * сделать с деньгами» — вот он.
     *
     * <p>Копилка — условное место, где деньги лежат. Поэтому оба исхода законны: деньги могли
     * быть потрачены на саму цель, а могло быть и вынужденное удаление, когда их надо вернуть.
     * Выбрать за человека продукт не может.
     *
     * @param disposal что сделать с деньгами; обязателен только при ненулевом балансе
     * @throws ResponseStatusException 409, если на копилке есть деньги, а выбор не сделан
     */
    @Transactional
    public void delete(UUID id, FundMoneyDisposal disposal) {
        TargetFund fund = fundRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", id));

        // У копилки СО СЧЁТОМ собственных денег нет: её баланс — это остаток счёта, и он
        // остаётся на месте. Удаляется только цель поверх чужих денег, спрашивать не о чем
        // (спека §4.6).
        BigDecimal balance = fund.getAccountId() != null
                ? BigDecimal.ZERO
                : (fund.getCurrentBalance() != null ? fund.getCurrentBalance() : BigDecimal.ZERO);

        if (balance.signum() != 0) {
            if (disposal == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Fund holds " + balance + "; pass money=RETURN or money=SPENT");
            }
            if (disposal == FundMoneyDisposal.RETURN) {
                // Обратный перевод на всю сумму: деньги возвращаются в свободные, кармашек и
                // остаток растут. confirm=true — подтверждать тут нечего, это возврат своих же.
                doTransfer(id, UUID.randomUUID(), balance.negate(), true);
            }
        }
        fund.setDeleted(true);
        fundRepository.save(fund);
    }

    /**
     * Устанавливает статус копилки/кредита в модуле /wishlist (OPEN/FIXED/DISMISSED).
     * Идемпотентно: запись того же значения не меняет состояние.
     *
     * @param id     идентификатор фонда
     * @param status новый wishlist-статус
     * @throws ResourceNotFoundException если фонд не найден или удалён
     */
    @Transactional
    public void setWishlistStatus(UUID id, WishlistStatus status) {
        setWishlistStatus(id, status, false);
    }

    /**
     * То же плюс явный выбор судьбы артефакта (ANO-103, спека §66). Зеркало
     * {@code FinancialEventService.setWishlistStatus}: хотелкой может быть и событие, и копилка,
     * и обе ветки обязаны вести себя одинаково. Само правило удаления живёт в одном месте —
     * {@link WishlistArtifactService}, здесь только ссылка и очистка.
     *
     * @param deleteArtifact удалить ли созданный конверсией артефакт
     * @throws ResponseStatusException 409, если за артефактом стоят деньги
     */
    @Transactional
    public void setWishlistStatus(UUID id, WishlistStatus status, boolean deleteArtifact) {
        TargetFund f = fundRepository.findById(id)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", id));
        if (deleteArtifact) {
            wishlistArtifactService.deleteArtifact(f.getConvertedToEventId(), f.getConvertedToFundId());
            f.setConvertedToEventId(null);
            f.setConvertedToFundId(null);
        }
        f.setWishlistStatus(status);
        fundRepository.save(f);
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
        return transferToPocket(fundId, idempotencyKey, amount, false);
    }

    /**
     * То же с явным подтверждением перевода сверх остатка счёта (ANO-87, спека §4.2).
     *
     * @param confirm человек увидел предупреждение и настаивает
     */
    @Transactional
    public TargetFundDto transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount,
                                          boolean confirm) {
        // Идемпотентность: повторный запрос с тем же ключом возвращает закэшированный результат
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(tx -> toDto(tx.getFund()))
                .orElseGet(() -> doTransfer(fundId, idempotencyKey, amount, confirm));
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
    private TargetFundDto doTransfer(UUID fundId, UUID idempotencyKey, BigDecimal amount,
                                     boolean confirm) {
        TargetFund fund = fundRepository.findById(fundId)
                .filter(f -> !f.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", fundId));
        rejectTransferToAccountBackedFund(fund);

        if (amount.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer amount must not be zero");
        }
        // ANO-87 (спека §4.1). Снять можно только то, что накоплено. Это не запрет, а
        // арифметика: в копилке столько физически нет. В отличие от перевода СВЕРХ остатка
        // счёта, здесь подтверждать нечего — отказ безусловный.
        if (amount.signum() < 0 && amount.negate().compareTo(fund.getCurrentBalance()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fund holds only " + fund.getCurrentBalance());
        }
        // ANO-87 (спека §4.2). Переложить больше, чем показывает остаток, можно — но только
        // осознанно. Остаток в продукте не банковская истина, а якорь плюс введённое: он
        // отстаёт от реальности, и жёсткий отказ наказывал бы за неточный ввод, что запрещает
        // правило 5. Человек, у которого деньги реально есть, обязан суметь их отложить.
        //
        // NB ANO-39: LocalDate.now() здесь — прямой вызов, 36-е место. Clock в этот сервис не
        // инжектится, а половинчатая миграция одного сервиса хуже честных 36 мест. При
        // инъекции Clock не пропустить: это денежный путь, тот же, где живёт ANO-125.
        if (amount.signum() > 0 && !confirm) {
            LocalDate today = LocalDate.now();
            // Ровно то число, которое продукт САМ называет свободными деньгами: так же
            // считает CapitalService.cashLiquidAt. Один freeMoneyAt занижает у пользователя
            // без чекпоинта — для него существует запасной путь noAnchorFallbackAt (ANO-28),
            // и предупреждать по числу, которое продукт свободными деньгами не считает, нельзя.
            BigDecimal free = accountBalanceService.freeMoneyAt(today)
                    .add(accountBalanceService.noAnchorFallbackAt(today));
            if (amount.compareTo(free) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Account holds " + free + ", transferring " + amount
                                + "; resend with confirm=true to proceed");
            }
        }

        BigDecimal oldBalance = fund.getCurrentBalance();
        BigDecimal newBalance = oldBalance.add(amount);
        fund.setCurrentBalance(newBalance);
        // Статус пересчитывается в ОБЕ стороны: после снятия копилка может перестать быть
        // достигнутой, и оставлять её REACHED значило бы врать на экране (ANO-87).
        fund.setStatus(fund.getTargetAmount() != null
                && newBalance.compareTo(fund.getTargetAmount()) >= 0
                ? FundStatus.REACHED : FundStatus.FUNDING);
        fundRepository.save(fund);

        // Сохраняем транзакцию для истории и расчёта прогноза
        FundTransaction tx = FundTransaction.builder()
                .fund(fund)
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .transactionDate(LocalDate.now())
                .build();
        transactionRepository.save(tx);

        // Создаём EXECUTED FACT-событие типа FUND_TRANSFER для видимости в бюджете
        Category category = getOrCreateFundTransferCategory();
        FinancialEvent transferEvent = FinancialEvent.builder()
                .eventKind(EventKind.FACT)
                .type(EventType.FUND_TRANSFER)
                .status(EventStatus.EXECUTED)
                .factAmount(amount)
                .date(LocalDate.now())
                .category(category)
                .targetFundId(fund.getId())
                .description("В копилку: " + fund.getName())
                .idempotencyKey(idempotencyKey)
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
        rejectTransferToAccountBackedFund(fund);

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
        BigDecimal balance = accountBalanceService.fundBalanceAt(f, LocalDate.now());
        return new TargetFundDto(
                f.getId(), f.getName(), f.getTargetAmount(),
                balance, f.getAccountId(), f.getStatus(), f.getPriority(),
                f.getTargetDate(), calcEstimatedCompletion(f, balance),
                f.getPurchaseType(), f.getCreditRate(), f.getCreditTermMonths());
    }

    /**
     * Перевод в копилку, лежащую на счёте, запрещён (§3.3): деньги двигаются на самом счёте,
     * а перевод создал бы вторую запись за те же рубли — ровно то задвоение, ради защиты от
     * которого копилка со счётом и теряет собственный баланс.
     */
    private static void rejectTransferToAccountBackedFund(TargetFund fund) {
        if (fund.getAccountId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This goal lives on an account: move the money on the account itself "
                            + "and re-anchor its balance, do not transfer into the goal");
        }
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
    private LocalDate calcEstimatedCompletion(TargetFund fund, BigDecimal balance) {
        if (fund.getTargetAmount() == null || fund.getStatus() != FundStatus.FUNDING) {
            return null;
        }
        BigDecimal remaining = fund.getTargetAmount().subtract(balance);
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
