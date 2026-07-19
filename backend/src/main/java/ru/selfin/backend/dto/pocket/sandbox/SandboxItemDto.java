package ru.selfin.backend.dto.pocket.sandbox;

import ru.selfin.backend.dto.pocket.SandboxRef;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Элемент списка окна примерки с дефолтными параметрами (спека sandbox §4):
 * хотелка-событие — amount=plannedAmount, date её (может быть null), stretch 0;
 * копилка — amount=остаток, date=targetDate, stretch=максимум §5;
 * кредитная копилка — плюс ставка/срок из полей фонда.
 *
 * @param kind       WISHLIST | SAVINGS | CREDIT (выводится из типа источника/purchaseType)
 * @param inBaseline операционально «фактически развёрнут ассемблером в baseline» (§9) —
 *                   фронту не надо повторять края §6
 */
public record SandboxItemDto(
        SandboxRef ref,
        String kind,
        String name,
        BigDecimal amount,
        LocalDate date,
        Integer stretchMonthsMax,
        Integer stretchMonthsDefault,
        BigDecimal creditRate,
        Integer creditTermMonths,
        String wishlistStatus,
        boolean inBaseline
) {}
