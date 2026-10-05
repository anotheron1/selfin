package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.TargetFundCreateDto;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.dto.wishlist.FundWishlistParamsDto;
import ru.selfin.backend.exception.ConfirmationRequiredException;
import ru.selfin.backend.exception.FundMovementRefusedException;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.FundAccountLink;
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
import ru.selfin.backend.repository.FundAccountLinkRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
    private final FundAccountLinkRepository linkRepository;
    private final FinancialEventRepository eventRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final AccountBalanceService accountBalanceService;
    private final WishlistArtifactService wishlistArtifactService;
    /** ANO-88: вопрос «отложить всё равно?» задаётся по кармашку после перевода. */
    private final PocketService pocketService;
    /** ANO-39: «сегодня» приходит извне — иначе календарную логику не проверить детерминированно. */
    private final Clock clock;

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
        TargetFund saved = fundRepository.save(fund);
        recordAccountLink(saved.getId(), null, saved.getAccountId());
        return toDto(saved);
    }

    /**
     * Обновляет название, целевую сумму, срок достижения и приоритет фонда. Статус конверта
     * пересчитывается по новой цели (ANO-199).
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
        // ANO-158: отвязка снимает ярлык, а не переносит чужие деньги. Копилке возвращается
        // ровно её собственная история переводов.
        //
        // Раньше сюда клался ОСТАТОК СЧЁТА: копилка начинала заявлять деньги, которых в неё
        // никто не переводил, — они лежали на счёте и оставались свободными. Замер на стенде
        // дал поле 55 000 при движениях 20 000. Капитал не врал (он складывает движения),
        // врали экран и кнопки: диалог удаления спрашивал про 55 000, а возвращал 20 000.
        //
        // Писать при отвязке компенсирующее движение нельзя — деньги на счёте уже посчитаны,
        // и капитал начал бы считать их дважды. Поэтому именно поле, и именно по движениям.
        //
        // Следствие названо владельцу и принято: копилка, жившая на счёте с рождения, после
        // отвязки пуста. Строка §8 спеки ANO-9 («накопленное переносится в собственное поле»)
        // отменена решением от 16.09.2026 — см. поправку в самой спеке.
        if (fund.getAccountId() != null && dto.accountId() == null) {
            fund.setCurrentBalance(transactionRepository.sumLiveByFundId(id));
        }
        UUID accountId = validateAccountLink(dto.accountId(), fund.getId());
        recordAccountLink(fund.getId(), fund.getAccountId(), accountId);
        fund.setAccountId(accountId);
        // ANO-199: статус — производная от накопленного И цели, а правка меняет цель. Раньше он
        // пересчитывался только при отвязке, и поднятая цель оставляла «Цель достигнута» при 50%
        // без кнопки пополнения. У копилки на счёте поле баланса — не её деньги (§3.3), статус по
        // нему не выводится; её случай — C11 (ANO-164, ANO-174).
        if (fund.getAccountId() == null) {
            applyStatusByBalance(fund);
        }
        return toDto(fundRepository.save(fund));
    }

    /**
     * Пишет историю привязок (ANO-163): капитал за прошлую дату смотрит, жила ли копилка на
     * счёте В ТОТ ДЕНЬ, а не сегодня ({@code FundTransactionRepository
     * .sumEnvelopeFundsByTransactionDateLessThanEqual}).
     *
     * <p>Раньше привязка была только признаком {@code accountId}, и привязка сегодня уменьшала
     * капитал за каждый прошлый месяц на взносы копилки. Замер на стенде: минус 20 000 в
     * августе от привязки 16 сентября; отвязка возвращала.
     *
     * <p>Зовётся из обоих мест, где меняется счёт копилки, — {@link #create} и {@link #update}.
     * Правка без смены счёта историю не трогает: фронт шлёт {@code accountId} в каждой правке.
     *
     * <p>Спека: {@code docs/superpowers/specs/2026-09-24-fund-link-history-design.md}.
     */
    private void recordAccountLink(UUID fundId, UUID oldAccountId, UUID newAccountId) {
        if (Objects.equals(oldAccountId, newAccountId)) return;
        LocalDate today = LocalDate.now(clock);
        linkRepository.findByFundIdAndLinkedToIsNull(fundId).ifPresent(open -> {
            open.setLinkedTo(today);
            // Именно saveAndFlush: Hibernate сбрасывает вставки раньше обновлений, и при
            // перепривязке со счёта на счёт новая открытая строка встретила бы ещё не
            // закрытую в uq_fund_account_links_open.
            linkRepository.saveAndFlush(open);
        });
        if (newAccountId != null) {
            linkRepository.save(FundAccountLink.builder()
                    .fundId(fundId).accountId(newAccountId).linkedFrom(today).build());
        }
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
    /**
     * «Цель достигнута» — производная от накопленного, а не отметка.
     *
     * <p>Пересчитывается в ОБЕ стороны: после снятия из копилки или после отвязки от счёта
     * она может перестать быть достигнутой, и оставлять её {@code REACHED} значило бы врать
     * на экране (ANO-87). Правило живёт в одном месте — двух копий «слово в слово» этому
     * репозиторию уже хватило (ANO-155).
     *
     * <p>Цель задана, только если она больше нуля (ANO-199). Ноль — копилка без цели: так её
     * заводили в обход обязательного поля, и первый же взнос в 1 ₽ «достигал» цели 0 — копилка
     * закрывалась и больше не принимала денег. Правило здесь, а не у формы: цель приходит ещё
     * из ручки, из конверсии хотелки (сумма хотелки бывает 0) и из старых записей.
     */
    private void applyStatusByBalance(TargetFund fund) {
        BigDecimal target = fund.getTargetAmount();
        fund.setStatus(target != null && target.signum() > 0
                && fund.getCurrentBalance().compareTo(target) >= 0
                ? FundStatus.REACHED : FundStatus.FUNDING);
    }

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
            // ANO-156, найдено ревью PR #42. Выбытие компенсирует ЗАПИСАННЫЕ ДВИЖЕНИЯ, а не
            // поле current_balance. Поле может с ними разойтись: update() при отвязке копилки
            // от счёта переносит в него остаток СЧЁТА, не создавая движения.
            //
            // Раньше разницу прятал фильтр t.fund.deleted в запросе суммы копилок. С ANO-156
            // фильтра нет, и остаток виден в cashLiquidAt навсегда, за каждую дату. Замерено:
            // компенсация по полю оставляла −460 000 при записанных 20 000.
            //
            // Для «вернуть» это ещё и вопрос честности: со счёта ушло ровно записанное, и
            // вернуть надо его. Возврат по раздутому полю создал бы деньги, которых не было.
            BigDecimal recorded = transactionRepository.sumLiveByFundId(id);
            fund.setCurrentBalance(recorded);

            if (disposal == FundMoneyDisposal.RETURN) {
                // Обратный перевод на всю записанную сумму: деньги возвращаются в свободные,
                // кармашек и остаток растут. confirm=true — подтверждать нечего, это возврат
                // своих же.
                if (recorded.signum() != 0) {
                    doTransfer(id, UUID.randomUUID(), recorded.negate(), true, null, null);
                }
            } else {
                // Деньги потрачены на цель. Журнал обязан назвать это тратой, а не
                // перемещением в место, которого больше нет: строка «В копилку: Отпуск»
                // ссылалась бы на несуществующую копилку (правило 13, спека §4.5).
                // Меняется ТОЛЬКО описание — сумма, дата и тип остаются: это по-прежнему та
                // же операция, просто названная правдиво. При RETURN не переименовываем:
                // перевод состоялся и деньги вернулись, переписывать прошлое незачем.
                eventRepository.findAllByTargetFundIdAndDeletedFalse(id)
                        .forEach(e -> e.setDescription(fund.getName()));

                // ANO-156: деньги ушли из копилки СЕГОДНЯ, и это обязано быть движением, а не
                // флагом. Раньше их «списывал» фильтр t.fund.deleted в запросе суммы копилок —
                // но флаг «удалена сейчас» применялся и ко всем прошлым датам, а
                // BaselineTimelineBuilder.buildPastPoints зовёт cashLiquidAt для каждого
                // прошлого месяца. Удаление копилки переписывало историю ликвида задним
                // числом, от даты первого взноса.
                //
                // Событие FUND_TRANSFER здесь НЕ создаётся, в отличие от ветки RETURN: счёт
                // потерял эти деньги ещё при первом переводе, и возвращать их некуда — они
                // потрачены на цель. Создать событие значило бы вернуть несуществующее.
                if (recorded.signum() != 0) {
                    transactionRepository.save(FundTransaction.builder()
                            .fund(fund)
                            .idempotencyKey(UUID.randomUUID())
                            .amount(recorded.negate())
                            .transactionDate(LocalDate.now(clock))
                            .build());
                }
                fund.setCurrentBalance(BigDecimal.ZERO);
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
     * Переносит параметры примерки в копилку или кредит — цель и, если присланы, срок, ставку и
     * срок кредита (ANO-162). Только их: счёт, имя, вид и приоритет не трогает. Путь «Что с
     * капиталом»; раньше он шёл полной перезаписью {@link #update}, а отсутствующий счёт там —
     * «отвязать»: копилка на счёте после фиксации теряла счёт и накопленное.
     *
     * <p>Статус конверта пересчитывается по новой цели, как в {@link #update} (ANO-199), — у
     * копилки не на счёте: у копилки на счёте поле баланса — не её деньги (§3.3).
     *
     * @throws ResourceNotFoundException если копилки нет или она не хотелка
     */
    @Transactional
    public void applyWishlistParams(UUID id, FundWishlistParamsDto dto) {
        TargetFund fund = fundRepository.findById(id)
                .filter(f -> !f.isDeleted() && f.getWishlistStatus() != null)
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund (wishlist)", id));
        fund.setTargetAmount(dto.targetAmount());
        if (dto.targetDate() != null) fund.setTargetDate(dto.targetDate());
        if (dto.creditRate() != null) fund.setCreditRate(dto.creditRate());
        if (dto.creditTermMonths() != null) fund.setCreditTermMonths(dto.creditTermMonths());
        if (fund.getAccountId() == null) {
            applyStatusByBalance(fund);
        }
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
        return transferToPocket(fundId, idempotencyKey, amount, confirm, null);
    }

    /**
     * То же по горизонту, выбранному на карточке кармашка (ANO-88).
     *
     * @param scope горизонт кармашка, как в {@code GET /pocket?scope=}; {@code null} — «до дохода»
     */
    @Transactional
    public TargetFundDto transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount,
                                          boolean confirm, String scope) {
        return transferToPocket(fundId, idempotencyKey, amount, confirm, scope, null);
    }

    /**
     * То же днём, который назвал человек (ANO-169): быстрый ввод записывает перевод, который
     * уже сделан, — возможно, вчера.
     *
     * @param date день перевода; {@code null} — сегодня; будущий — 400
     */
    @Transactional
    public TargetFundDto transferToPocket(UUID fundId, UUID idempotencyKey, BigDecimal amount,
                                          boolean confirm, String scope, LocalDate date) {
        // Идемпотентность: повторный запрос с тем же ключом возвращает закэшированный результат
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(tx -> toDto(tx.getFund()))
                .orElseGet(() -> doTransfer(fundId, idempotencyKey, amount, confirm, scope, date));
    }

    /**
     * Выполняет фактический перевод: увеличивает баланс фонда, при достижении цели
     * переводит статус в {@link FundStatus#REACHED}, сохраняет транзакцию в историю.
     * Изменение логируется в аудит-лог.
     *
     * @param fundId         идентификатор фонда
     * @param idempotencyKey UUID для записи транзакции
     * @param amount         сумма пополнения
     * @param date           день перевода; {@code null} — сегодня (ANO-169)
     * @return обновлённый фонд
     * @throws ResourceNotFoundException если фонд не найден или удалён
     */
    private TargetFundDto doTransfer(UUID fundId, UUID idempotencyKey, BigDecimal amount,
                                     boolean confirm, String scope, LocalDate date) {
        TargetFund fund = fundRepository.findById(fundId)
                .filter(f -> !f.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", fundId));
        rejectTransferToAccountBackedFund(fund);

        if (amount.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer amount must not be zero");
        }
        LocalDate today = LocalDate.now(clock);
        // ANO-169: факт перевода и движение копилки — одним днём, тем, что назвал человек.
        // Будущего перевода не бывает: деньги ещё не ушли, и в копилке их нет (ANO-155).
        LocalDate day = date != null ? date : today;
        if (day.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer date " + day + " is in the future");
        }
        // Ревью #96: дата здесь — ради «уже перевёл» из быстрого ввода, то есть вклада. Снятие
        // прошлым днём проверялось бы ниже по СЕГОДНЯШНЕМУ остатку: вчера в копилке могло не быть
        // денег, положенных сегодня, и её история ушла бы в минус. Экран снятие с датой не шлёт —
        // поэтому снятие только сегодняшним днём, а не проверка по остатку того дня.
        if (amount.signum() < 0 && day.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A withdrawal from a fund is recorded today, not on " + day);
        }
        // ANO-87 (спека §4.1). Снять можно только то, что накоплено. Это не запрет, а
        // арифметика: в копилке столько физически нет. В отличие от перевода СВЕРХ остатка
        // счёта, здесь подтверждать нечего — отказ безусловный.
        if (amount.signum() < 0 && amount.negate().compareTo(fund.getCurrentBalance()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fund holds only " + fund.getCurrentBalance());
        }
        // ANO-87 (спека §4.2). Переложить больше, чем свободно, можно — но только
        // осознанно. Остаток в продукте не банковская истина, а якорь плюс введённое: он
        // отстаёт от реальности, и жёсткий отказ наказывал бы за неточный ввод, что запрещает
        // правило 5. Человек, у которого деньги реально есть, обязан суметь их отложить.
        //
        // ANO-157: и только если у продукта ЕСТЬ основания судить. Без якоря и без фактов
        // свободные деньги равны нулю не потому, что их нет, а потому что мы не знаем. Ноль
        // из незнания продукт читал как ноль-знание и запрещал действие — то же правило 5,
        // нарушенное с другой стороны: человек, ничего не вводивший, упирался в стену на
        // первом же действии и не узнавал почему.
        //
        // Основания проверяются ДО записи перевода: сам факт перевода — тоже факт, и после
        // записи у человека без якоря и фактов «основания» появились бы из его же действия.
        boolean askIfShort = amount.signum() > 0 && !confirm
                && accountBalanceService.knowsFreeMoneyAt(today);

        BigDecimal oldBalance = fund.getCurrentBalance();
        BigDecimal newBalance = oldBalance.add(amount);
        fund.setCurrentBalance(newBalance);
        applyStatusByBalance(fund);
        fundRepository.save(fund);

        // Сохраняем транзакцию для истории и расчёта прогноза
        FundTransaction tx = FundTransaction.builder()
                .fund(fund)
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .transactionDate(day)
                .build();
        transactionRepository.save(tx);

        // Создаём EXECUTED FACT-событие типа FUND_TRANSFER для видимости в бюджете
        Category category = getOrCreateFundTransferCategory();
        FinancialEvent transferEvent = FinancialEvent.builder()
                .eventKind(EventKind.FACT)
                .type(EventType.FUND_TRANSFER)
                .status(EventStatus.EXECUTED)
                .factAmount(amount)
                .date(day)
                .category(category)
                .targetFundId(fund.getId())
                .description("В копилку: " + fund.getName())
                .idempotencyKey(idempotencyKey)
                .build();
        eventRepository.save(transferEvent);

        // ANO-88, решение владельца 26.09 (вариант А): переспрашиваем по тому же числу, что
        // карточка называет «свободно», — по кармашку, а не по остатку на счёте (тот не видит
        // ни броней, ни планов: при «свободно −1 600» молча уходило 60 000). И по кармашку
        // ПОСЛЕ перевода: кармашек держит плановые взносы в зафиксированные копилки, и перевод
        // в такую копилку сам уменьшает её резерв — сравнение суммы с кармашком «до»
        // переспрашивало бы на плановом взносе. Перевод уже записан в этой транзакции, движок
        // видит его; при минусе исключение откатит всё — копилку, историю и факт.
        if (askIfShort) {
            ru.selfin.backend.dto.pocket.PocketResultDto after = pocketService.getPocket(scope, today);
            if (after.pocket().signum() < 0) {
                // Отдельный тип, а не ResponseStatusException: статус тот же 409, что у
                // безусловного отказа выше, и различить их фронт может только по коду в
                // details (ANO-157).
                throw new ConfirmationRequiredException(
                        "Pocket after transfer would be " + after.pocket()
                                + "; resend with confirm=true to proceed",
                        List.of(afterTransferHint(after)));
            }
        }

        log.info("fund_transfer fund_id={} amount={} balance_before={} balance_after={} key={}",
                fundId, amount, oldBalance, newBalance, idempotencyKey);

        return toDto(fund);
    }

    /**
     * Что будет после перевода — чтобы вопрос повторил слова карточки кармашка (ANO-88).
     *
     * <p>{@code short:ДЕНЬ:СУММА} — по плану не хватит денег; {@code nz:ДЕНЬ:СУММА} — денег хватит,
     * но придётся взять из НЗ. День — минимум кармашка после перевода. Считает тот же движок,
     * что уже посчитал кармашек после перевода: фронт своей оценки не строит — с резервом
     * взносов она разошлась бы с правдой. Контракт с фронтом — {@code lib/transferConfirm.ts}.
     */
    static String afterTransferHint(ru.selfin.backend.dto.pocket.PocketResultDto after) {
        var min = after.minPoint();
        return min.balance().signum() < 0
                ? "short:" + min.date() + ":" + min.balance().negate().toPlainString()
                : "nz:" + min.date() + ":" + after.buffer().subtract(min.balance()).toPlainString();
    }

    /**
     * Приводит движение копилки к факту перевода (ANO-169, ANO-201).
     *
     * <p>Факт {@code FUND_TRANSFER} уменьшает остаток счёта, движение {@link FundTransaction}
     * с тем же ключом увеличивает копилку, и вместе они гасят друг друга — капитал от перевода
     * не меняется (спека капитала {@code 2026-05-10-capital-net-worth-design.md:63-66}). Кнопка
     * «Пополнить» пишет обе записи сама ({@link #doTransfer}). Этот метод — для трёх путей,
     * которые меняют уже записанный или записываемый факт: факт к плану, правка суммы,
     * удаление. До ANO-201 они писали только первую запись: деньги пропадали или появлялись
     * из воздуха (замер — спека {@code 2026-09-27-fund-transfer-fact-design.md}).
     *
     * <p>Когда копилка сдвинуться не может, отказ бросается ДО любой записи, и транзакция
     * вызывающего откатывает факт вместе с ней — факт и копилка меняются только вместе:
     * <ul>
     *   <li>копилка удалена — её переводы история: «вернуть» закрыло её обратным переводом,
     *       «потрачено» — списанием, и правка после этого оставила бы деньги-призраки;</li>
     *   <li>копилка на счёте — её деньги двигаются на самом счёте (§3.3, ANO-158);</li>
     *   <li>баланс ушёл бы в минус — та же арифметика, что снятие сверх накопленного (ANO-87).</li>
     * </ul>
     *
     * @param fundId  копилка перевода
     * @param key     ключ события — он же ключ движения
     * @param date    день факта: им датируется новое движение, чтобы две записи сходились
     *                и в капитале за прошлые даты
     * @param desired сумма, которую должно нести движение: сумма факта; ноль — факта больше нет
     * @throws FundMovementRefusedException если копилка не может сдвинуться вместе с фактом
     */
    @Transactional
    public void syncMovement(UUID fundId, UUID key, LocalDate date, BigDecimal desired) {
        FundTransaction tx = transactionRepository.findByIdempotencyKey(key).orElse(null);
        BigDecimal current = tx != null && !tx.isDeleted() ? tx.getAmount() : BigDecimal.ZERO;
        BigDecimal delta = desired.subtract(current);
        if (delta.signum() == 0) return;

        // Деньги уже лежат в копилке движения — она и двигается; копилка события — для нового.
        TargetFund fund = tx != null ? tx.getFund() : fundRepository.findById(fundId)
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", fundId));
        if (fund.isDeleted()) throw FundMovementRefusedException.closed(fund.getName());
        if (fund.getAccountId() != null) throw FundMovementRefusedException.onAccount(fund.getName());
        BigDecimal newBalance = fund.getCurrentBalance().add(delta);
        if (newBalance.signum() < 0) {
            throw FundMovementRefusedException.holdsLess(fund.getName(), fund.getCurrentBalance());
        }

        if (tx == null) {
            tx = FundTransaction.builder()
                    .fund(fund)
                    .idempotencyKey(key)
                    .amount(desired)
                    .transactionDate(date)
                    .build();
        } else if (desired.signum() == 0) {
            tx.setDeleted(true);
        } else {
            tx.setAmount(desired);
            tx.setDeleted(false);
        }
        transactionRepository.save(tx);

        fund.setCurrentBalance(newBalance);
        applyStatusByBalance(fund);
        fundRepository.save(fund);

        log.info("fund_movement_sync fund_id={} key={} movement={} delta={} balance_after={}",
                fund.getId(), key, desired, delta, newBalance);
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
     * Конвертирует entity фонда в DTO, попутно вычисляя дату «в нынешнем темпе».
     *
     * @param f entity фонда
     * @return DTO с рассчитанным {@code estimatedCompletionDate}
     * @see #calcEstimatedCompletion(TargetFund, BigDecimal)
     */
    public TargetFundDto toDto(TargetFund f) {
        BigDecimal balance = accountBalanceService.fundBalanceAt(f, LocalDate.now(clock));
        return new TargetFundDto(
                f.getId(), f.getName(), f.getTargetAmount(),
                balance, f.getAccountId(), f.getStatus(), f.getPriority(),
                f.getTargetDate(), calcEstimatedCompletion(f, balance),
                f.getPurchaseType(), f.getCreditRate(), f.getCreditTermMonths(),
                f.getWishlistStatus());
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
     * Когда цель наберётся в нынешнем темпе — «В нынешнем темпе — к &lt;месяцу&gt;» на «Целях»
     * (Р9-Б, ANO-219). Не второй срок: срок — {@code targetDate}, к нему ядро держит взносы.
     *
     * <p><b>Первый день пополнений — стартовые деньги, а не темп.</b> В новую копилку часто сразу
     * кладут уже отложенное: 100 000 разом и дальше по 10 000 дали бы темп 100 000 в месяц и срок
     * в разы ближе настоящего. Темп — пополнения после первого дня за три последних месяца,
     * делённые на месяцы от первого дня: не больше трёх (окно) и не меньше одного (второе
     * пополнение в первый же месяц); месяцы без пополнений входят — иначе одно пополнение за
     * три месяца выглядело бы ежемесячным (ревью Codex на #128).
     *
     * <p>{@code null} — строки нет: нет цели; копилка не копится ({@link FundStatus#FUNDING});
     * цель набрана; после первого дня пополнений нет или в сумме они не положительны.
     */
    private LocalDate calcEstimatedCompletion(TargetFund fund, BigDecimal balance) {
        if (fund.getTargetAmount() == null || fund.getStatus() != FundStatus.FUNDING) {
            return null;
        }
        BigDecimal remaining = fund.getTargetAmount().subtract(balance);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0)
            return null;

        List<FundTransaction> moves = transactionRepository.findByFundIdAndDeletedFalse(fund.getId());
        LocalDate firstDay = moves.stream().map(FundTransaction::getTransactionDate)
                .min(Comparator.naturalOrder()).orElse(null);
        if (firstDay == null)
            return null;

        LocalDate today = LocalDate.now(clock);
        LocalDate threeMonthsAgo = today.minusMonths(3);
        BigDecimal paid = moves.stream()
                .filter(t -> t.getTransactionDate().isAfter(firstDay)
                        && t.getTransactionDate().isAfter(threeMonthsAgo))
                .map(FundTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Полные месяцы — годовщины первого дня не позже сегодня, от одного до трёх. Не
        // ChronoUnit.MONTHS.between: с 31.01 по 30.04 он даёт два, хотя 31.01 + 3 месяца = 30.04
        // (ревью Codex на #135).
        long months = 1;
        while (months < 3 && !firstDay.plusMonths(months + 1).isAfter(today)) months++;
        BigDecimal avgMonthly = paid.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        if (avgMonthly.compareTo(BigDecimal.ZERO) <= 0)
            return null;

        long monthsLeft = remaining.divide(avgMonthly, 0, RoundingMode.CEILING).longValue();
        return today.plusMonths(monthsLeft);
    }
}
