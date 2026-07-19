package ru.selfin.backend.dto.pocket.sandbox;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Разреженное дневное событие дельты (спека sandbox §4): запись только в день,
 * когда item трогает счёт. Знак: расход −, «возврат» excluded-элемента +.
 */
public record DayDeltaDto(LocalDate date, BigDecimal delta) {}
