package ru.selfin.backend.dto.strategy;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Одна точка временной шкалы стратегии — один месяц.
 * См. spec, раздел 2.
 *
 * <p>Поля {@code balanceConfirmed}, {@code balanceLow}, {@code balanceHigh}
 * заполнены ТОЛЬКО для {@code phase == CURRENT} или {@code FUTURE}; для PAST они null.
 *
 * <p>Поле {@code breakdown} заполнено только если запрос пришёл с {@code withBreakdown=true}.
 *
 * @param yearMonth         месяц (формат YYYY-MM при сериализации)
 * @param phase             PAST | CURRENT | FUTURE
 * @param balance           кумулятивный баланс на конец месяца (для CURRENT — live liquidAt(today))
 * @param income            суммарный доход месяца
 * @param expense           суммарный расход месяца
 * @param nettoFlow         income − expense
 * @param balanceConfirmed  баланс БЕЗ прогноза (только recurring + manual planned). Null для PAST.
 * @param balanceLow        P25-граница fan chart. Null для PAST.
 * @param balanceHigh       P75-граница fan chart. Null для PAST.
 * @param capital           капитал (активы − обязательства) на конец месяца
 * @param assets            сумма активов
 * @param liabilities       сумма обязательств
 * @param breakdown         разбивка по категориям; null если запрос без breakdown
 */
public record StrategyTimelinePointDto(
        YearMonth yearMonth,
        StrategyPointPhase phase,

        BigDecimal balance,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal nettoFlow,

        BigDecimal balanceConfirmed,
        BigDecimal balanceLow,
        BigDecimal balanceHigh,

        BigDecimal capital,
        BigDecimal assets,
        BigDecimal liabilities,

        BreakdownDto breakdown
) {}
