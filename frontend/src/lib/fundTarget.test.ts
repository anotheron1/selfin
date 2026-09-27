import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fundTarget } from './fundTarget';
import { fmtRub } from './format';

describe('fundTarget — цель на карточке копилки (ANO-199)', () => {
    it('без цели нет процента, «Накоплено» — одна сумма', () => {
        expect(fundTarget({ targetAmount: null, currentBalance: 1000 }))
            .toEqual({ pct: null, amountLine: fmtRub(1000) });
    });

    it('цель 0 — то же, что без цели: не «100%» и не «1 ₽ / 0 ₽»', () => {
        expect(fundTarget({ targetAmount: 0, currentBalance: 1 }))
            .toEqual({ pct: null, amountLine: fmtRub(1) });
    });

    it('половина цели — 50% и «100 ₽ / 200 ₽»', () => {
        expect(fundTarget({ targetAmount: 200, currentBalance: 100 }))
            .toEqual({ pct: 50, amountLine: `${fmtRub(100)} / ${fmtRub(200)}` });
    });

    it('сверх цели процент не больше 100', () => {
        expect(fundTarget({ targetAmount: 100, currentBalance: 150 }).pct).toBe(100);
    });
});

describe('карточка копилки берёт цель из fundTarget (ANO-199)', () => {
    // Компонентных тестов в проекте нет — сторож читает исходник. Раньше процент считался
    // в самой карточке: пустая цель давала «: 100», а «{fund.targetAmount && …}» при нуле
    // рисовал отдельный «0».
    const src = readFileSync(new URL('../pages/Funds.tsx', import.meta.url), 'utf8');

    it('процент и строка «Накоплено» — из fundTarget', () => {
        expect(src).toContain('fundTarget(fund)');
        expect(src).toContain('{amountLine}');
    });

    it('процент и полоса — только когда цель есть', () => {
        expect(src).toMatch(/pct != null && \(\s*<span[^>]*>\{pct\}%<\/span>/);
        expect(src).toMatch(/pct != null && \(\s*<Progress/);
    });

    it('своего расчёта процента в карточке нет', () => {
        expect(src).not.toMatch(/fund\.currentBalance\s*\/\s*fund\.targetAmount/);
        expect(src).not.toMatch(/\{fund\.targetAmount\s*&&/);
    });
});
