import { describe, expect, it } from 'vitest';
import { QuickCloseGuard } from './quickCloseGuard';

describe('кружок занят, пока закрытие брони не разрешилось (ревью #56)', () => {
    it('второе касание той же брони во время запроса не пишет второй факт', () => {
        const g = new QuickCloseGuard();
        expect(g.begin('A')).toBe(true);
        expect(g.begin('A')).toBe(false);
        expect(g.held('A')).toBe(true);
    });

    it('закрытие другой брони не освобождает первую', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        g.begin('B');
        expect(g.held('A')).toBe(true);
        g.failed('B');
        expect(g.held('A')).toBe(true);
        expect(g.held('B')).toBe(false);
    });

    it('запрос упал — факта нет, кружок снова доступен', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        g.failed('A');
        expect(g.held('A')).toBe(false);
        expect(g.begin('A')).toBe(true);
    });

    it('факт записан — кружок занят, пока журнал не перечитан после записи', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        g.wrote('A');
        expect(g.held('A')).toBe(true);
        const read = g.readStarted();
        g.readSucceeded(read);
        expect(g.held('A')).toBe(false);
    });

    it('чтение, начатое до записи, бронь не освобождает: в нём ещё старый остаток', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        const early = g.readStarted();
        g.wrote('A');
        g.readSucceeded(early);
        expect(g.held('A')).toBe(true);
    });

    it('чтение освобождает только брони, записанные до его начала', () => {
        const g = new QuickCloseGuard();
        g.begin('B');
        g.wrote('B');
        g.begin('A');
        const read = g.readStarted();
        g.wrote('A');
        g.readSucceeded(read);
        expect(g.held('B')).toBe(false);
        expect(g.held('A')).toBe(true);
    });

    it('чтение после записи не удалось — кружок остаётся занятым, строка знает почему', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        g.wrote('A');
        const read = g.readStarted();
        g.readFailed(read);
        expect(g.held('A')).toBe(true);
        expect(g.stale('A')).toBe(true);
        expect(g.begin('A')).toBe(false);
    });

    it('следующее удачное чтение снимает и занятость, и пометку', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        g.wrote('A');
        g.readFailed(g.readStarted());
        g.readSucceeded(g.readStarted());
        expect(g.held('A')).toBe(false);
        expect(g.stale('A')).toBe(false);
    });

    it('неудачное чтение, начатое до записи, пометку не ставит', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        const early = g.readStarted();
        g.wrote('A');
        g.readFailed(early);
        expect(g.stale('A')).toBe(false);
    });
});

describe('попытка кружка живёт, пока журнал не показал её исход (ANO-192, ревью #104)', () => {
    const DATA = { date: '2026-09-27', factAmount: 192 };

    it('запрос упал — повтор с тем же ключом: сервер мог записать, а ответ потеряться', () => {
        const g = new QuickCloseGuard();
        g.begin('A');
        const first = g.attempts.keyFor('fact:A', DATA);
        g.failed('A');
        g.begin('A');
        expect(g.attempts.keyFor('fact:A', DATA)).toBe(first);
    });

    it('журнал перечитан после сбоя — попытка кончилась: такая же запись позже — новый ключ', () => {
        // Иначе оставленная попытка жила бы до конца сессии: записанный факт удалили, такой же
        // записали заново — сервер вернул бы удалённый по старому ключу, и запись молча пропала бы.
        const g = new QuickCloseGuard();
        g.begin('A');
        const first = g.attempts.keyFor('fact:A', DATA);
        g.failed('A');
        g.readSucceeded(g.readStarted());
        expect(g.attempts.keyFor('fact:A', DATA)).not.toBe(first);
    });

    it('чтение, начатое до сбоя, попытку не кончает: исхода записи в нём ещё нет', () => {
        const g = new QuickCloseGuard();
        const early = g.readStarted();
        g.begin('A');
        const first = g.attempts.keyFor('fact:A', DATA);
        g.failed('A');
        g.readSucceeded(early);
        expect(g.attempts.keyFor('fact:A', DATA)).toBe(first);
    });

    it('чтение не кончает попытку, которая ещё в пути', () => {
        const g = new QuickCloseGuard();
        const read = g.readStarted();
        g.begin('A');
        const first = g.attempts.keyFor('fact:A', DATA);
        g.readSucceeded(read);
        g.failed('A');
        expect(g.attempts.keyFor('fact:A', DATA)).toBe(first);
    });
});
