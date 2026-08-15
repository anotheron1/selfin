package ru.selfin.backend.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * @param trackBalance {@code null} — взять дефолт природы (§3.1): DEBIT/DEPOSIT/CREDIT со
 *                     слежением, CASH без («расход при снятии»)
 * @param sortOrder    {@code null} — 100, как в схеме
 */
public record AccountCreateDto(
        @NotBlank @Size(max = 255) String name,
        @NotNull AccountKind kind,
        Boolean trackBalance,
        UUID purposeCategoryId,
        @PositiveOrZero BigDecimal creditLimit,
        @PositiveOrZero BigDecimal availableFloor,
        Integer sortOrder
) {}
