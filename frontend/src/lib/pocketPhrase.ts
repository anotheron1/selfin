import type { PocketResponse } from '../types/api';
import { fmtRub as fmtC } from './format';

const fmtD = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${d}.${m}`;
};

/**
 * Адаптивная фраза-ответ кармашка (ANO-13): говорит, что значит число, почему оно такое и что
 * дальше. Собирается из структурных полей /pocket — бэкенд текст не генерирует
 * (спека 2026-07-02-pocket-core-design.md §3.6, дополнение 2026-07-10).
 *
 * <p>ANO-99: число фраза не повторяет — оно крупно стоит над ней, а при НЗ 0 кармашек и есть
 * самый низкий остаток, и прежняя фраза называла его дважды. «Самый низкий остаток» — слово
 * людей и справки (ANO-124), а не наше «самый узкий день».
 * Спека: docs/superpowers/specs/2026-09-26-pocket-phrase-design.md.
 */
export function buildPocketPhrase(p: PocketResponse): string {
    const { pocket, buffer, minPoint, horizon, trajectory } = p;
    // Точка на КОНЦЕ ГОРИЗОНТА, не последняя: траектория может нести информационный
    // хвост за горизонтом (§3.9), а «после дохода» — это именно день дохода.
    const horizonPoint = trajectory.find(pt => pt.date === horizon.endDate)
        ?? trajectory[trajectory.length - 1];
    // Горизонт «заякорен доходом» — его конец = день дохода, можно говорить «после дохода».
    const isIncomeAnchored =
        (horizon.type === 'NEXT_INCOME' || horizon.type === 'SECOND_INCOME') && !horizon.fallback;
    const afterIncome = isIncomeAnchored && horizonPoint ? horizonPoint.balance : null;
    const minDate = fmtD(minPoint.date);
    // Фолбэк «доходов нет вовсе» (label «30 дней вперёд…») требует предлога «на»;
    // правдивый SECOND_NOT_FOUND-label начинается с «до» и читается дословно.
    const horizonPart = horizon.fallback && !horizon.label.startsWith('до')
        ? 'на 30 дней вперёд (плановых доходов нет)'
        : horizon.label;
    const cause = minPoint.drivenBy ? ` («${minPoint.drivenBy}»)` : '';
    // Предложением, а не стрелкой (правило 8).
    const afterTail = afterIncome != null && afterIncome > 0 ? ` После дохода станет ${fmtC(afterIncome)}.` : '';

    // Дефицит: провал траектории ниже нуля
    if (minPoint.balance < 0) {
        const tail = afterIncome == null ? ''
            : afterIncome > 0
                ? ` Доход ${fmtD(horizon.endDate)} выведет в ${fmtC(afterIncome)}.`
                : ` Даже доход ${fmtD(horizon.endDate)} не выведет в плюс — план требует правки.`;
        return `Свободных денег нет: к ${minDate} по плану не хватит ${fmtC(Math.abs(minPoint.balance))}${cause}.${tail}`;
    }

    // НЗ задет: минимум положительный, но ниже НЗ. Карточка в этом случае показывает режим
    // «НЗ задет» (gapMode.ts), а не фразу; ветка остаётся, чтобы фраза отвечала на любой вход.
    if (pocket < 0) {
        return `Впритык: самый низкий остаток по плану — ${fmtC(minPoint.balance)} ${minDate}${cause}, меньше НЗ ${fmtC(buffer)}.${afterTail}`;
    }

    // Что значит число — первой фразой справки кармашка: обещание состояния, а не оценка
    // (правила 10–12). «За» — только у «N мес»: «Столько можно потратить за 3 мес (до 10.10)».
    const span = horizon.type === 'MONTHS' && !horizon.fallback ? `за ${horizonPart}` : horizonPart;
    const meaning = `Столько можно потратить ${span} так, чтобы хватило на всё, что уже стоит в плане.`;
    // «Не тратится» — слово определения НЗ в справке.
    const nz = buffer > 0 ? `; НЗ ${fmtC(buffer)} из него не тратится` : '';

    // Минимум в день 0: ниже сегодняшнего траектория не опускается
    // (это НЕ значит «расходов нет» — доход внутри горизонта может перекрывать поздние траты)
    if (minPoint.date === trajectory[0]?.date) {
        return `${meaning} Ниже сегодняшнего остаток по плану не опустится${nz}.${afterTail}`;
    }

    // При НЗ 0 кармашек и есть самый низкий остаток: число не повторяем, называем его смысл.
    // При НЗ числа разные, и остаток называем.
    const lowest = buffer > 0
        ? `Самый низкий остаток по плану — ${fmtC(minPoint.balance)} ${minDate}${cause}${nz}.`
        : `Это самый низкий остаток по плану — ${minDate}${cause}.`;
    return `${meaning} ${lowest}${afterTail}`;
}
