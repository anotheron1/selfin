import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import {
    buildAgeHint,
    buildBreakHint,
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

describe('buildBreakHint (ANO-102)', () => {
    it('прошлый визит больше недели назад — дни без записей и предложение обновить остаток', () => {
        // Дни между визитами, а не «после визита»: запись сегодня не делает строку неправдой (ревью Codex на #187).
        expect(buildBreakHint('2026-07-10', '2026-07-01', TODAY)).toBe('С 11 по 17 июля записей нет — обновить остаток?');
    });
    it('перерыв через границу месяца — названы оба месяца', () => {
        expect(buildBreakHint('2026-06-28', '2026-06-01', TODAY)).toBe('С 29 июня по 17 июля записей нет — обновить остаток?');
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
    it('сверка — по дню прошлого визита, а не по местной дате: западнее Москвы вечером сервер уже завтра', () => {
        // Шторка пишет местную дату, сервер считает по Москве (ревью Codex на #187).
        expect(buildBreakHint('2026-07-01', '2026-07-17', TODAY)).toBeNull();
    });
    it('сверка в день прошлого визита строку не гасит: остаток устарел на весь перерыв', () => {
        expect(buildBreakHint('2026-07-01', '2026-07-01', TODAY)).toBe('С 2 по 17 июля записей нет — обновить остаток?');
    });
});

describe('строка о перерыве в «Свободно» (ANO-102)', () => {
    const src = readFileSync(new URL('../components/PocketCard.tsx', import.meta.url), 'utf8');

    it('вместо подсказки о давности, когда есть: обе предлагают сверить остаток', () => {
        expect(src).toMatch(/buildBreakHint\(lastVisit, data\.checkpointDate, serverToday\)\s*\?\?\s*buildAgeHint\(/);
    });
    it('перерыв — по календарю сервера: день визита ставит сервер (ревью Codex на #187)', () => {
        expect(src).toMatch(/const serverToday = serverTodayIso\(/);
    });
    it('вкладка, оставленная открытой, по возвращении в другой день перечитывает /me и карточку (ревью Codex на #187)', () => {
        // Телефон возвращает приложение из фона без перезагрузки: без этого строка о перерыве не появилась бы
        // ровно у того, кто вернулся.
        expect(src).toMatch(/addEventListener\('visibilitychange', resume\)/);
        expect(src).toMatch(/const resume = \(\) => \{[\s\S]{0,300}today === shownOn\.current[\s\S]{0,200}fetchMe\(\)[\s\S]{0,200}load\(\)/);
    });
    it('день прошлого визита — из /me', () => {
        expect(src).toMatch(/fetchMe\(\)[\s\S]{0,80}lastVisit/);
    });
});
