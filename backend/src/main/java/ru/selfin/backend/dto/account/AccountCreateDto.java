package ru.selfin.backend.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Тело запроса на создание и на обновление счёта (спека §5.3: «Создание счёта — имя плюс
 * природа; всё остальное имеет дефолт и правится потом»).
 *
 * <p>Флаг «счёт по умолчанию» сюда сознательно не входит: он меняется отдельным
 * {@code PATCH /accounts/{id}/default}, потому что это не свойство одного счёта, а
 * перестановка на два счёта сразу — старый обязан потерять флаг в той же транзакции.
 *
 * <p>Создание — только ответом (срез 4, п. 4): {@code purpose} обязателен, оси счёта сервер раскладывает сам,
 * {@code kind} и {@code trackBalance} не читаются. Прежний путь с природой ушёл вместе с листом «Счетов» (PR 4).
 * Обновление — по осям: {@code kind} обязателен, {@code purpose} и {@code balance} не читаются.
 *
 * @param kind         природа; обязательна при обновлении
 * @param trackBalance {@code null} — взять дефолт природы (§3.1): DEBIT/DEPOSIT/CREDIT со
 *                     слежением, CASH без («расход при снятии»)
 * @param sortOrder    {@code null} — 100, как в схеме
 * @param balance      остаток при заведении — первое обновление сегодня; у кредитки — доступное сейчас.
 *                     {@code null} — обновления нет («первое обновление»). Только у карты с остатком
 */
public record AccountCreateDto(
        @NotBlank @Size(max = 255) String name,
        AccountKind kind,
        Boolean trackBalance,
        UUID purposeCategoryId,
        @PositiveOrZero BigDecimal creditLimit,
        @PositiveOrZero BigDecimal availableFloor,
        Integer sortOrder,
        Purpose purpose,
        @PositiveOrZero BigDecimal balance
) {

    /** Обновление по осям — без ответа и остатка. */
    public AccountCreateDto(String name, AccountKind kind, Boolean trackBalance, UUID purposeCategoryId,
                            BigDecimal creditLimit, BigDecimal availableFloor, Integer sortOrder) {
        this(name, kind, trackBalance, purposeCategoryId, creditLimit, availableFloor, sortOrder, null, null);
    }

    /** Ответ на «Для чего эта карта?» (`S4-A1`). Основной среди ответов нет: она одна и есть с первого входа. */
    public enum Purpose { SPEND, SAVINGS, CREDIT }
}
