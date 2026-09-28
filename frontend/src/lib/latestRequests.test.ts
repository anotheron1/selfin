import { describe, expect, it } from 'vitest';
import { LatestRequests } from './latestRequests';

/**
 * ANO-105. Пересчётов по строке примерки бывает несколько подряд — ползунок «Когда» двигают дальше,
 * пока сервер отвечает на прошлое положение. Ответ старого запроса после ответа нового показал бы
 * график другой даты под ползунком этой.
 */
describe('применяется только ответ на последний запрос строки (ANO-105)', () => {
    it('два запроса подряд — ответ первого опоздал', () => {
        const latest = new LatestRequests();
        const first = latest.start('i1');
        const second = latest.start('i1');
        expect(latest.isLatest('i1', first)).toBe(false);
        expect(latest.isLatest('i1', second)).toBe(true);
    });

    it('строки независимы: запрос одной не отменяет ответ другой', () => {
        const latest = new LatestRequests();
        const a = latest.start('i1');
        latest.start('i2');
        expect(latest.isLatest('i1', a)).toBe(true);
    });
});
