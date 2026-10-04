package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Тесты сборки входа: разрешение горизонта, кап 92 дня, маппинг ошибок на 400.
 * Идут end-to-end через публичный API PocketService → PocketInputAssembler → PocketEngine
 * (после выделения ассемблера, ANO-16 §3, покрытие сохранено как было).
 */
class PocketServiceTest {

    private FinancialEventRepository eventRepository;
    private UserSettingsService settingsService;
    private PredictionService predictionService;
    private RecurringRuleService recurringRuleService;
    private PocketService pocketService;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        settingsService = mock(UserSettingsService.class);
        predictionService = mock(PredictionService.class);
        recurringRuleService = mock(RecurringRuleService.class);

        // Дефолтного счёта нет (Mockito отдаёт Optional.empty() на незастабленный
        // Optional-метод) — эквивалент прежнего пустого checkpointRepository: чекпоинта нет.
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(eventRepository.findOverdueMandatoryExpenses(any(), any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findPlannedIncomeDates(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        TargetFundRepository fundRepository = mock(TargetFundRepository.class);
        when(fundRepository.findByWishlistStatusAndDeletedFalse(any())).thenReturn(List.of());
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        AccountBalanceService accountBalanceService = mock(AccountBalanceService.class);
        when(accountBalanceService.snapshot(any(), any()))
                .thenReturn(new AccountBalanceService.Snapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        pocketService = new PocketService(new PocketInputAssembler(eventRepository,
                settingsService, predictionService, recurringRuleService,
                fundRepository, categoryRepository, accountBalanceService), eventRepository);
    }

    /** Плановый расход с категорией — для проверки имён в списке «осталось потратить». */
    private static ru.selfin.backend.model.FinancialEvent planIn(String categoryName,
                                                                 LocalDate date, long amount) {
        ru.selfin.backend.model.Category cat = ru.selfin.backend.model.Category.builder()
                .id(java.util.UUID.randomUUID()).name(categoryName)
                .type(ru.selfin.backend.model.enums.CategoryType.EXPENSE).build();
        return ru.selfin.backend.model.FinancialEvent.builder()
                .id(java.util.UUID.randomUUID()).date(date).category(cat)
                .type(ru.selfin.backend.model.enums.EventType.EXPENSE)
                .eventKind(ru.selfin.backend.model.EventKind.PLAN)
                .status(ru.selfin.backend.model.enums.EventStatus.PLANNED)
                .priority(ru.selfin.backend.model.enums.Priority.MEDIUM)
                .plannedAmount(BigDecimal.valueOf(amount))
                .description(categoryName + " описание")
                .deleted(false).build();
    }

    @Test
    @DisplayName("ANO-119: сервис подставляет имена категорий, не меняя порядок строк")
    void upcoming_getsCategoryNames_inOriginalOrder() {
        // Движку имена не видны: он работает на плоских снимках без JPA. Подставить их —
        // работа сервиса, и подставить надо СВОЕЙ строке, а не по порядку выдачи из базы.
        var ipoteka = planIn("Ипотека", TODAY.plusDays(3), 23_600);
        var produkty = planIn("Продукты", TODAY.plusDays(1), 8_000);
        incomeDates(LocalDate.of(2026, 3, 15));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(ipoteka, produkty));
        when(eventRepository.findAllById(any())).thenReturn(List.of(ipoteka, produkty));

        PocketResultDto r = pocketService.getPocket(null, TODAY);

        assertThat(r.upcoming())
                .extracting(PocketResultDto.UpcomingItem::categoryName)
                .as("порядок списка задаёт движок — по датам, а не порядок выдачи из базы")
                .containsExactly("Продукты", "Ипотека");
    }

    @Test
    @DisplayName("ANO-100: подстановка имён не теряет характер строки")
    void upcoming_keepsPriority_throughNaming() {
        // Сервис пересобирает строки, чтобы вписать имя категории. Всё, чего он не
        // подставляет, обязано дойти до экрана как есть — иначе режим нехватки предложит
        // сдвинуть бронь.
        var ipoteka = planIn("Ипотека", TODAY.plusDays(3), 23_600);
        ipoteka.setPriority(ru.selfin.backend.model.enums.Priority.HIGH);
        var produkty = planIn("Продукты", TODAY.plusDays(1), 8_000);
        incomeDates(LocalDate.of(2026, 3, 15));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(ipoteka, produkty));
        when(eventRepository.findAllById(any())).thenReturn(List.of(ipoteka, produkty));

        PocketResultDto r = pocketService.getPocket(null, TODAY);

        assertThat(r.upcoming())
                .extracting(PocketResultDto.UpcomingItem::priority)
                .containsExactly(ru.selfin.backend.model.enums.Priority.MEDIUM,
                        ru.selfin.backend.model.enums.Priority.HIGH);
    }

    @Test
    @DisplayName("ANO-119: пустой список имён не запрашивает")
    void upcoming_empty_doesNotQueryNames() {
        incomeDates(LocalDate.of(2026, 3, 15));

        PocketResultDto r = pocketService.getPocket(null, TODAY);

        assertThat(r.upcoming()).isEmpty();
        verify(eventRepository, never()).findAllById(any());
    }

    // ── ANO-95: виновник дня минимума без описания называется категорией ────

    private static ru.selfin.backend.model.FinancialEvent planNamedIn(String categoryName, String description,
                                                                      LocalDate date, long amount) {
        var e = planIn(categoryName, date, amount);
        e.setDescription(description);
        return e;
    }

    /** День минимума 12.03: безымянный расход 8 000 и «Бензин» 3 300 — как на стенде 27.09. */
    private PocketResultDto pocketWithCulprit(String culpritDescription) {
        var culprit = planNamedIn("Продукты", culpritDescription, LocalDate.of(2026, 3, 12), 8_000);
        var benzin = planNamedIn("Авто", "Бензин", LocalDate.of(2026, 3, 12), 3_300);
        incomeDates(LocalDate.of(2026, 3, 13));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of(culprit, benzin));
        when(eventRepository.findAllById(any())).thenReturn(List.of(culprit, benzin));
        return pocketService.getPocket(null, TODAY);
    }

