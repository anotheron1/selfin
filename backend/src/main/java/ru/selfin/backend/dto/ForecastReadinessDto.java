package ru.selfin.backend.dto;

/**
 * Готовность прогноза (ANO-80).
 *
 * <p>Величина ОБЩАЯ, а не покатегорийная: окно наблюдения отвечает на вопрос «давно ли
 * человек ведёт учёт», а не «давно ли он покупает одежду». Поэтому и эндпоинт один на всё,
 * а не поле в каждой категории.
 *
 * @param monthsObserved сколько полных месяцев наблюдения набралось
 * @param monthsRequired сколько нужно, чтобы медиане верить
 * @param readyFrom      месяц вида «2026-11», с которого прогноз появится; {@code null},
 *                       когда он уже готов либо трат нет вовсе и считать не от чего
 */
public record ForecastReadinessDto(int monthsObserved, int monthsRequired, String readyFrom) {}
