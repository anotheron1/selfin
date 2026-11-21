package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Найденное поиском по журналу (срез 3, п. 9): «Нашлось N записей · сумма».
 *
 * @param rows     найденные строки, свежие первыми, не больше 200
 * @param total    сколько строк нашлось всего
 * @param recorded записанное в найденных строках со знаком для свободных денег: доход — плюс, расход — минус
 */
public record JournalSearchDto(List<FinancialEventDto> rows, int total, BigDecimal recorded) {
}
