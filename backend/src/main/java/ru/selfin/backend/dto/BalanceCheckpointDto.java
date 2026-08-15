package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Чекпоинт с дрейфом интервала (ANO-15 §4). Поля дрейфа вычисляются на лету
 * из цепочки, ничего не хранится; у самого раннего чекпоинта оба {@code null}.
 *
 * @param accountId       чей остаток зафиксирован (ANO-9). История нескольких счетов лежит
 *                        в одном списке, и без адреса две карты в нём неразличимы
 * @param accountName     имя счёта — чтобы фронт не тянул справочник ради подписи строки
 * @param computedBalance что selfin насчитал на дату этого чекпоинта от предыдущего
 *                        (prev.amount + знаковые факты в {@code (prev.date, date]})
 * @param drift           amount − computedBalance: незаписанные потоки интервала.
 *                        {@code null} у самого раннего чекпоинта счёта и у всех чекпоинтов
 *                        НЕ дефолтного счёта — там журнал остаток не двигает (§6)
 */
public record BalanceCheckpointDto(
        UUID id,
        LocalDate date,
        BigDecimal amount,
        UUID accountId,
        String accountName,
        LocalDateTime createdAt,
        BigDecimal computedBalance,
        BigDecimal drift
) {}
