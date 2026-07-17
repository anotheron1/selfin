import type { PocketResponse } from '../types/api';
import { fmtRub as fmtC } from './format';

const fmtD = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${d}.${m}`;
};

/**
 * Адаптивная фраза-ответ кармашка (ANO-13): объясняет число (оно уже dip-aware,
 * = min траектории − буфер), называет узкий день и виновника, говорит «что дальше».
 * Собирается из структурных полей /pocket — бэкенд текст не генерирует
 * (спека 2026-07-02-pocket-core-design.md §3.6, дополнение 2026-07-10).
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
    // Фолбэк «доходов нет вовсе» (label «30 дней вперёд…») требует предлога «на» после
    // «Свободно X …»; правдивый SECOND_NOT_FOUND-label начинается с «до» и читается дословно.
    const horizonPart = horizon.fallback && !horizon.label.startsWith('до')
        ? 'на 30 дней вперёд (плановых доходов нет)'
        : horizon.label;
    const cause = minPoint.drivenBy ? ` («${minPoint.drivenBy}»)` : '';
    const afterTail = afterIncome != null && afterIncome > 0 ? ` После дохода → ${fmtC(afterIncome)}.` : '';

    // Дефицит: провал траектории ниже нуля
    if (minPoint.balance < 0) {
        const tail = afterIncome == null ? ''
            : afterIncome > 0
                ? ` Доход ${fmtD(horizon.endDate)} выведет в ${fmtC(afterIncome)}.`
                : ` Даже доход ${fmtD(horizon.endDate)} не выведет в плюс — план требует правки.`;
        return `Свободных денег нет: к ${minDate} по плану не хватит ${fmtC(Math.abs(minPoint.balance))}${cause}.${tail}`;
    }

    // Буфер прогрызен: минимум положительный, но ниже буфера
    if (pocket < 0) {
        return `Впритык: в узкий день ${minDate} на счёте останется ${fmtC(minPoint.balance)} — меньше буфера ${fmtC(buffer)}${cause}.${afterTail}`;
    }

    const bufferPart = buffer > 0 ? ` Буфер ${fmtC(buffer)} уже отложен.` : '';

    // Минимум в день 0: ниже сегодняшнего траектория не опускается
    // (это НЕ значит «расходов нет» — доход внутри горизонта может перекрывать поздние траты)
    if (minPoint.date === trajectory[0]?.date) {
        return `Свободно ${fmtC(pocket)} ${horizonPart}. Минимум — уже сегодня: дальше по плану не ниже.${bufferPart}${afterTail}`;
    }

    return `Свободно ${fmtC(pocket)} ${horizonPart}. Самый узкий день — ${minDate}: на счёте останется ${fmtC(minPoint.balance)}${cause}.${bufferPart}${afterTail}`;
}
