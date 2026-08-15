package ru.selfin.backend.dto.account;

import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Счёт для экрана «Счета» (спека 2026-08-12-accounts-skeleton-design.md §5.3).
 *
 * <p>Числа карточки взаимоисключающие и зависят от тумблера «слежу за остатком» — это и есть
 * ключевая механика §5.2, вынесенная наружу: у счёта со слежением заполнены {@code balance} и
 * {@code balanceDate}, у конверта без слежения — {@code allocatedThisMonth}. Одновременно оба
 * не заполняются никогда, поэтому фронт не выбирает, что показать, а показывает то, что пришло.
 *
 * @param balance            остаток на дату запроса. Для {@code CREDIT} — ДОСТУПНЫЙ остаток, не
 *                           долг (§3.2). {@code null}, если за остатком не следят ИЛИ ни одного
 *                           чекпоинта нет: ноль читался бы как «денег нет», хотя мы их не знаем
 *                           (§8, «счёт молчит, а не считает ноль»).
 * @param balanceDate        дата последнего якоря; {@code null}, если якоря нет
 * @param debt               долг по кредитке = {@code creditLimit − доступно}; {@code null} для
 *                           некредитных счетов, без лимита или без чекпоинта
 * @param allocatedThisMonth «выделено за месяц» — сумма фактов по категории-зоне с первого числа.
 *                           Только для счетов БЕЗ слежения и с заданной зоной; иначе {@code null}.
 *                           Подпись на экране «Выделено за месяц», НЕ «Осталось» (§5.3)
 * @param floorSuggestion    уровень, до которого стоит поднять планку: доступное на последнем
 *                           чекпоинте, если оно выше нынешней планки (§4.2); иначе {@code null}
 */
public record AccountDto(
        UUID id,
        String name,
        AccountKind kind,
        boolean trackBalance,
        UUID purposeCategoryId,
        String purposeCategoryName,
        BigDecimal creditLimit,
        BigDecimal availableFloor,
        boolean isDefault,
        int sortOrder,
        BigDecimal balance,
        LocalDate balanceDate,
        BigDecimal debt,
        BigDecimal allocatedThisMonth,
        BigDecimal floorSuggestion
) {}
