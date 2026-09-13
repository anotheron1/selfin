package ru.selfin.backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Единственное место правила «входит ли факт в остаток счёта» (ANO-82, ANO-125, спека §4).
 *
 * <p>Правило ANO-15 §5 гласит: число из банка уже содержит всё, что случилось до него.
 * Раньше это выражалось голой датой — операции дня якоря отбрасывались целиком. Отсюда
 * ANO-82: человек сверялся утром, записывал траты вечером, и весь день ввода уходил в
 * никуда. И ANO-125: перевод в копилку в тот же день выпадал из остатка, а баланс копилки
 * его засчитывал, отчего капитал рос на пустом месте.
 *
 * <p><b>Различаем по времени записи, а не по дате.</b> «Банк уже всё учёл» верно только для
 * того, что существовало В МОМЕНТ СВЕРКИ. Записанное после — попасть в то число не могло.
 * Правило 6 продуктовых правил: ре-якорь разделяет частоты ввода и вывода, склеивать их
 * обратно нельзя.
 *
 * <p>Правило переживает якорь задним числом: сравнивается время ВВОДА якоря, а не его дата.
 *
 * <p><b>Принятый риск (спека §решение 1).</b> Трата картой, случившаяся ДО момента сверки, но
 * записанная ПОСЛЕ неё, будет вычтена второй раз: признака «прошло по банку» у события нет
 * (у {@code financial_events} вовсе нет привязки к счёту, §5.1). Случай узкий — трата картой
 * ПОСЛЕ сверки в прочитанное число не попала и считается верно. Ошибка уходит в сторону
 * уменьшения остатка, то есть в безопасную.
 *
 * <p>Три места, которые раньше держали по копии этого правила и обязаны были меняться
 * синхронно (ANO-23), теперь зовут его отсюда: {@link AccountBalanceService#balanceAt},
 * {@link PocketEngine#calculate} (шаг 1) и {@link BalanceCheckpointService#findAll} (дрейф).
 * Четвёртое место — резерв просрочки {@code FinancialEventRepository.findOverdueMandatoryExpenses}
 * — про ПЛАНЫ, а не про факты, и сюда сознательно не сведено: просроченный план значит, что
 * деньги ещё НЕ двинулись, и банковское число его не содержит независимо от даты (ANO-79).
 */
public final class AnchorWindow {

    private AnchorWindow() {}

    /**
     * Входит ли факт в остаток на дату {@code upperBound}.
     *
     * @param factDate        дата факта
     * @param factCreatedAt   когда факт записан; {@code null} — считать записанным давно
     * @param anchorDate      дата якоря либо {@code null}, если якоря нет
     * @param anchorCreatedAt когда якорь введён; {@code null} вместе с {@code anchorDate}
     * @param upperBound      верхняя граница окна, включительно
     */
    public static boolean countsTowardBalance(LocalDate factDate, LocalDateTime factCreatedAt,
                                              LocalDate anchorDate, LocalDateTime anchorCreatedAt,
                                              LocalDate upperBound) {
        if (factDate == null || factDate.isAfter(upperBound)) return false;
        if (anchorDate == null) return true;             // якоря нет — нижней границы тоже
        if (factDate.isAfter(anchorDate)) return true;   // строго после якоря — как было
        if (!factDate.isEqual(anchorDate)) return false; // раньше якоря — как было

        // День якоря: решает время записи, а не дата.
        if (factCreatedAt == null || anchorCreatedAt == null) return false; // нечем решить — как было
        return factCreatedAt.isAfter(anchorCreatedAt);
    }

    /**
     * Входит ли факт в окно МЕЖДУ двумя якорями: «уже не внутри числа {@code from}, но ещё
     * внутри числа {@code to}». Нужно дрейфу ({@link BalanceCheckpointService#findAll}), который
     * сравнивает посчитанный остаток с числом ВТОРОГО якоря, а не с сегодняшним днём.
     *
     * <p>Обе границы обязаны решаться по времени записи, иначе они разъезжаются. Число из банка,
     * прочитанное в 12:00, не содержит трату, записанную в 18:00 того же дня, — значит и
     * посчитанный остаток не имеет права её содержать. Иначе дрейф показывает расхождение,
     * которого нет, а живой остаток ту же трату считает: два места разошлись бы ровно там, где
     * ANO-82 их только что свела.
     *
     * <p>Верхняя граница выражена через то же {@link #countsTowardBalance}, а не отдельной
     * проверкой: «внутри числа {@code to}» — это буквально отрицание «считается после
     * {@code to}».
     */
    public static boolean fallsBetweenAnchors(LocalDate factDate, LocalDateTime factCreatedAt,
                                              LocalDate from, LocalDateTime fromCreatedAt,
                                              LocalDate to, LocalDateTime toCreatedAt) {
        return countsTowardBalance(factDate, factCreatedAt, from, fromCreatedAt, to)
                && !countsTowardBalance(factDate, factCreatedAt, to, toCreatedAt, to);
    }
}
