import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { periodRange } from './periodRange';

/**
 * ANO-220: диапазон таблицы «Аналитики» за 3, 6, 12 месяцев — местными датами. Через
 * toISOString() местная полночь восточнее Гринвича уезжала на день назад: «3 мес» в Москве
 * показывали четыре месяца, а последний день текущего выпадал. В UTC — то есть в CI — дефект
 * не виден, поэтому пояс зафиксирован.
 */
describe('periodRange — местными датами (ANO-220)', () => {
    const tz = process.env.TZ;
    beforeAll(() => { process.env.TZ = 'Europe/Moscow'; });
    afterAll(() => { process.env.TZ = tz; });

    it('пояс действительно московский — иначе тест зелёный и с дефектом', () => {
        expect(new Date(2026, 9, 1).getTimezoneOffset()).toBe(-180);
    });

    it('«3 мес» — три месяца, с первого дня по последний день текущего', () => {
        expect(periodRange(3, new Date(2026, 9, 4))).toEqual({ startDate: '2026-08-01', endDate: '2026-10-31' });
    });

    it('через границу года', () => {
        expect(periodRange(12, new Date(2026, 9, 4))).toEqual({ startDate: '2025-11-01', endDate: '2026-10-31' });
        expect(periodRange(3, new Date(2026, 0, 15))).toEqual({ startDate: '2025-11-01', endDate: '2026-01-31' });
    });
});
