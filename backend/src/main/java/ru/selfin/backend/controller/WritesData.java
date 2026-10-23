package ru.selfin.backend.controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Пишет ли меняющая ручка данные человека (ANO-102): удачный ответ ручки с {@code @WritesData} ставит дню визита
 * отметку «записывал». Разметка обязательна у каждой меняющей ручки — сторож в {@code VisitDaysIT}: не всякий
 * POST пишет, и молча засчитанная запись врала бы о частоте ввода (ANO-78).
 *
 * <p>Спека: docs/superpowers/specs/2026-10-19-break-hint-design.md
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WritesData {

    /** {@code false} — меняющая по методу, но данных человека не пишет: примерка, пересчёт, согласие до входа. */
    boolean value() default true;
}
