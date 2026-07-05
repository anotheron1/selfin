package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.EventSnapshot;
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

    public PocketResultDto getPocket(String rawScope, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(rawScope);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 1. Горизонт (спека §4)
        boolean fallback = false;
        LocalDate horizonEnd;
        switch (scope.type()) {
            case NEXT_INCOME -> {
                Optional<LocalDate> next = eventRepository.findNextPlannedIncomeDate(
                        asOfDate, asOfDate.plusDays(NEXT_INCOME_SEARCH_DAYS));
                if (next.isPresent()) {
                    horizonEnd = next.get();
                } else {
                    horizonEnd = asOfDate.plusDays(FALLBACK_HORIZON_DAYS);
                    fallback = true;
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
