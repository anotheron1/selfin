import { fmtRub } from './format';

/**
 * Отказ сервера записать, исправить или удалить перевод в копилку (ANO-169, ANO-201).
 *
 * Факт перевода и копилка меняются только вместе; когда копилка сдвинуться не может, сервер
 * отвечает 409 с кодом в `details` — первым, дальше подсказки `fund:ИМЯ` и `holds:СУММА`.
 * Контракт зеркалит `FundMovementRefusedException` на бэкенде; текст сообщения им не служит.
 */
export const FUND_HOLDS_LESS = 'FUND_HOLDS_LESS';
export const FUND_ON_ACCOUNT = 'FUND_ON_ACCOUNT';
export const FUND_CLOSED = 'FUND_CLOSED';

/** Подсказка вида `ключ:значение`; значение может содержать двоеточие — имя копилки. */
function hint(details: string[], key: string): string | null {
    const prefix = `${key}:`;
    const found = details.find(d => d.startsWith(prefix));
    return found === undefined ? null : found.slice(prefix.length);
}

/**
 * Причина отказа словами экрана — или `null`, если это не отказ копилки.
 *
 * Тексты — что с копилкой сейчас и почему запись не меняется; без «вы» и оценок (правило 12).
 */
export function fundMovementMessage(err: unknown): string | null {
    const e = err as { status?: unknown; details?: unknown } | null | undefined;
    if (e?.status !== 409 || !Array.isArray(e.details)) return null;
    const details = e.details.filter((d): d is string => typeof d === 'string');
    const fund = hint(details, 'fund') ?? 'копилка';
    switch (details[0]) {
        case FUND_HOLDS_LESS: {
            // Без подсказки суммы не называем: Number(null) дал бы «сейчас 0 ₽» — выдуманный ноль.
            const raw = hint(details, 'holds');
            const holds = raw === null ? NaN : Number(raw);
            const now = Number.isFinite(holds) ? ` сейчас ${fmtRub(holds)}` : '';
            return `В копилке «${fund}»${now}: часть этих денег из неё уже взяли, и столько оттуда не убрать.`;
        }
        case FUND_ON_ACCOUNT:
            return `Копилка «${fund}» лежит на счёте: деньги в ней двигаются на самом счёте, переводы в неё не записываются.`;
        case FUND_CLOSED:
            return `Копилка «${fund}» удалена: её переводы остаются в журнале как история и не меняются.`;
        default:
            return null;
    }
}
