package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Строка ожидания, к которой встанет запись (срез 3 п. 12): по ней форма «+» говорит, что будет со «Свободно».
 *
 * @param name     название строки, без него — имя категории
 * @param recorded сколько к строке уже записано всеми её живыми записями
 */
public record FactPreviewDto(UUID id, String name, LocalDate date, BigDecimal plannedAmount, BigDecimal recorded) {
}
