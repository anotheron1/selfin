import type { PocketResponse } from '../types/api';

const fmtC = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);
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
    const last = trajectory[trajectory.length - 1];
    const isNextIncome = horizon.type === 'NEXT_INCOME' && !horizon.fallback;
    const afterIncome = isNextIncome && last ? last.balance : null;
    const minDate = fmtD(minPoint.date);
    const horizonPart = horizon.fallback
        ? 'на 30 дней вперёд (плановых доходов нет)'
        : isNextIncome ? `до дохода ${fmtD(horizon.endDate)}` : horizon.label;
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

    // Трат до горизонта нет: минимум в день 0
    if (minPoint.date === trajectory[0]?.date) {
        return `Свободно ${fmtC(pocket)} ${horizonPart} — трат по плану до конца горизонта нет.${bufferPart}${afterTail}`;
    }

    return `Свободно ${fmtC(pocket)} ${horizonPart}. Самый узкий день — ${minDate}: на счёте останется ${fmtC(minPoint.balance)}${cause}.${bufferPart}${afterTail}`;
}