    @Test
    @DisplayName("ANO-95: у крупнейшего расхода дня минимума нет описания — виновник назван категорией")
    void culprit_withoutDescription_isNamedByCategory() {
        PocketResultDto r = pocketWithCulprit(null);

        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(r.minPoint().drivenBy()).isEqualTo("Продукты");
    }

    @Test
    @DisplayName("ANO-95: описание есть — виновник назван описанием, а не категорией")
    void culprit_withDescription_keepsDescription() {
        assertThat(pocketWithCulprit("Страховка").minPoint().drivenBy()).isEqualTo("Страховка");
    }

    @Test
    @DisplayName("ANO-95: описание из пробелов — как без описания")
    void culprit_blankDescription_isNamedByCategory() {
        assertThat(pocketWithCulprit("   ").minPoint().drivenBy()).isEqualTo("Продукты");
    }

    /**
     * Прогнозный минимум идёт тем же путём, но поднять прогноз в сервисном тесте — это три
     * месяца наблюдений в моках. Подстановка проверяется на готовом ответе: у главного минимума
     * виновник с описанием, у прогнозного — без него.
     */
    @Test
    @DisplayName("ANO-95: у прогнозного минимума — та же подстановка, описание главного не тронуто")
    void culprit_forecastPoint_isNamedByCategory() {
        var unnamed = planNamedIn("Продукты", null, LocalDate.of(2026, 3, 12), 8_000);
        when(eventRepository.findAllById(any())).thenReturn(List.of(unnamed));
        var main = new PocketResultDto.MinPoint(LocalDate.of(2026, 3, 12), BigDecimal.valueOf(-11_300),
                "Кафе", java.util.UUID.randomUUID());
        var withForecast = new PocketResultDto.MinPoint(LocalDate.of(2026, 3, 12), BigDecimal.valueOf(-12_000),
                null, unnamed.getId());
        PocketResultDto raw = new PocketResultDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                main, List.of(), List.of(), List.of(), null, null, BigDecimal.ZERO, withForecast, List.of(), false);

        PocketResultDto r = pocketService.withCulpritCategories(raw);

