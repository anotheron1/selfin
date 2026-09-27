import { fmtRub } from './format';

/** Что карточка копилки на «Целях» показывает о цели. */
export interface FundTargetView {
    /** Сколько накоплено от цели, в процентах, не больше 100. {@code null} — у копилки нет цели. */
    pct: number | null;
    /** Строка «Накоплено»: «100 ₽ / 200 ₽», а у копилки без цели — «1 000 ₽». */
    amountLine: string;
}

/**
 * Цель копилки на карточке (ANO-199).
 *
 * Цель необязательна: пусто или 0 — копилка без цели, и сервер считает так же
 * ({@code TargetFundService.applyStatusByBalance}). Раньше карточка читала пустую цель как
 * полную: «100%» у пустой копилки, при нуле — отдельный «0» под процентом и «0 ₽ / 0 ₽».
 * Без цели показывать долю не от чего, поэтому процента и полосы нет, а «Накоплено» — одна сумма.
 */
export function fundTarget(fund: { targetAmount: number | null; currentBalance: number }): FundTargetView {
    const target = fund.targetAmount;
    if (target == null || target <= 0) {
        return { pct: null, amountLine: fmtRub(fund.currentBalance) };
    }
    return {
        pct: Math.min(Math.round((fund.currentBalance / target) * 100), 100),
        amountLine: `${fmtRub(fund.currentBalance)} / ${fmtRub(target)}`,
    };
}
