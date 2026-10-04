package ru.selfin.backend.dto.wishlist;

import ru.selfin.backend.dto.pocket.SandboxRef;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Внутренний результат {@link ru.selfin.backend.service.BaselineTimelineBuilder}: остаток по
 * месяцам — прошлые, текущий и будущие, обогащённые капиталом. Текущий и будущие — из ядра (Р1).
 *
 * <p>Используется и {@code StrategyTimelineService} (как есть), и {@code WishlistSimulationService}
 * (основа «Что с капиталом» — без зафиксированного, см. {@code heldByRef}).
 *
 * @param firstMonth     первый месяц активности
 * @param currentMonth   текущий месяц
 * @param horizonEnd     последний месяц горизонта
 * @param predictionWindowMonths окно прогноза (мес)
 * @param fanEnabled     включён ли веер неопределённости
 * @param points         все точки (past + current + future), обогащённые капиталом
 * @param heldByRef      сколько ядро держит по каждой ссылке примерки (зафиксированное), по месяцам.
 *                       Ссылка без строк и копилка в плане ядра без строк — накоплена до цели или
 *                       срок в этом месяце — тоже здесь, с пустой картой: она в ядре, просто
 *                       держать по ней нечего
 */
public record TimelineSnapshot(
        YearMonth firstMonth,
        YearMonth currentMonth,
        YearMonth horizonEnd,
        int predictionWindowMonths,
        boolean fanEnabled,
        List<StrategyTimelinePointDto> points,
        Map<SandboxRef, Map<YearMonth, BigDecimal>> heldByRef
) {
    /** Без зафиксированного в ядре — тестам, которым оно не нужно. */
    public TimelineSnapshot(YearMonth firstMonth, YearMonth currentMonth, YearMonth horizonEnd,
                            int predictionWindowMonths, boolean fanEnabled,
                            List<StrategyTimelinePointDto> points) {
        this(firstMonth, currentMonth, horizonEnd, predictionWindowMonths, fanEnabled, points, Map.of());
    }
}
