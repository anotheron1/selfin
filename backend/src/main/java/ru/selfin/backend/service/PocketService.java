package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.FallbackKind;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Тонкая обвязка PocketEngine: собирает вход из репозиториев (спека §2).
 * Вся математика — в движке; здесь только выборки, разрешение горизонта и маппинг ошибок.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class PocketService {

    /** Кап поиска следующего дохода (спека §4): дальше квартала — не «период до дохода». */
    static final int NEXT_INCOME_SEARCH_DAYS = 92;
    static final int FALLBACK_HORIZON_DAYS = 30;
    /** Дата «с начала времён» для выборки фактов без чекпоинта. */
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final UserSettingsService settingsService;
    private final PredictionService predictionService;
    private final RecurringRuleService recurringRuleService;

    public PocketResultDto getPocket(String rawScope, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(rawScope);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 0. Материализация recurring-правил ДО резолюции горизонта (ANO-14 §6):
        //    нематериализованный доход не должен ронять NEXT/SECOND_INCOME в фолбэк,
        //    а расходы за пределами сгенерированных строк — теряться из траектории.
        //    Сбой продления не роняет чтение (REQUIRES_NEW, зеркально FundPlannerService).
        try {
            recurringRuleService.extendIndefiniteRules(asOfDate.plusMonths(36));
        } catch (Exception e) {
            log.warn("Lazy-extend of indefinite rules failed; pocket continues on existing events: {}",
                    e.getMessage());
        }

        // 1. Горизонт (спека §4)
        FallbackKind fallback = FallbackKind.NONE;
        LocalDate horizonEnd;
        switch (scope.type()) {
            case NEXT_INCOME -> {
                List<LocalDate> dates = incomeDates(asOfDate);
                if (!dates.isEmpty()) {
                    horizonEnd = dates.get(0);
                } else {
                    horizonEnd = asOfDate.plusDays(FALLBACK_HORIZON_DAYS);
                    fallback = FallbackKind.NO_INCOMES;
                }
            }
            case SECOND_INCOME -> {
                List<LocalDate> dates = incomeDates(asOfDate);
                if (dates.size() >= 2) {
                    horizonEnd = dates.get(1);
                } else if (dates.size() == 1) {
                    // Второго дохода нет — горизонт всё равно накрывает известный первый
                    // и тянется минимум на 30 дней (правдивый label, ANO-14 §4).
                    LocalDate floor = asOfDate.plusDays(FALLBACK_HORIZON_DAYS);
                    horizonEnd = dates.get(0).isAfter(floor) ? dates.get(0) : floor;
                    fallback = FallbackKind.SECOND_NOT_FOUND;
                } else {
                    horizonEnd = asOfDate.plusDays(FALLBACK_HORIZON_DAYS);
                    fallback = FallbackKind.NO_INCOMES;
                }
            }
            case MONTHS -> horizonEnd = asOfDate.plusMonths(scope.months());
            case DATE -> {
                horizonEnd = scope.date();
                if (!horizonEnd.isAfter(asOfDate)
                        || horizonEnd.isAfter(asOfDate.plusMonths(PocketScope.MAX_MONTHS))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "DATE scope must be in the future and within 36 months");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scope");
        }

        // 2. Чекпоинт и события (баланс + траектория)
        Optional<BalanceCheckpoint> checkpoint = checkpointRepository.findTopByOrderByDateDesc();
        LocalDate from = checkpoint.map(BalanceCheckpoint::getDate).orElse(EPOCH);
        List<EventSnapshot> events = eventRepository
                .findAllByDeletedFalseAndDateBetween(from, horizonEnd)
                .stream().map(EventSnapshot::from).toList();

        // 3. Просрочка (без границы месяца) и хотелки (отдельные выборки, спека §3.1, §3.4)
        List<EventSnapshot> overdue = eventRepository.findOverdueMandatoryExpenses(asOfDate)
                .stream().map(EventSnapshot::from).toList();
        List<EventSnapshot> wishlist = eventRepository
                .findByWishlistStatusInAndDeletedFalse(EnumSet.of(WishlistStatus.OPEN, WishlistStatus.FIXED))
                .stream().map(EventSnapshot::from).toList();

        // 4. Прогноз незапланированных: текущий месяц, как в старом adjustedPocket (спека §3.5)
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());
        List<FinancialEvent> monthEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);
        MonthlyForecastDto forecast = predictionService.forecastFromEvents(monthEvents, asOfDate);
        BigDecimal delta = forecast.netPredictionDelta().max(BigDecimal.ZERO);
        List<String> contributors = buildContributors(forecast);

        // 5. Буфер
        BigDecimal buffer = settingsService.getPocketSettings().bufferAmount();

        return PocketEngine.calculate(new PocketInput(asOfDate,
                checkpoint.map(BalanceCheckpoint::getAmount).orElse(BigDecimal.ZERO),
                checkpoint.map(BalanceCheckpoint::getDate).orElse(null),
                events, wishlist, overdue, scope, horizonEnd, fallback, buffer, delta, contributors));
    }

    /** Две ближайшие различные даты плановых доходов в окне поиска (NEXT/SECOND_INCOME). */
    private List<LocalDate> incomeDates(LocalDate asOfDate) {
        return eventRepository.findPlannedIncomeDates(
                asOfDate, asOfDate.plusDays(NEXT_INCOME_SEARCH_DAYS), PageRequest.of(0, 2));
    }

    /**
     * Имена линейных категорий с ожидаемым добором (для details строки UNPLANNED_FORECAST).
     * Фильтр идентичен бывшему TargetFundService.buildContributors (линейная категория =
     * без PLAN-событий, plannedLimit == 0; вклад = projection − currentFact > 0),
     * но вывод — голые имена категорий, без сумм «(+3к)».
     */
    private List<String> buildContributors(MonthlyForecastDto forecast) {
        return forecast.categories().stream()
                .filter(c -> c.plannedLimit().signum() == 0
                        && c.projectionAmount().compareTo(c.currentFact()) > 0)
                .map(CategoryForecastDto::categoryName)
                .toList();
    }
}
