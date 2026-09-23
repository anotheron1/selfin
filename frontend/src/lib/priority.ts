import type { Priority } from '../types/api';

/**
 * Характер плановой строки — единственное место, где у него есть имена на экране (ANO-173).
 *
 * Канон — docs/superpowers/specs/2026-09-01-product-rules.md, раздел «Характер плановой строки»;
 * priority.test.ts сверяет имена с ним. В коде и API свойство по-прежнему Priority с константами
 * HIGH / MEDIUM / LOW: читаются они как очерёдность, но это не очерёдность, а переименование в коде
 * и базе сочтено не стоящим цены. Поэтому имя на экране — только отсюда: до ANO-173 одно свойство
 * называлось четырьмя словарями, а средний уровень на быстром вводе — точкой.
 *
 * Подсказки нарочно без «превышения» и «перерасхода»: эти слова в словаре сторожа (productRules.test.ts).
 */
export const PRIORITY_DOT_CONFIG: Record<Priority, { color: string; name: string; plural: string; hint: string }> = {
    HIGH:   { color: '#f87171', name: 'Обязательство', plural: 'Обязательства', hint: 'сумма и дата известны заранее' },
    MEDIUM: { color: '#facc15', name: 'Ожидание',      plural: 'Ожидания',      hint: 'сумма примерная, тратится по ходу' },
    LOW:    { color: '#60a5fa', name: 'Хотелка',       plural: 'Хотелки',       hint: 'если влезет' },
};

/** Подпись поля: слово владельца из требований 18.09 — «у траты бывает два разных характера». */
export const PRIORITY_FIELD_LABEL = 'Характер';

export const PRIORITY_ORDER: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];

/** «Ожидание — сумма примерная, тратится по ходу»: для всплывающих подсказок. */
export const priorityTitle = (p: Priority) => `${PRIORITY_DOT_CONFIG[p].name} — ${PRIORITY_DOT_CONFIG[p].hint}`;
