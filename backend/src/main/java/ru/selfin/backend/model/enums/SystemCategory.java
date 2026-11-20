package ru.selfin.backend.model.enums;

/**
 * Вид системной категории: продукт пишет в неё сам и находит её по этому признаку, а не по имени
 * ({@code CategoryService.systemCategory}, столбец {@code system_kind}, миграция V37). Своя категория с
 * таким же именем системной не становится. Удалённую продукт возвращает при следующей записи (ANO-224).
 */
public enum SystemCategory {
    WISHLIST("Хотелки"),
    /** ANO-259: была «Переводы в копилки»; у кого своя «Накопление», системная осталась под старым именем (V37). */
    FUND_TRANSFER("Накопление"),
    /** Траты «потрачено на цель» (Р10-А, ANO-212). */
    GOAL_SPENDING("Цели"),
    CREDIT("Кредит");

    /** Имя, под которым продукт заводит категорию, если её ещё нет. */
    public final String title;

    SystemCategory(String title) {
        this.title = title;
    }
}
