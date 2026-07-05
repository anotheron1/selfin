package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Вход чистого движка. Собирается PocketService (спека §3.1).
 *
 * @param events          события для БАЛАНСА и ТРАЕКТОРИИ (диапазон дат: чекпоинт..горизонт)
 * @param wishlistEvents  отдельная выборка хотелок (OPEN + FIXED) — date-range их не достаёт
 * @param overdueEvents   просроченные обязательные PLAN(PLANNED) HIGH EXPENSE без FACT-детей,
 *                        БЕЗ границы месяца (спека §3.4)
 * @param checkpointDate  null = чекпоинта нет, баланс от нуля
 * @param horizonFallback true = плановых доходов не нашлось, горизонт условный +30 дней
 * @param unplannedForecast прогноз незапланированных трат текущего месяца (≥ 0)
 * @param forecastContributors имена категорий-виновников прогноза (для details)
 */
public record PocketInput(
        LocalDate asOfDate,
        BigDecimal checkpointAmount,
        LocalDate checkpointDate,
        List<EventSnapshot> events,
        List<EventSnapshot> wishlistEvents,
        List<EventSnapshot> overdueEvents,
        PocketScope scope,
        LocalDate horizonEnd,
        boolean horizonFallback,
        BigDecimal bufferAmount,
        BigDecimal unplannedForecast,
        List<String> forecastContributors
) {}