        assertThat(r.minPoint().drivenBy()).isEqualTo("Кафе");
        assertThat(r.minPointWithForecast().drivenBy()).isEqualTo("Продукты");
    }

    /** Стаб дат доходов в стандартном окне поиска (asOf, asOf+92]. */
    private void incomeDates(LocalDate... dates) {
        when(eventRepository.findPlannedIncomeDates(eq(TODAY), eq(TODAY.plusDays(92)), anyBoolean(), any()))
                .thenReturn(List.of(dates));
    }

    @Test
    @DisplayName("NEXT_INCOME: доход найден в пределах 92 дней — горизонт до него")
    void nextIncome_found() {
        incomeDates(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 30));
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(r.horizon().fallback()).isFalse();
        assertThat(r.horizon().type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    @DisplayName("SECOND_INCOME: две даты — горизонт до второй, label «до 2-го дохода»")
    void secondIncome_found() {
        incomeDates(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 30));
        PocketResultDto r = pocketService.getPocket("SECOND_INCOME", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(LocalDate.of(2026, 3, 30));
        assertThat(r.horizon().fallback()).isFalse();
        assertThat(r.horizon().type()).isEqualTo(PocketScope.Type.SECOND_INCOME);
        assertThat(r.horizon().label()).isEqualTo("до 2-го дохода 30.03");
    }

    @Test
    @DisplayName("SECOND_INCOME: один доход дальше 30 дней — горизонт накрывает его, фолбэк-label правдив")
    void secondIncome_onlyOneFarIncome() {
        incomeDates(LocalDate.of(2026, 4, 15));
        PocketResultDto r = pocketService.getPocket("SECOND_INCOME", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("до 15.04 (второй доход не найден)");
    }

    @Test
    @DisplayName("SECOND_INCOME: один доход ближе 30 дней — горизонт минимум asOf+30")
    void secondIncome_onlyOneNearIncome() {
        incomeDates(LocalDate.of(2026, 3, 10));
        PocketResultDto r = pocketService.getPocket("SECOND_INCOME", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("до 31.03 (второй доход не найден)");
    }

    @Test
    @DisplayName("SECOND_INCOME: доходов нет вовсе — обычный фолбэк «нет плановых доходов»")
    void secondIncome_noIncomes() {
        PocketResultDto r = pocketService.getPocket("SECOND_INCOME", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("30 дней вперёд (нет плановых доходов)");
    }

    @Test
    @DisplayName("Recurring-правила продлеваются ДО резолюции горизонта (ANO-14 §6)")
    void recurringExtension_beforeHorizonResolution() {
        pocketService.getPocket(null, TODAY);
        InOrder inOrder = inOrder(recurringRuleService, eventRepository);
        // Р1: до конца месяца через 36 мес — «Стратегия» просит точку на конец последнего месяца.
        inOrder.verify(recurringRuleService).extendIndefiniteRules(LocalDate.of(2029, 3, 31));
        inOrder.verify(eventRepository).findPlannedIncomeDates(any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Сбой продления recurring не роняет чтение кармашка")
    void recurringExtension_failureIsNonFatal() {
        doThrow(new RuntimeException("boom"))
                .when(recurringRuleService).extendIndefiniteRules(any());
        assertThatCode(() -> pocketService.getPocket(null, TODAY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NEXT_INCOME: дохода нет — фолбэк +30 дней с флагом")
    void nextIncome_fallback() {
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(r.horizon().fallback()).isTrue();
    }

    @Test
    @DisplayName("MONTHS:3 — горизонт +3 месяца")
    void monthsScope() {
        PocketResultDto r = pocketService.getPocket("MONTHS:3", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusMonths(3));
    }

    @Test
    @DisplayName("DATE в прошлом или дальше 36 мес → 400")
    void dateScope_validation() {
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2026-02-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2030-01-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Р1: DATE до конца месяца через 36 мес принят, днём позже — 400")
    void dateScope_untilEndOfThirtySixthMonth() {
        assertThat(pocketService.getPocket("DATE:2029-03-31", TODAY).horizon().endDate())
                .isEqualTo(LocalDate.of(2029, 3, 31));
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2029-04-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Мусорный скоуп → 400 (ResponseStatusException)")
    void garbageScope_400() {
        assertThatThrownBy(() -> pocketService.getPocket("GARBAGE", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Отрицательная netPredictionDelta зажимается в 0")
    void negativeForecast_clamped() {
        when(predictionService.forecastFromEvents(any(), any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), new BigDecimal("-500")));
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.breakdown()).noneMatch(l ->
                l.type() == ru.selfin.backend.dto.pocket.BreakdownType.UNPLANNED_FORECAST);
    }
}
