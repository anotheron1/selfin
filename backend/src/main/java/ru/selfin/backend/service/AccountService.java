package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.account.AccountCreateDto;
import ru.selfin.backend.dto.account.AccountDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
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
    private final AccountBalanceService accountBalanceService;

    public List<AccountDto> findAll() {
        LocalDate today = LocalDate.now();
        return accountRepository.findAllActiveWithPurpose().stream()
                .map(a -> toDto(a, today))
                .toList();
    }

    public AccountDto get(UUID id) {
        return toDto(activeById(id), LocalDate.now());
    }

    @Transactional
    public AccountDto create(AccountCreateDto dto) {
        Account account = Account.builder()
                .name(dto.name())
                .kind(dto.kind())
                .trackBalance(dto.trackBalance() != null ? dto.trackBalance() : defaultTracking(dto.kind()))
                .purposeCategory(resolveCategory(dto.purposeCategoryId()))
                .creditLimit(dto.creditLimit())
                .availableFloor(dto.availableFloor())
                .sortOrder(dto.sortOrder() != null ? dto.sortOrder() : 100)
                .build();
        validate(account);
        return toDto(accountRepository.save(account), LocalDate.now());
    }

    @Transactional
    public AccountDto update(UUID id, AccountCreateDto dto) {
        Account account = activeById(id);
        account.setName(dto.name());
        account.setKind(dto.kind());
        account.setTrackBalance(dto.trackBalance() != null ? dto.trackBalance() : defaultTracking(dto.kind()));
        account.setPurposeCategory(resolveCategory(dto.purposeCategoryId()));
        account.setCreditLimit(dto.creditLimit());
        account.setAvailableFloor(dto.availableFloor());
        if (dto.sortOrder() != null) account.setSortOrder(dto.sortOrder());
        validate(account);
        return toDto(accountRepository.save(account), LocalDate.now());
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
        account.setDeleted(true);
        accountRepository.save(account);
    }

    /**
     * Перестановка флага «счёт по умолчанию». Флаг СНАЧАЛА снимается со старого счёта и
     * сбрасывается в базу ({@code saveAndFlush}), и только потом ставится новому: частичный
     * уникальный индекс {@code uq_accounts_single_default} отвергает промежуточное состояние
     * с двумя дефолтными, а порядок записи внутри одной транзакции JPA сама не гарантирует.
     */
    @Transactional
    public AccountDto makeDefault(UUID id) {
        Account target = activeById(id);
        requireEligibleAsDefault(target);
        if (target.isDefaultAccount()) {
            return toDto(target, LocalDate.now());
        }
        accountRepository.findByDefaultAccountTrueAndDeletedFalse().ifPresent(old -> {
            old.setDefaultAccount(false);
            accountRepository.saveAndFlush(old);
        });
        target.setDefaultAccount(true);
        return toDto(accountRepository.save(target), LocalDate.now());
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

    /** Дефолт тумблера по природе счёта (§3.1). CASH без слежения — «расход при снятии». */
    private static boolean defaultTracking(AccountKind kind) {
        return kind != AccountKind.CASH;
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
        Optional<BalanceCheckpoint> anchor = accountBalanceService.anchorAt(a, today);
        boolean hasAnchor = anchor.isPresent();

        BigDecimal balance = a.isTrackBalance() && hasAnchor
                ? accountBalanceService.balanceAt(a, today) : null;
        LocalDate balanceDate = anchor.map(BalanceCheckpoint::getDate).orElse(null);

        boolean creditKnown = a.getKind() == AccountKind.CREDIT
                && a.getCreditLimit() != null && hasAnchor;
        BigDecimal debt = creditKnown ? accountBalanceService.creditDebtAt(a, today) : null;

        Category purpose = a.getPurposeCategory();
        BigDecimal allocated = !a.isTrackBalance() && purpose != null
                ? allocatedThisMonth(purpose.getId(), today) : null;

        return new AccountDto(
                a.getId(), a.getName(), a.getKind(), a.isTrackBalance(),
                purpose != null ? purpose.getId() : null,
                purpose != null ? purpose.getName() : null,
                a.getCreditLimit(), a.getAvailableFloor(), a.isDefaultAccount(), a.getSortOrder(),
                balance, balanceDate, debt, allocated,
                floorSuggestion(a, anchor.orElse(null)));
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
     */
    private static BigDecimal floorSuggestion(Account a, BalanceCheckpoint anchor) {
        if (a.getKind() != AccountKind.CREDIT || a.getAvailableFloor() == null || anchor == null) {
            return null;
        }
        return anchor.getAmount().compareTo(a.getAvailableFloor()) > 0 ? anchor.getAmount() : null;
    }
}
