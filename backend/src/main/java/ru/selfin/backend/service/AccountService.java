package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.BalanceCheckpointCreateDto;
import ru.selfin.backend.dto.account.AccountCreateDto;
import ru.selfin.backend.dto.account.AccountDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD и инварианты счёта (спека 2026-08-12-accounts-skeleton-design.md §3.1, §5.3, §8).
 *
 * <p><b>Валидация живёт здесь, а не только в CHECK-ограничениях базы.</b> Ограничения V20
 * (<code>chk_accounts_*</code>) повторяют те же правила и остаются вторым рубежом, но
 * пользователю нужен внятный 400 с текстом, а не 500 от нарушения ограничения. Дублирование
 * сознательное; при правке правила править оба места.
 *
 * <p>Правило «остаток счёта на дату» здесь НЕ живёт — все числа карточки берутся у
 * {@link AccountBalanceService}, единственного места этого правила.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final TargetFundRepository fundRepository;
    private final AccountBalanceService accountBalanceService;
    /** Первое обновление при заведении и обновления при смене основной (срез 4, пп. 4, 12). */
    private final BalanceCheckpointService checkpointService;
    /** «Свободно» до и после смены основной (срез 4, п. 12). */
    private final PocketService pocketService;
    /** ANO-39: «сегодня» приходит извне — иначе календарную логику не проверить детерминированно. */
    private final Clock clock;

    public List<AccountDto> findAll() {
        LocalDate today = LocalDate.now(clock);
        return accountRepository.findAllActiveWithPurpose().stream()
                .map(a -> toDto(a, today))
                .toList();
    }

    public AccountDto get(UUID id) {
        return toDto(activeById(id), LocalDate.now(clock));
    }

    /**
     * Заводит карту ответом на «Для чего эта карта?»: оси раскладывает {@link #byPurpose}; остаток, если он
     * пришёл, — первое обновление на сегодня в той же транзакции (срез 4, п. 4): ошибка обновления не оставляет
     * карты без остатка, который человек ввёл. Без ответа — 400: прежний путь с природой ушёл с листом «Счетов»
     * (срез 4, PR 4).
     */
    @Transactional
    public AccountDto create(AccountCreateDto dto) {
        if (dto.purpose() == null) throw badRequest("Answer what the card is for: purpose is required");
        Account account = byPurpose(dto);
        validate(account);
        if (dto.balance() != null && !account.isTrackBalance()) {
            throw badRequest("A card without a tracked balance takes no balance");
        }
        Account saved = accountRepository.save(account);
        LocalDate today = LocalDate.now(clock);
        if (dto.balance() != null) {
            checkpointService.create(new BalanceCheckpointCreateDto(today, dto.balance(), saved.getId()));
        }
        return toDto(saved, today);
    }

    @Transactional
    public AccountDto update(UUID id, AccountCreateDto dto) {
        Account account = activeById(id);
        AccountKind kind = requireKind(dto);
        boolean track = dto.trackBalance() != null ? dto.trackBalance() : defaultTracking(kind);
        // Копилка держится только на счёте с остатком и не на кредитке (TargetFundService.validateAccountLink):
        // иначе она повисла бы на карте, которая её не держит, с замёрзшим остатком (ревью Gemini на #243).
        if (kind == AccountKind.CREDIT || !track) {
            fundOn(id).ifPresent(f -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The goal \"" + f.getName()
                        + "\" lives on this account: a card without a tracked balance or a credit card cannot hold it — move it first");
            });
        }
        rejectCreditBoundaryChange(account, kind);
        account.setName(dto.name());
        account.setKind(kind);
        account.setTrackBalance(track);
        account.setPurposeCategory(resolveCategory(dto.purposeCategoryId()));
        account.setCreditLimit(dto.creditLimit());
        account.setAvailableFloor(dto.availableFloor());
        if (dto.sortOrder() != null) account.setSortOrder(dto.sortOrder());
        validate(account);
        return toDto(accountRepository.save(account), LocalDate.now(clock));
    }

    /**
     * Мягкое удаление (§8): чекпоинты остаются на месте, счёт уходит из всех сумм. Дефолтный
     * счёт удалить нельзя — 409, сначала назначь другой: иначе безадресным операциям некуда
     * падать, а этот инвариант держит вся модель (§4.1).
     */
    @Transactional
    public void delete(UUID id) {
        Account account = activeById(id);
        if (account.isDefaultAccount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete the default account: assign another one first");
        }
        // Копилка, лежащая на этом счёте, после удаления показала бы ноль накопленного
        // (fundBalanceAt на удалённом счёте даёт ноль), а кармашек начал бы резервировать
        // взносы на всю цель заново. Восстановить прежнее число неоткуда: собственный баланс
        // такой копилки давно нулевой. Поэтому 409, а не тихое обнуление (ревью чанка 3).
        fundOn(id).ifPresent(f -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The goal \"" + f.getName() + "\" lives on this account: unlink it first");
        });
        account.setDeleted(true);
        accountRepository.save(account);
    }

    /** Копилка, лежащая на этом счёте. */
    private Optional<TargetFund> fundOn(UUID id) {
        return fundRepository.findAllByDeletedFalseOrderByPriorityAscCreatedAtAscIdAsc().stream()
                .filter(f -> id.equals(f.getAccountId()))
                .findAny();
    }

    /**
     * Перестановка флага «счёт по умолчанию». Флаг СНАЧАЛА снимается со старого счёта и
     * сбрасывается в базу ({@code saveAndFlush}), и только потом ставится новому: частичный
     * уникальный индекс {@code uq_accounts_single_default} отвергает промежуточное состояние
     * с двумя дефолтными, а порядок записи внутри одной транзакции JPA сама не гарантирует.
     *
     * <p><b>«Свободно» не сдвигается (срез 4, п. 12).</b> Записи без карты идут только на основную: переставь один
     * флаг — записи после последнего обновления прежней основной ушли бы с неё на новую. Поэтому в той же транзакции
     * обе карты получают обновление на сегодня — свои нынешние остатки, посчитанные до перестановки, — и только потом
     * флаг. У новой основной обновление обязано быть: иначе записи пошли бы на остаток, которого никто не вводил.
     *
     * <p>Обновлений «сегодня» мало: брони с прошедшей датой держатся с даты обновления основной, и обновление сегодня
     * их отпустило бы, хотя из банка ничего не вводили. Поэтому обещание проверяется тем же расчётом: «Свободно» до
     * смены и после, в одной транзакции; разошлось — смена отменяется, 400 «Сначала обнови остатки» (решение владельца
     * 21.11, вариант Б). После настоящего обновления листом смена проходит.
     */
    @Transactional
    public AccountDto makeDefault(UUID id) {
        Account target = activeById(id);
        requireEligibleAsDefault(target);
        LocalDate today = LocalDate.now(clock);
        if (target.isDefaultAccount()) {
            return toDto(target, today);
        }
        BalanceCheckpoint targetAnchor = accountBalanceService.anchorAt(target, today).orElseThrow(() -> badRequest(
                "Update this card's balance first: records would land on a balance nobody entered"));
        BigDecimal targetBalance = accountBalanceService.balanceAt(target, today, targetAnchor);
        Optional<Account> old = accountRepository.findByDefaultAccountTrueAndDeletedFalse();
        // Без обновления у прежней основной остаток — все записи с нуля, как у движка (ANO-28).
        BigDecimal oldBalance = old.map(o -> accountBalanceService.anchorAt(o, today).isPresent()
                ? accountBalanceService.balanceAt(o, today)
                : accountBalanceService.noAnchorFallbackAt(today)).orElse(null);
        if (oldBalance != null && oldBalance.signum() < 0) {
            throw badRequest("Update balances first: the main card is below zero by records");
        }
        BigDecimal freeBefore = free(today);
        old.ifPresent(o -> {
            checkpointService.create(new BalanceCheckpointCreateDto(today, oldBalance, o.getId()));
            o.setDefaultAccount(false);
            accountRepository.saveAndFlush(o);
        });
        checkpointService.create(new BalanceCheckpointCreateDto(today, targetBalance, target.getId()));
        target.setDefaultAccount(true);
        Account saved = accountRepository.save(target);
        accountRepository.flush();
        if (free(today).compareTo(freeBefore) != 0) {
            throw badRequest("Update balances first: switching the main card would change free money");
        }
        return toDto(saved, today);
    }

    /** «Свободно» — то же число, что {@code GET /pocket}. */
    private BigDecimal free(LocalDate today) {
        return pocketService.getPocket(null, today).pocket();
    }

    /**
     * Обнуляет зону ответственности у счетов, ссылавшихся на удалённую категорию (§8:
     * «Ссылка обнуляется, счёт остаётся»). Зовётся из {@link CategoryService#delete}.
     *
     * <p>Через загрузку сущностей, а не {@code @Modifying}-запросом: счетов в системе единицы,
     * а массовый апдейт мимо контекста персистентности — ровно та ловушка, что уже стоила
     * проекту трёх латентных 500 в recurring.
     */
    @Transactional
    public void clearPurposeCategory(UUID categoryId) {
        for (Account a : accountRepository.findAllByPurposeCategoryIdAndDeletedFalse(categoryId)) {
            a.setPurposeCategory(null);
            accountRepository.save(a);
        }
    }

    // === инварианты ===

    /**
     * Ответ на «Для чего эта карта?» — по осям счёта, таблица п. 4 спеки среза 4. Основной среди ответов нет:
     * она одна и меняется только {@link #makeDefault}. Наличные — CASH, но их заводят миграция и регистрация, а
     * не этот вопрос.
     */
    private Account byPurpose(AccountCreateDto dto) {
        Account.AccountBuilder account = Account.builder()
                .name(dto.name())
                .creditLimit(dto.creditLimit())
                .availableFloor(dto.availableFloor())
                .sortOrder(dto.sortOrder() != null ? dto.sortOrder() : 100);
        return switch (dto.purpose()) {
            case SPEND -> account.kind(AccountKind.DEBIT).trackBalance(false)
                    .purposeCategory(spendingCategory(dto.purposeCategoryId())).build();
            case SAVINGS -> account.kind(AccountKind.DEPOSIT).trackBalance(true).build();
            case CREDIT -> {
                if (dto.creditLimit() == null) throw badRequest("A credit card needs its credit limit");
                yield account.kind(AccountKind.CREDIT).trackBalance(true).build();
            }
        };
    }

    /** Карта для трат пишет траты в свою категорию — значит, категория есть и она расходная. */
    private Category spendingCategory(UUID categoryId) {
        if (categoryId == null) throw badRequest("A spending card needs its expense category");
        Category category = resolveCategory(categoryId);
        if (category.getType() != CategoryType.EXPENSE) {
            throw badRequest("A spending card needs an expense category, not an income one");
        }
        return category;
    }

    /** Обновление идёт по осям — природа обязательна. */
    private static AccountKind requireKind(AccountCreateDto dto) {
        if (dto.kind() == null) throw badRequest("Account kind is required");
        return dto.kind();
    }

    /** Дефолт тумблера по природе счёта (§3.1). CASH без слежения — «расход при снятии». */
    private static boolean defaultTracking(AccountKind kind) {
        return kind != AccountKind.CASH;
    }

    /**
     * Переход природы через границу «кредитный / некредитный» запрещён, если у счёта уже есть
     * чекпоинты (§3.2). Смысл сохранённых сумм при таком переходе переворачивается: у CREDIT
     * чекпоинт хранит ДОСТУПНЫЙ остаток, у остальных — остаток на счёте. Карта с остатком
     * 40 000, объявленная кредиткой с лимитом 200 000, мгновенно получила бы долг 160 000 и
     * выпала из свободных денег — молча, без единой правки чисел (найдено ревью чанка 3).
     *
     * <p>Переходы DEBIT ↔ CASH ↔ DEPOSIT разрешены: там сумма означает одно и то же.
     * Счёт без единого чекпоинта меняет природу свободно — переворачивать нечего.
     */
    private void rejectCreditBoundaryChange(Account account, AccountKind next) {
        boolean wasCredit = account.getKind() == AccountKind.CREDIT;
        if (wasCredit == (next == AccountKind.CREDIT)) return;
        if (!checkpointRepository.existsByAccountId(account.getId())) return;
        throw badRequest(wasCredit
                ? "This account already has balances recorded as available credit: "
                        + "they would silently become a plain balance. Create a new account instead"
                : "This account already has balances recorded as a plain balance: "
                        + "they would silently become available credit. Create a new account instead");
    }

    private void validate(Account a) {
        if (a.getKind() != AccountKind.CREDIT
                && (a.getCreditLimit() != null || a.getAvailableFloor() != null)) {
            throw badRequest("Credit limit and floor are only valid for CREDIT accounts");
        }
        if (a.getAvailableFloor() != null && a.getCreditLimit() != null
                && a.getAvailableFloor().compareTo(a.getCreditLimit()) > 0) {
            throw badRequest("Available floor must not exceed the credit limit");
        }
        if (a.getKind() == AccountKind.DEPOSIT && !a.isTrackBalance()) {
            throw badRequest("A deposit without a tracked balance is meaningless: "
                    + "it is semi-liquid capital and exists only as an amount");
        }
        if (a.isDefaultAccount()) {
            requireEligibleAsDefault(a);
        }
    }

    /**
     * Счёт-приёмник обязан быть отслеживаемым и ликвидным (§3.1). Безадресные факты попадают
     * именно на него, а прибавлять их к остатку, за которым не следят, значит выдумывать число.
     */
    private void requireEligibleAsDefault(Account a) {
        if (!a.isTrackBalance()) {
            throw badRequest("The default account must have its balance tracked: "
                    + "addressless transactions land on it");
        }
        if (a.getKind() != AccountKind.DEBIT && a.getKind() != AccountKind.CASH) {
            throw badRequest("The default account must be a DEBIT or CASH account");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Account activeById(UUID id) {
        return accountRepository.findById(id)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    private Category resolveCategory(UUID categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findById(categoryId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));
    }

    // === числа карточки (§5.3) ===

    private AccountDto toDto(Account a, LocalDate today) {
        // Якорь спрашивается ОДИН раз на карточку и передаётся дальше: balanceAt, долг и дата
        // должны отвечать про один и тот же чекпоинт (ревью чанка 3 — раньше было три запроса).
        BalanceCheckpoint anchor = accountBalanceService.anchorAt(a, today).orElse(null);

        BigDecimal balance = a.isTrackBalance() && anchor != null
                ? accountBalanceService.balanceAt(a, today, anchor) : null;
        // Дата тоже только при слежении: у конверта без слежения число остатка не показывается,
        // и одинокая дата рядом с «Выделено за месяц» читалась бы как дата этого выделения.
        LocalDate balanceDate = a.isTrackBalance() && anchor != null ? anchor.getDate() : null;

        boolean creditKnown = a.getKind() == AccountKind.CREDIT
                && a.getCreditLimit() != null && anchor != null;
        BigDecimal debt = creditKnown ? accountBalanceService.creditDebtAt(a, anchor) : null;

        Category purpose = a.getPurposeCategory();
        BigDecimal allocated = !a.isTrackBalance() && purpose != null
                ? allocatedThisMonth(purpose.getId(), today) : null;

        return new AccountDto(
                a.getId(), a.getName(), a.getKind(), a.isTrackBalance(),
                purpose != null ? purpose.getId() : null,
                purpose != null ? purpose.getName() : null,
                a.getCreditLimit(), a.getAvailableFloor(), a.isDefaultAccount(), a.getSortOrder(),
                balance, balanceDate, debt, allocated,
                floorSuggestion(a, anchor));
    }

    /**
     * «Выделено за месяц» (§5.3) — сумма записанных расходных фактов по категории-зоне с
     * первого числа. Честно ровно настолько, насколько конверт используется по назначению:
     * траты той же категории с других карт смешаются с выделенным, поэтому подпись на экране
     * «Выделено за месяц», а не «Осталось» — остаток мы обещать не можем.
     */
    private BigDecimal allocatedThisMonth(UUID categoryId, LocalDate today) {
        return eventRepository.findAllByDeletedFalseAndDateBetween(today.withDayOfMonth(1), today).stream()
                .filter(e -> e.getFactAmount() != null)
                .filter(e -> e.getWishlistStatus() == null)
                .filter(e -> e.getType() == EventType.EXPENSE)
                .filter(e -> e.getCategory() != null && categoryId.equals(e.getCategory().getId()))
                .map(e -> e.getFactAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Подсказка поднять планку (§4.2): доступное на последнем чекпоинте, если оно выше
     * нынешней планки. Одно сравнение, без статистики — устойчив ли уровень, решает
     * пользователь, а не приложение.
     *
     * <p>Предложение подрезается лимитом. §8 разрешает доступному быть ВЫШЕ лимита (свои
     * деньги лежат на кредитке), а {@link #validate} требует {@code планка ≤ лимит} — без
     * подрезки подсказка предлагала бы уровень, который сама же валидация отвергнет 400,
     * и кнопка на карточке вела бы в тупик (найдено ревью чанка 3).
     */
    private static BigDecimal floorSuggestion(Account a, BalanceCheckpoint anchor) {
        if (a.getKind() != AccountKind.CREDIT || a.getAvailableFloor() == null || anchor == null) {
            return null;
        }
        BigDecimal available = anchor.getAmount();
        BigDecimal capped = a.getCreditLimit() != null && available.compareTo(a.getCreditLimit()) > 0
                ? a.getCreditLimit() : available;
        return capped.compareTo(a.getAvailableFloor()) > 0 ? capped : null;
    }
}
