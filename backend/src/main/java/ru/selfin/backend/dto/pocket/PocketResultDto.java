package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Ответ GET /api/v1/pocket (спека §3.6, §6). Один ответ кормит все представления.
 *
 * <p>Чисел «свободно» три, и они не равноправны (ANO-9 §4.2–§4.3). {@code pocket} — ответ на
 * вопрос «сколько можно тратить»; два других — оговорки к нему, и оба {@code null}, когда
 * оговаривать нечего. Порядок на экране: {@code pocket} крупно, {@code pocketAfterCreditRestore}
 * обычным, {@code pocketWithDeposits} мелким.
 */
public record PocketResultDto(
        BigDecimal pocket,
        BigDecimal currentBalance,
        BigDecimal buffer,
        /** Дата последнего якоря остатка; null — якоря ещё не было (ANO-15: напоминалка возраста). */
        LocalDate checkpointDate,
        Horizon horizon,
        MinPoint minPoint,
        List<BreakdownLine> breakdown,
        List<TrajectoryPoint> trajectory,
        List<WishlistCandidate> wishlistCandidates,
        /**
         * «Свободно, если вернуть карты к планке» (§4.2) = {@code pocket − резерв возврата}.
         * {@code null}, когда возвращать нечего: планок нет либо доступное уже выше них.
         * Может быть отрицательным — если на возврат не хватает, это и есть ответ.
         */
        BigDecimal pocketAfterCreditRestore,
        /**
         * «Свободно, если распечатать вклад» (§4.3) = {@code pocket + полу-ликвид}.
         * {@code null}, когда вкладов нет. Показывать мелким: вклад распечатывают в трудный
         * момент, а не планируют им жить, поэтому это сноска, а не третий равноправный ответ.
         */
        BigDecimal pocketWithDeposits,
        /**
         * «Кармашек с обычными тратами» (ANO-80) = минимум прогнозной кумуляты − буфер.
         *
         * <p>{@code null}, когда прогноза по горизонту нет вовсе. Причин три — галочка не
         * стоит нигде, порог наблюдения не пройден, норма целиком покрыта планами, — и
         * различать их ответу незачем: экран во всех трёх случаях делает одно и то же.
         *
         * <p>Прогноз — предположение, и потому он оговорка к главному числу, а не часть его.
         * До ANO-80 он входил в {@code pocket} молча, и человек распоряжался числом, в
         * котором сидела догадка продукта о его тратах.
         */
        BigDecimal pocketWithForecast,
        /** Минимум прогнозной кумуляты; {@code null} там же, где и число выше. */
        MinPoint minPointWithForecast,
        /**
         * «Осталось потратить» (ANO-119) — то, что движок удержал в пути денег на этом
         * горизонте, строками. Блок на дашборде показывает ровно это.
         *
         * <p>Не отдельная выборка рядом с числом, а побочный продукт того же обхода:
         * правило отбора, живущее в двух экземплярах, однажды расходится — так было
         * в ANO-82 и ANO-155.
         */
        List<UpcomingItem> upcoming,
        /**
         * Есть ли среди {@link #upcoming} хоть одно ожидание (ANO-185). Без ожиданий —
         * продуктов, бензина, кафе — кармашек завышен по построению, и экран говорит об
         * этом строкой, пока прогноз «с обычными тратами» не научился.
         *
         * <p>Берётся из тех же строк, что {@code upcoming}, а не отдельной выборкой: чем
         * кармашек держит деньги на этом горизонте, по тому и судим, полон ли план.
         */
        boolean planHasExpectations
) {
    /**
     * Копия с подставленными именами категорий (ANO-119). Имена знает сервис, а не движок:
     * у движка на входе плоские снимки без JPA.
     */
    public PocketResultDto withUpcoming(List<UpcomingItem> named) {
        return new PocketResultDto(pocket, currentBalance, buffer, checkpointDate, horizon,
                minPoint, breakdown, trajectory, wishlistCandidates, pocketAfterCreditRestore,
                pocketWithDeposits, pocketWithForecast, minPointWithForecast, named,
                planHasExpectations);
    }

    public record Horizon(PocketScope.Type type, LocalDate endDate, String label, boolean fallback) {}
    /**
     * Точка минимума; drivenBy = описание самого крупного планового расхода дня минимума.
     * null — если минимум в день 0, если в день минимума нет расходов-событий (типовой случай:
     * минимум создан размазкой прогноза незапланированных) или у события нет описания.
     */
    public record MinPoint(LocalDate date, BigDecimal balance, String drivenBy) {}
    /**
     * Точка траектории с дневными суммами (спека §3.6, дополнение 2026-07-04).
     *
     * <p>ANO-80: {@code expense} — расход ПО ПЛАНАМ. Раньше в него подмешивался прогноз, что
     * противоречило имени поля; дневной прогноз теперь берётся разностью двух балансов.
     *
     * @param balanceWithForecast вторая линия графика, «с обычными тратами»; {@code null}
     *                            в точках, где прогноз ещё не накоплен, и на всём горизонте,
     *                            если прогноза нет вовсе
     */
    public record TrajectoryPoint(LocalDate date, BigDecimal balance, BigDecimal income,
                                  BigDecimal expense, BigDecimal balanceWithForecast) {}
    public record BreakdownLine(BreakdownType type, String label, BigDecimal amount, List<String> details) {}
    public record WishlistCandidate(java.util.UUID id, String description,
                                    BigDecimal plannedAmount, LocalDate date, boolean fixed) {}

    /**
     * Строка блока «осталось потратить» (ANO-119).
     *
     * @param amount       непогашенный остаток плана, а не полная плановая сумма (ANO-155)
     * @param categoryName имя категории; движку не видно, подставляет {@code PocketService}
     * @param overdue      дата прошла, а факта нет — такие кармашек держит бронью и
     *                     показывает отдельной группой
     * @param wishlist     зафиксированная хотелка: стоит рядом со счетами, но счётом не является
     * @param priority     характер строки (ANO-100): режим нехватки предлагает сдвинуть
     *                     ожидания и хотелки, но не брони
     */
    public record UpcomingItem(java.util.UUID id, LocalDate date, String categoryName,
                               BigDecimal amount, String description,
                               boolean overdue, boolean wishlist,
                               ru.selfin.backend.model.enums.Priority priority) {
        /**
         * Та же строка с именем категории. Копия целиком, а не поле за полем: сервис,
         * вписывающий имя, не должен знать о полях, которые он не трогает, — иначе новое
         * поле однажды потеряется при пересборке.
         */
        public UpcomingItem withCategoryName(String name) {
            return new UpcomingItem(id, date, name, amount, description, overdue, wishlist, priority);
        }
    }
}
