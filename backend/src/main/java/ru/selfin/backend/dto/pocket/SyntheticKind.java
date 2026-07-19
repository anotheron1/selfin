package ru.selfin.backend.dto.pocket;

/** Происхождение синтетического (не из БД) снапшота события (спека sandbox §5–§6). */
public enum SyntheticKind {
    /** Взнос в FIXED-копилку (§6) или взнос примерки-растяжки (§5). */
    SAVINGS_CONTRIBUTION,
    /** Разовая примерочная трата / платёж кредита примерки (ANO-16, чанк 2). */
    TRY_ON
}
