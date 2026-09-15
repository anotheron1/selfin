package ru.selfin.backend.service;

import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Непогашенная часть плана — единственное место правила (ANO-155).
 *
 * <p>Факт не замещает обязательство, а гасит его на свою сумму: как счёт «частично оплачен»
 * в платёжном календаре остаётся в нём на оставшуюся сумму. Раньше первый же факт снимал
 * план целиком, и чек на 300 освобождал резерв продуктов на 20 000.
 *
 * <p><b>Почему отдельным классом.</b> Правило нужно двоим: {@code PocketEngine} удерживает
 * остаток в траектории, {@code PredictionService} вычитает ровно его же из нормы. Раньше эти
 * два места держали предикат «слово в слово» — и однажды разъехались: замер по «Авто» дал
 * 16 000 вместо 12 000. Дисциплина «копируем дословно» держится на внимательности, поэтому
 * арифметика вынесена сюда, а копию сторожит {@code PlanRemainderSingleSourceTest}.
 *
 * <p>Пол в ноль обязателен: при переплате разность отрицательна, и без него переплата
 * ВЕРНУЛА бы деньги в кармашек — потратил больше, стало больше.
 */
public final class PlanRemainder {

    private PlanRemainder() {}

    /**
     * @param planned плановая сумма; {@code null} считается нулём
     * @param settled сколько по плану уже погашено фактами; {@code null} считается нулём
     * @return {@code max(0, planned − settled)}
     */
    public static BigDecimal of(BigDecimal planned, BigDecimal settled) {
        BigDecimal p = planned != null ? planned : BigDecimal.ZERO;
        BigDecimal s = settled != null ? settled : BigDecimal.ZERO;
        return p.subtract(s).max(BigDecimal.ZERO);
    }

    /**
     * Сколько по каждому плану погашено фактами-детьми (строки БД).
     *
     * <p>Группировка живёт здесь по той же причине, что и вычитание: после ревью #45 она
     * понадобилась третьему месту (мостик стартового баланса в {@code AnalyticsService}),
     * а три копии шестистрочного цикла разъезжаются ровно так же, как разъехались две
     * копии предиката.
     *
     * @param events события в рассматриваемом окне; факты вне окна не учитываются
     * @return сумма фактов по идентификатору родительского плана
     */
    public static Map<UUID, BigDecimal> settledByPlan(Collection<FinancialEvent> events) {
        return group(events,
                e -> e.getEventKind() == EventKind.FACT ? e.getParentEventId() : null,
                FinancialEvent::getFactAmount);
    }

    /**
     * То же для снимков движка.
     *
     * @param events снимки событий в траектории
     * @return сумма фактов по идентификатору родительского плана
     */
    public static Map<UUID, BigDecimal> settledBySnapshots(Collection<EventSnapshot> events) {
        return group(events,
                e -> e.eventKind() == EventKind.FACT ? e.parentEventId() : null,
                EventSnapshot::factAmount);
    }

    private static <T> Map<UUID, BigDecimal> group(Collection<T> items,
                                                   Function<T, UUID> parentId,
                                                   Function<T, BigDecimal> amount) {
        Map<UUID, BigDecimal> settled = new HashMap<>();
        for (T item : items) {
            UUID parent = parentId.apply(item);
            BigDecimal value = amount.apply(item);
            if (parent != null && value != null) settled.merge(parent, value, BigDecimal::add);
        }
        return settled;
    }
}
