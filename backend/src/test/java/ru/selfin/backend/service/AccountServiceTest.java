package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.account.AccountCreateDto;
import ru.selfin.backend.dto.account.AccountDto;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Инварианты счёта из §8 спеки — по тесту на строку таблицы краевых случаев (план Task 3.1).
 *
 * <p>Валидация живёт в сервисе, а не только в CHECK-ограничениях базы: пользователю нужен
 * внятный 400, а не 500 от нарушения ограничения. Ограничения базы остаются вторым рубежом,
 * и то, что они дублируют эти проверки, — сознательно.
 *
 * <p>Использует НАСТОЯЩИЙ {@link AccountBalanceService} поверх замоканных репозиториев: числа
 * карточки (остаток, долг, подсказка планки) считаются его правилом, и замокав сервис целиком,
 * проверишь только факт вызова.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepo;
    @Mock CategoryRepository categoryRepo;
    @Mock BalanceCheckpointRepository checkpointRepo;
    @Mock FinancialEventRepository eventRepo;
    @Mock TargetFundRepository fundRepo;

    private AccountService service;

    @BeforeEach
    void setUp() {
        AccountBalanceService balanceService =
                new AccountBalanceService(accountRepo, checkpointRepo, eventRepo);
        service = new AccountService(accountRepo, categoryRepo, eventRepo, checkpointRepo,
                fundRepo, balanceService);
    }

    private void savePassesThrough() {
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static BalanceCheckpoint checkpoint(Account a, LocalDate date, String amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(new BigDecimal(amount)).account(a)
                .build();
    }

    private static FinancialEvent expenseFact(LocalDate date, Category category, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID()).date(date).type(EventType.EXPENSE)
                .category(category).eventKind(EventKind.FACT).factAmount(new BigDecimal(amount))
                .status(EventStatus.EXECUTED).priority(Priority.MEDIUM).deleted(false)
                .build();
    }

    private static Category category(String name) {
        return Category.builder().id(UUID.randomUUID()).name(name)
                .type(CategoryType.EXPENSE).priority(Priority.MEDIUM).build();
    }

    private static AccountCreateDto dto(String name, AccountKind kind, Boolean track) {
        return new AccountCreateDto(name, kind, track, null, null, null, null);
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(expected);
    }

    // === Дефолты природы (спека §3.1) ===

    @Test
    @DisplayName("DEBIT создаётся со слежением за остатком, CASH — без него («расход при снятии»)")
    void create_appliesTrackingDefaultsByKind() {
        savePassesThrough();
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        assertThat(service.create(dto("Карта", AccountKind.DEBIT, null)).trackBalance()).isTrue();
        assertThat(service.create(dto("Наличные", AccountKind.CASH, null)).trackBalance()).isFalse();
        assertThat(service.create(dto("Вклад", AccountKind.DEPOSIT, null)).trackBalance()).isTrue();
        assertThat(service.create(dto("Кредитка", AccountKind.CREDIT, null)).trackBalance()).isTrue();
    }

    @Test
    @DisplayName("Явный trackBalance перебивает дефолт природы")
    void create_explicitTrackBalanceWins() {
        savePassesThrough();
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        assertThat(service.create(dto("Бензин", AccountKind.DEBIT, false)).trackBalance()).isFalse();
        assertThat(service.create(dto("Копилка налом", AccountKind.CASH, true)).trackBalance()).isTrue();
    }

    // === §8: краевые случаи ===

    @Test
    @DisplayName("§8: планка выше лимита — 400")
    void create_floorAboveLimit_rejected() {
        AccountCreateDto in = new AccountCreateDto("Кредитка", AccountKind.CREDIT, true, null,
                new BigDecimal("100000"), new BigDecimal("120000"), null);

        assertThatThrownBy(() -> service.create(in))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§8: планка равна лимиту — разрешено (граница включительная)")
    void create_floorEqualToLimit_allowed() {
        savePassesThrough();
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        AccountDto out = service.create(new AccountCreateDto("Кредитка", AccountKind.CREDIT, true,
                null, new BigDecimal("100000"), new BigDecimal("100000"), null));

        assertThat(out.availableFloor()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("§3.1: кредитные поля у некредитного счёта — 400")
    void create_creditFieldsOnNonCreditAccount_rejected() {
        AccountCreateDto in = new AccountCreateDto("Карта", AccountKind.DEBIT, true, null,
                new BigDecimal("100000"), null, null);

        assertThatThrownBy(() -> service.create(in))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§8: попытка выключить слежение у вклада — 400")
    void update_deposit_turningOffTracking_rejected() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        when(accountRepo.findById(deposit.getId())).thenReturn(Optional.of(deposit));

        assertThatThrownBy(() -> service.update(deposit.getId(),
                dto("Вклад", AccountKind.DEPOSIT, false)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§8: назначить дефолтным конверт без слежения — 400 (он не может принимать "
            + "безадресные операции: считать их нечем)")
    void makeDefault_untrackedEnvelope_rejected() {
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false).build();
        when(accountRepo.findById(envelope.getId())).thenReturn(Optional.of(envelope));

        assertThatThrownBy(() -> service.makeDefault(envelope.getId()))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(envelope.isDefaultAccount()).isFalse();
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§8: назначить дефолтным кредитку — 400")
    void makeDefault_creditAccount_rejected() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true).build();
        when(accountRepo.findById(credit.getId())).thenReturn(Optional.of(credit));

        assertThatThrownBy(() -> service.makeDefault(credit.getId()))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§8: назначить дефолтным вклад — 400 (он полу-ликвид, а не касса)")
    void makeDefault_depositAccount_rejected() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        when(accountRepo.findById(deposit.getId())).thenReturn(Optional.of(deposit));

        assertThatThrownBy(() -> service.makeDefault(deposit.getId()))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("Смена дефолтного: флаг СНАЧАЛА снимается со старого и сбрасывается в базу, "
            + "и только потом ставится новому — иначе частичный unique-индекс отвергнет "
            + "промежуточное состояние с двумя дефолтными")
    void makeDefault_clearsOldBeforeSettingNew() {
        Account oldDefault = AccountFixtures.defaultAccount();
        Account target = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountRepo.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(oldDefault));
        when(accountRepo.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        savePassesThrough();
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        AccountDto out = service.makeDefault(target.getId());

        assertThat(oldDefault.isDefaultAccount()).isFalse();
        assertThat(target.isDefaultAccount()).isTrue();
        assertThat(out.isDefault()).isTrue();

        InOrder order = inOrder(accountRepo);
        order.verify(accountRepo).saveAndFlush(oldDefault);
        order.verify(accountRepo).save(target);
    }

    @Test
    @DisplayName("§8: удаление дефолтного счёта — 409, сначала назначь другой")
    void delete_defaultAccount_rejectedWithConflict() {
        Account defaultAccount = AccountFixtures.defaultAccount();
        when(accountRepo.findById(defaultAccount.getId())).thenReturn(Optional.of(defaultAccount));

        assertThatThrownBy(() -> service.delete(defaultAccount.getId()))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(defaultAccount.isDeleted()).isFalse();
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§8: удаление обычного счёта — мягкое; чекпоинты не трогаются")
    void delete_regularAccount_softDeletesAndKeepsCheckpoints() {
        Account account = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepo.findById(account.getId())).thenReturn(Optional.of(account));
        savePassesThrough();

        service.delete(account.getId());

        assertThat(account.isDeleted()).isTrue();
        verify(accountRepo).save(account);
        verify(checkpointRepo, never()).deleteById(any());
        verify(checkpointRepo, never()).delete(any());
    }

    @Test
    @DisplayName("§8: удалённая категория-зона снимается со счёта, счёт остаётся жить")
    void clearPurposeCategory_nullsReferenceAndKeepsAccount() {
        Category fuel = category("Бензин");
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false)
                .purposeCategory(fuel).build();
        when(accountRepo.findAllByPurposeCategoryIdAndDeletedFalse(fuel.getId()))
                .thenReturn(List.of(envelope));

        service.clearPurposeCategory(fuel.getId());

        assertThat(envelope.getPurposeCategory()).isNull();
        assertThat(envelope.isDeleted()).isFalse();
        verify(accountRepo).save(envelope);
    }

    @Test
    @DisplayName("Удаление счёта, на котором лежит цель — 409: накопленное после удаления "
            + "показало бы ноль, а восстановить его неоткуда")
    void delete_accountHoldingAFund_rejectedWithConflict() {
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();
        when(accountRepo.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(fundRepo.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(
                TargetFund.builder().id(UUID.randomUUID()).name("На квартиру")
                        .accountId(deposit.getId()).build()));

        assertThatThrownBy(() -> service.delete(deposit.getId()))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(deposit.isDeleted()).isFalse();
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("§3.2: смена природы через границу «кредитная / некредитная» у счёта с "
            + "остатками — 400: те же суммы означали бы другое, и долг возник бы из ниоткуда")
    void update_kindAcrossCreditBoundary_withCheckpoints_rejected() {
        Account card = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepo.findById(card.getId())).thenReturn(Optional.of(card));
        when(checkpointRepo.existsByAccountId(card.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.update(card.getId(), new AccountCreateDto(
                "Кредитка", AccountKind.CREDIT, true, null, new BigDecimal("200000"), null, null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(card.getKind()).isEqualTo(AccountKind.DEBIT);
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("Та же смена природы у счёта БЕЗ остатков разрешена — переворачивать нечего")
    void update_kindAcrossCreditBoundary_withoutCheckpoints_allowed() {
        Account card = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepo.findById(card.getId())).thenReturn(Optional.of(card));
        when(checkpointRepo.existsByAccountId(card.getId())).thenReturn(false);
        savePassesThrough();
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        AccountDto out = service.update(card.getId(), new AccountCreateDto(
                "Кредитка", AccountKind.CREDIT, true, null, new BigDecimal("200000"), null, null));

        assertThat(out.kind()).isEqualTo(AccountKind.CREDIT);
    }

    @Test
    @DisplayName("Переход DEBIT → CASH с остатками разрешён: сумма означает одно и то же")
    void update_kindWithinNonCreditKinds_allowedEvenWithCheckpoints() {
        Account card = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepo.findById(card.getId())).thenReturn(Optional.of(card));
        savePassesThrough();
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        AccountDto out = service.update(card.getId(),
                new AccountCreateDto("Наличные", AccountKind.CASH, true, null, null, null, null));

        assertThat(out.kind()).isEqualTo(AccountKind.CASH);
        // Проверка существования чекпоинтов для этого перехода вообще не нужна
        verify(checkpointRepo, never()).existsByAccountId(any());
    }

    // === Числа карточки (спека §5.3) ===

    @Test
    @DisplayName("§5.3: счёт со слежением отдаёт остаток и дату последнего якоря, "
            + "а «выделено за месяц» у него null")
    void dto_trackedAccount_showsBalanceAndAnchorDate() {
        Account account = AccountFixtures.account(AccountKind.DEBIT, true).build();
        LocalDate today = LocalDate.now();
        LocalDate anchorDate = today.minusDays(3);
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(account));
        when(checkpointRepo.findLatestForAccountAt(account.getId(), today))
                .thenReturn(Optional.of(checkpoint(account, anchorDate, "40000")));

        AccountDto out = service.findAll().get(0);

        assertThat(out.balance()).isEqualByComparingTo("40000");
        assertThat(out.balanceDate()).isEqualTo(anchorDate);
        assertThat(out.allocatedThisMonth()).isNull();
    }

    @Test
    @DisplayName("§8: счёт со слежением, но без единого чекпоинта, молчит — остаток null, "
            + "а не ноль: ноль читался бы как «денег нет», хотя мы их просто не знаем")
    void dto_trackedAccountWithoutCheckpoint_reportsNullBalance() {
        Account account = AccountFixtures.account(AccountKind.DEBIT, true).build();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(account));
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        AccountDto out = service.findAll().get(0);

        assertThat(out.balance()).isNull();
        assertThat(out.balanceDate()).isNull();
    }

    @Test
    @DisplayName("§5.3: конверт без слежения отдаёт «выделено за месяц» по своей категории-зоне "
            + "и НЕ отдаёт остаток — обещать остаток мы не можем")
    void dto_untrackedEnvelope_showsAllocatedThisMonth() {
        Category fuel = category("Бензин");
        Category groceries = category("Продукты");
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false)
                .purposeCategory(fuel).build();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(envelope));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(monthStart, today)).thenReturn(List.of(
                expenseFact(monthStart, fuel, "3000"),
                expenseFact(today, fuel, "4000"),
                // чужая категория в тот же месяц — не должна попасть в число
                expenseFact(today, groceries, "9000")
        ));

        AccountDto out = service.findAll().get(0);

        assertThat(out.allocatedThisMonth()).isEqualByComparingTo("7000");
        assertThat(out.balance()).isNull();
        assertThat(out.purposeCategoryName()).isEqualTo("Бензин");
    }

    @Test
    @DisplayName("У конверта без слежения нет и ДАТЫ остатка, даже если чекпоинт когда-то был: "
            + "одинокая дата рядом с «Выделено за месяц» читалась бы как дата этого выделения")
    void dto_untrackedAccountWithOldCheckpoint_reportsNoBalanceDate() {
        Category fuel = category("Бензин");
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false)
                .purposeCategory(fuel).build();
        LocalDate today = LocalDate.now();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(envelope));
        when(checkpointRepo.findLatestForAccountAt(envelope.getId(), today))
                .thenReturn(Optional.of(checkpoint(envelope, today.minusDays(60), "5000")));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        AccountDto out = service.findAll().get(0);

        assertThat(out.balance()).isNull();
        assertThat(out.balanceDate()).isNull();
    }

    @Test
    @DisplayName("«Выделено за месяц» считает ТОЛЬКО расходные факты своей категории: доход по той "
            + "же категории (кэшбэк), факт по хотелке и голый план в число не входят")
    void dto_allocatedThisMonth_ignoresIncomeWishlistAndPlans() {
        Category fuel = category("Бензин");
        Account envelope = AccountFixtures.account(AccountKind.DEBIT, false)
                .purposeCategory(fuel).build();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        FinancialEvent wishlistFact = expenseFact(today, fuel, "5000");
        wishlistFact.setWishlistStatus(WishlistStatus.FIXED);
        FinancialEvent cashback = FinancialEvent.builder()
                .id(UUID.randomUUID()).date(today).type(EventType.INCOME)
                .category(fuel).eventKind(EventKind.FACT).factAmount(new BigDecimal("2000"))
                .status(EventStatus.EXECUTED).priority(Priority.MEDIUM).deleted(false).build();
        FinancialEvent planOnly = FinancialEvent.builder()
                .id(UUID.randomUUID()).date(today).type(EventType.EXPENSE)
                .category(fuel).eventKind(EventKind.PLAN).plannedAmount(new BigDecimal("9000"))
                .status(EventStatus.PLANNED).priority(Priority.MEDIUM).deleted(false).build();

        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(envelope));
        when(eventRepo.findAllByDeletedFalseAndDateBetween(monthStart, today)).thenReturn(List.of(
                expenseFact(monthStart, fuel, "3000"),
                wishlistFact, cashback, planOnly));

        // Только 3 000. С хотелкой было бы 8 000, с кэшбэком 10 000, с планом 19 000.
        assertThat(service.findAll().get(0).allocatedThisMonth()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("Конверт без слежения и без зоны ответственности: считать нечего — null, а не ноль")
    void dto_untrackedWithoutPurposeCategory_reportsNullAllocated() {
        Account envelope = AccountFixtures.account(AccountKind.CASH, false).build();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(envelope));

        AccountDto out = service.findAll().get(0);

        assertThat(out.allocatedThisMonth()).isNull();
        verify(eventRepo, never()).findAllByDeletedFalseAndDateBetween(any(), any());
    }

    @Test
    @DisplayName("§5.3: кредитка отдаёт доступное и рассчитанный долг (лимит − доступно)")
    void dto_creditAccount_showsAvailableAndComputedDebt() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000")).build();
        LocalDate today = LocalDate.now();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(credit));
        when(checkpointRepo.findLatestForAccountAt(credit.getId(), today))
                .thenReturn(Optional.of(checkpoint(credit, today.minusDays(2), "62000")));

        AccountDto out = service.findAll().get(0);

        assertThat(out.balance()).isEqualByComparingTo("62000"); // доступно, НЕ долг
        assertThat(out.debt()).isEqualByComparingTo("138000");
    }

    @Test
    @DisplayName("§8: кредитка без чекпоинта — ни долга, ни остатка (счёт молчит, а не считает ноль)")
    void dto_creditAccountWithoutCheckpoint_reportsNullDebt() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000")).build();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(credit));
        when(checkpointRepo.findLatestForAccountAt(any(), any())).thenReturn(Optional.empty());

        AccountDto out = service.findAll().get(0);

        assertThat(out.debt()).isNull();
        assertThat(out.balance()).isNull();
    }

    @Test
    @DisplayName("§4.2: доступное выше планки — подсказка поднять планку до этого уровня")
    void dto_availableAboveFloor_suggestsRaisingFloor() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000"))
                .availableFloor(new BigDecimal("150000")).build();
        LocalDate today = LocalDate.now();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(credit));
        when(checkpointRepo.findLatestForAccountAt(credit.getId(), today))
                .thenReturn(Optional.of(checkpoint(credit, today.minusDays(1), "160000")));

        assertThat(service.findAll().get(0).floorSuggestion()).isEqualByComparingTo("160000");
    }

    @Test
    @DisplayName("§4.2: доступное ниже планки — подсказки нет (это долг к возврату, а не новый уровень)")
    void dto_availableBelowFloor_noSuggestion() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000"))
                .availableFloor(new BigDecimal("150000")).build();
        LocalDate today = LocalDate.now();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(credit));
        when(checkpointRepo.findLatestForAccountAt(credit.getId(), today))
                .thenReturn(Optional.of(checkpoint(credit, today.minusDays(1), "120000")));

        assertThat(service.findAll().get(0).floorSuggestion()).isNull();
    }

    @Test
    @DisplayName("§4.2 + §8: доступное ВЫШЕ лимита (свои деньги на кредитке) — подсказка "
            + "подрезается лимитом, иначе она предлагала бы уровень, который валидация "
            + "отвергнет 400, и кнопка вела бы в тупик")
    void dto_availableAboveLimit_suggestionIsCappedByLimit() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000"))
                .availableFloor(new BigDecimal("150000")).build();
        LocalDate today = LocalDate.now();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(credit));
        when(checkpointRepo.findLatestForAccountAt(credit.getId(), today))
                .thenReturn(Optional.of(checkpoint(credit, today.minusDays(1), "210000")));

        // 200 000 (лимит), а не 210 000 (доступное)
        assertThat(service.findAll().get(0).floorSuggestion()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("§4.2: планка не задана — подсказки нет, приложение её не выдумывает")
    void dto_noFloorSet_noSuggestion() {
        Account credit = AccountFixtures.account(AccountKind.CREDIT, true)
                .creditLimit(new BigDecimal("200000")).build();
        LocalDate today = LocalDate.now();
        when(accountRepo.findAllActiveWithPurpose()).thenReturn(List.of(credit));
        when(checkpointRepo.findLatestForAccountAt(credit.getId(), today))
                .thenReturn(Optional.of(checkpoint(credit, today.minusDays(1), "160000")));

        assertThat(service.findAll().get(0).floorSuggestion()).isNull();
    }
}
