package ru.selfin.backend.model.enums;

/**
 * Имена системных категорий: продукт пишет в них сам и находит их по имени
 * ({@code CategoryService.systemCategory}). Переименовать системную нельзя, удалённую продукт
 * возвращает при следующей записи (ANO-224).
 */
public final class SystemCategory {
    private SystemCategory() {}

    public static final String WISHLIST_NAME = "Хотелки";
    /** Та же категория доводится миграцией V30 — имя меняется только вместе. */
    public static final String FUND_TRANSFER_NAME = "Переводы в копилки";
    /** Траты «потрачено на цель» (Р10-А, ANO-212); та же категория заводится миграцией V29 — имя меняется только вместе. */
    public static final String GOAL_SPENDING_NAME = "Цели";
    public static final String CREDIT_NAME = "Кредит";
}
