package ru.selfin.backend.service;

import java.math.BigDecimal;

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
}
