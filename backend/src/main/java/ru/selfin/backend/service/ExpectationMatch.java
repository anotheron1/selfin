package ru.selfin.backend.service;

import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * К какой строке ожидания встаёт запись без плана (срез 3 п. 1, решение 52). Единственное место
 * правила: им пользуются запись, её правка и подсказка формы о «Свободно».
 *
 * <p>Встав к строке, запись гасит её на свою сумму тем же путём, что факт к брони
 * ({@link PlanRemainder}), — и «Свободно» не меняется: трата уже была в плане.
 *
 * <ul>
 *   <li>кандидаты — строки с характером «Ожидание», того же типа и той же категории, что запись,
 *       не удалённые и не погашенные целиком;</li>
 *   <li>встаёт только запись с характером «Ожидание»: бронь гасит «Оплачено» или «своё», хотелке
 *       в ожидании не место;</li>
 *   <li>запись «в кредит» не встаёт (срез 4, п. 1): иначе строка погасилась бы деньгами с кредитки, и «Свободно»
 *       выросло бы на сумму покупки;</li>
 *   <li>сначала — последняя строка с датой не позже записи и не раньше, чем за шесть дней до неё:
 *       строка своей недели;</li>
 *   <li>иначе — ближайшая строка позже записи в том же месяце: разовое ожидание собирает траты до
 *       своей даты;</li>
 *   <li>иначе — ничего: запись уменьшает «Свободно» на свою сумму.</li>
 * </ul>
 */
public final class ExpectationMatch {

    /** «Своя неделя»: строка не раньше, чем за столько дней до записи. */
    static final int OWN_WEEK_DAYS_BACK = 6;

    private ExpectationMatch() {}

    /**
     * @param rows    строки-кандидаты; лишние отсеиваются здесь же
     * @param settled сколько по каждой строке погашено всеми её живыми фактами
     */
    public static Optional<FinancialEvent> pick(LocalDate date, UUID categoryId, EventType type, Priority priority,
                                                boolean onCredit, List<FinancialEvent> rows,
                                                Map<UUID, BigDecimal> settled) {
        if (priority != Priority.MEDIUM || date == null || onCredit) return Optional.empty();
        List<FinancialEvent> open = rows.stream()
                .filter(r -> isCandidate(r, categoryId, type, settled))
                .toList();
        Optional<FinancialEvent> ownWeek = open.stream()
                .filter(r -> !r.getDate().isAfter(date) && !r.getDate().isBefore(date.minusDays(OWN_WEEK_DAYS_BACK)))
                .max(Comparator.comparing(FinancialEvent::getDate));
        if (ownWeek.isPresent()) return ownWeek;
        return open.stream()
                .filter(r -> r.getDate().isAfter(date) && YearMonth.from(r.getDate()).equals(YearMonth.from(date)))
                .min(Comparator.comparing(FinancialEvent::getDate));
    }

    private static boolean isCandidate(FinancialEvent r, UUID categoryId, EventType type,
                                       Map<UUID, BigDecimal> settled) {
        return !r.isDeleted()
                && r.getEventKind() == EventKind.PLAN
                && r.getPriority() == Priority.MEDIUM
                && r.getType() == type
                && r.getCategory() != null && r.getCategory().getId().equals(categoryId)
                && r.getDate() != null
                && PlanRemainder.of(r.getPlannedAmount(), settled.get(r.getId())).signum() > 0;
    }
}
