import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fmtRub } from './format';
import {
    buildAgeHint,
    buildBreakHint,
    buildDriftPreview,
    buildMirrorLabel,
    checkpointAgeDays,
    mirrorBalance,
} from './reanchor';

const TODAY = '2026-07-18';

describe('checkpointAgeDays', () => {
    it('дни между якорем и сегодня', () => {
        expect(checkpointAgeDays('2026-07-06', TODAY)).toBe(12);
        expect(checkpointAgeDays(TODAY, TODAY)).toBe(0);
    });
    it('без якоря — null', () => {
        expect(checkpointAgeDays(null, TODAY)).toBeNull();
    });
});

describe('buildAgeHint', () => {
    it('свежий якорь (≤30 дн.) — без напоминалки', () => {
        expect(buildAgeHint('2026-07-01', TODAY)).toBeNull();
        expect(buildAgeHint('2026-06-18', TODAY)).toBeNull(); // ровно 30
    });
    it('старый якорь — возраст в строке', () => {
        expect(buildAgeHint('2026-05-12', TODAY)).toBe('остаток обновлялся 67 дн. назад');
    });
    it('якоря не было — онбординг-подсказка', () => {
        expect(buildAgeHint(null, TODAY)).toBe('остаток ещё не якорился — тапни, чтобы задать');
    });
});

describe('buildBreakHint (ANO-102)', () => {
    it('прошлый визит больше недели назад — его дата и предложение обновить остаток', () => {
        expect(buildBreakHint('2026-07-10', '2026-07-01', TODAY)).toBe('После 10 июля записей нет — обновить остаток?');
    });
    it('неделя и меньше — строки нет', () => {
        expect(buildBreakHint('2026-07-11', '2026-07-01', TODAY)).toBeNull(); // ровно 7
        expect(buildBreakHint('2026-07-17', null, TODAY)).toBeNull();
    });
    it('первый визит — строки нет', () => {
        expect(buildBreakHint(null, '2026-07-01', TODAY)).toBeNull();
    });
    it('остаток сверен сегодня — строки нет: предложение исполнено', () => {
        expect(buildBreakHint('2026-07-01', TODAY, TODAY)).toBeNull();
    });
});

describe('строка о перерыве в «Свободно» (ANO-102)', () => {
    const src = readFileSync(new URL('../components/PocketCard.tsx', import.meta.url), 'utf8');

    it('вместо подсказки о давности, когда есть: обе предлагают сверить остаток', () => {
        expect(src).toMatch(/buildBreakHint\([^)]*\)\s*\?\?\s*buildAgeHint\(/);
    });
    it('день прошлого визита — из /me', () => {
        expect(src).toMatch(/fetchMe\(\)[\s\S]{0,80}lastVisit/);
    });
});

describe('buildMirrorLabel', () => {
    it('с якорем — «считает»', () => {
        expect(buildMirrorLabel(true, 48200)).toBe(`selfin считает: ${fmtRub(48200)}`);
    });
    it('первый якорь — честная формулировка без «считает»', () => {
        expect(buildMirrorLabel(false, 48200))
            .toBe(`расчёт selfin по записанным фактам: ${fmtRub(48200)}`);
    });
});

describe('buildDriftPreview', () => {
    it('отрицательный дрейф с возрастом', () => {
        expect(buildDriftPreview(45000, 48200, 12)).toBe(`дрейф −${fmtRub(3200)} за 12 дн.`);
    });
    it('положительный дрейф', () => {
        expect(buildDriftPreview(48700, 48200, 5)).toBe(`дрейф +${fmtRub(500)} за 5 дн.`);
    });
    it('нулевой дрейф — «сходится»', () => {
        expect(buildDriftPreview(48200, 48200, 12)).toBe('дрейф 0 ₽ — расчёт сходится');
    });
    it('якорь сегодня — «за сегодня»', () => {
        expect(buildDriftPreview(48000, 48200, 0)).toBe(`дрейф −${fmtRub(200)} за сегодня`);
    });
    it('пустой ввод или первый якорь — строки нет', () => {
        expect(buildDriftPreview(null, 48200, 12)).toBeNull();
        expect(buildDriftPreview(Number.NaN, 48200, 12)).toBeNull();
        expect(buildDriftPreview(45000, 48200, null)).toBeNull();
    });
});

describe('mirrorBalance (ANO-214)', () => {
    const main = { id: 'main', isDefault: true, balance: 54900 };
    const cash = { id: 'cash', isDefault: false, balance: 5000 };

    it('основная карта со сверкой — её остаток, а не сумма свободных счетов', () => {
        expect(mirrorBalance([main, cash], '', 59900, true)).toBe(54900);
        expect(mirrorBalance([main, cash], 'main', 59900, true)).toBe(54900);
    });
    it('без сверки — число карточки: расчёт по записанным фактам', () => {
        expect(mirrorBalance([{ ...main, balance: null }, cash], '', 12000, false)).toBe(12000);
    });
    it('прочий счёт — зеркала нет: факты его не двигают', () => {
        expect(mirrorBalance([main, cash], 'cash', 59900, true)).toBeNull();
    });
    it('список счетов не пришёл — зеркала нет, а не число карточки', () => {
        expect(mirrorBalance(null, '', 59900, true)).toBeNull();
    });
});

describe('лист «Обновить остаток» берёт зеркало у сверяемого счёта (ANO-214)', () => {
    const src = readFileSync(new URL('../components/pocket/ReanchorSheet.tsx', import.meta.url), 'utf8');

    it('подпись и дрейф — по mirrorBalance, а не по числу карточки', () => {
        expect(src).toContain('mirrorBalance(');
        expect(src).not.toMatch(/buildDriftPreview\([^)]*currentBalance/);
        expect(src).not.toMatch(/buildMirrorLabel\([^)]*currentBalance/);
    });
});
