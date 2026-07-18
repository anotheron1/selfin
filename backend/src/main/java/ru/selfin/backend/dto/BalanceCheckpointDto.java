package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Чекпоинт с дрейфом интервала (ANO-15 §4). Поля дрейфа вычисляются на лету
 * из цепочки, ничего не хранится; у самого раннего чекпоинта оба {@code null}.
 *
 * @param computedBalance что selfin насчитал на дату этого чекпоинта от предыдущего
 *                        (prev.amount + знаковые факты в {@code (prev.date, date]})
 * @param drift           amount − computedBalance: незаписанные потоки интервала
 */
public record BalanceCheckpointDto(
        UUID id,
        LocalDate date,
        BigDecimal amount,
        LocalDateTime createdAt,
        BigDecimal computedBalance,
        BigDecimal drift
) {}
