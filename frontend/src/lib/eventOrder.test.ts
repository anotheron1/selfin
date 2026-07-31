import { describe, it, expect } from 'vitest';
import { compareByRecency, byRecencyDesc, type OrderableEvent } from './eventOrder';

const ev = (id: string, date: string | null, createdAt: string): OrderableEvent =>
    ({ id, date, createdAt });

describe('eventOrder: детерминированный порядок журнала (ANO-7)', () => {
    it('сначала по дате операции', () => {
        const list = [
            ev('a', '2026-07-20', '2026-07-01T10:00:00'),
            ev('b', '2026-07-05', '2026-07-02T10:00:00'),
        ];
        expect(list.slice().sort(compareByRecency).map(e => e.id)).toEqual(['b', 'a']);
        expect(list.slice().sort(byRecencyDesc).map(e => e.id)).toEqual(['a', 'b']);
    });

    it('внутри дня — по моменту ввода, а не по алфавиту', () => {
        const list = [
            ev('поздний', '2026-07-10', '2026-07-10T18:00:00'),
            ev('ранний', '2026-07-10', '2026-07-10T09:00:00'),
        ];
        expect(list.slice().sort(compareByRecency).map(e => e.id)).toEqual(['ранний', 'поздний']);
    });

    it('одинаковый createdAt — порядок всё равно стабилен (пачка recurring)', () => {
        const same = '2026-07-10T09:00:00';
        const list = [
            ev('ccc', '2026-07-10', same),
            ev('aaa', '2026-07-10', same),
            ev('bbb', '2026-07-10', same),
        ];
        const once = list.slice().sort(compareByRecency).map(e => e.id);
        const twice = list.slice().reverse().sort(compareByRecency).map(e => e.id);
        expect(once).toEqual(['aaa', 'bbb', 'ccc']);
        // Ключевое свойство: результат не зависит от исходного порядка входа
        expect(twice).toEqual(once);
    });

    it('записи без даты не роняют компаратор', () => {
        const list = [
            ev('nodate', null, '2026-07-10T09:00:00'),
            ev('dated', '2026-07-10', '2026-07-10T08:00:00'),
        ];
        expect(() => list.slice().sort(compareByRecency)).not.toThrow();
        expect(list.slice().sort(compareByRecency).map(e => e.id)).toEqual(['nodate', 'dated']);
    });

    it('компаратор согласован: cmp(a,b) === -cmp(b,a), равные дают 0', () => {
        const a = ev('a', '2026-07-10', '2026-07-10T09:00:00');
        const b = ev('b', '2026-07-11', '2026-07-11T09:00:00');
        expect(compareByRecency(a, b)).toBe(-compareByRecency(b, a));
        expect(compareByRecency(a, a)).toBe(0);
        // Прежний компаратор в Budget.tsx возвращал -1 при равенстве — отсюда «плавающий» последний факт
        expect(byRecencyDesc(a, a)).toBe(0);
    });
});
