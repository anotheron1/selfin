import { describe, expect, it } from 'vitest';
import { fmtRub } from './format';
import {
    buildAgeHint,
    buildDriftPreview,
    buildMirrorLabel,
    checkpointAgeDays,
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
