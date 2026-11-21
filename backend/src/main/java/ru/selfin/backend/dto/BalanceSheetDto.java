package ru.selfin.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.selfin.backend.model.enums.AccountKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Лист «Обновить остатки» (срез 4, п. 3; доска `S4-U1`): карты, у которых есть остаток, — основная, вторая
 * «на всё», накопительные, кредитки, в этом порядке. Карт для трат в листе нет: остатка у них нет.
 *
 * @param cards карты листа
 */
public record BalanceSheetDto(List<Card> cards) {

    /**
     * @param byRecords «по записям» на сегодня — правило {@code AccountBalanceService.balanceAt}: у основной
     *                  обновление плюс записи, у прочих — обновление. У кредитки — доступное. {@code null} — карту
     *                  ещё не обновляли («первое обновление»)
     * @param updatedOn дата последнего обновления; {@code null} — обновлений нет
     */
    public record Card(UUID accountId, String name, AccountKind kind, boolean isDefault,
                       BigDecimal byRecords, LocalDate updatedOn) {}

    /**
     * Введённые числа листа — одним пакетом, обновлением на сегодня. Пустое поле ({@code amount == null})
     * пропускается: карта не обновляется.
     */
    public record Batch(@NotNull @Valid List<Entry> entries) {}

    /** @param amount «в банке»; у кредитки — доступное */
    public record Entry(@NotNull UUID accountId, @PositiveOrZero BigDecimal amount) {}

    /**
     * Итог пакета — и записанного, и предпросмотра: «Свободно станет…» по нему, правило на клиенте не повторяется.
     *
     * @param cards обновлённые карты, в порядке пакета
     */
    public record Result(BigDecimal freeBefore, BigDecimal freeAfter, List<Change> cards) {}

    /**
     * @param difference «в банке» минус «по записям»: «в банке на 1 200 ₽ больше». У накопительной это и есть рост
     *                   сверх переводов — «по записям» у неё обновление плюс переводы. {@code null} — карту обновили
     *                   впервые, сравнивать не с чем
     */
    public record Change(UUID accountId, BigDecimal difference) {}
}
