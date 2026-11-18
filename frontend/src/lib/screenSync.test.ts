import { describe, expect, it } from 'vitest';
import { readJournalPosition, returnedAfterPause, saveJournalPosition } from './screenSync';

/** Возврат на вкладку и позиция журнала (срез 3, п. 8, ANO-256, ANO-126). */
describe('вернулся на вкладку', () => {
    it('после минуты и дольше — перечитать; раньше — нет', () => {
        expect(returnedAfterPause(1_000, 61_000)).toBe(true);
        expect(returnedAfterPause(1_000, 60_999)).toBe(false);
        expect(returnedAfterPause(null, 1_000_000)).toBe(false);
    });
});

describe('позиция журнала', () => {
    const storage = () => {
        const m = new Map<string, string>();
        return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v); } };
    };

    it('записанное читается тем же', () => {
        const s = storage();
        saveJournalPosition(s, { year: 2025, month: 0, scroll: 640 });
        expect(readJournalPosition(s)).toEqual({ year: 2025, month: 0, scroll: 640 });
    });

    it('пусто, испорчено или хранилища нет — ничего', () => {
        expect(readJournalPosition(storage())).toBeNull();
        const bad = storage();
        bad.setItem('journal-position', '{"year":"x"}');
        expect(readJournalPosition(bad)).toBeNull();
        bad.setItem('journal-position', 'не json');
        expect(readJournalPosition(bad)).toBeNull();
        bad.setItem('journal-position', '{"year":2026,"month":12,"scroll":0}');
        expect(readJournalPosition(bad)).toBeNull();
        expect(readJournalPosition(null)).toBeNull();
        expect(() => saveJournalPosition(null, { year: 2026, month: 1, scroll: 0 })).not.toThrow();
    });
});
