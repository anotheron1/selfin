import { describe, it, expect, beforeEach } from 'vitest';
import {
    emptyState, loadSandbox, saveSandbox, forgetRef, isEnabled, isExcluded, enabledIndex,
} from './sandboxStorage';
import type { SandboxState } from './sandboxStorage';
import type { SandboxRef } from '../types/api';

// Vitest-окружение проекта — node (без DOM). Подкладываем in-memory localStorage:
// прод-код обращается к нему напрямую (в браузере он есть), в тесте — этот мок.
class MemoryStorage {
    private map = new Map<string, string>();
    getItem(k: string) { return this.map.has(k) ? this.map.get(k)! : null; }
    setItem(k: string, v: string) { this.map.set(k, v); }
    removeItem(k: string) { this.map.delete(k); }
    clear() { this.map.clear(); }
}
(globalThis as unknown as { localStorage: MemoryStorage }).localStorage = new MemoryStorage();

const FUND: SandboxRef = { type: 'FUND', id: 'f1' };
const EVENT: SandboxRef = { type: 'EVENT', id: 'e1' };

function state(): SandboxState {
    return {
        enabled: [{ ref: FUND, amount: 60000, date: '2026-12-14', stretchMonths: 5 }],
        excluded: [{ type: 'FUND', id: 'egypt' }],
        adhoc: [{ ref: null, amount: 12000, date: '2026-08-20' }],
    };
}

describe('sandboxStorage', () => {
    beforeEach(() => localStorage.clear());

    it('load без записи → пустое состояние', () => {
        expect(loadSandbox()).toEqual(emptyState());
    });

    it('save затем load — round-trip переживает перезагрузку', () => {
        saveSandbox(state());
        expect(loadSandbox()).toEqual(state());
    });

    it('битый JSON → пустое состояние, не падает', () => {
        localStorage.setItem('selfin.sandbox.v1', '{ не json');
        expect(loadSandbox()).toEqual(emptyState());
    });

    it('forgetRef убирает ref из enabled и excluded', () => {
        const s = { ...state(), excluded: [FUND] };
        const after = forgetRef(s, FUND);
        expect(isEnabled(after, FUND)).toBe(false);
        expect(isExcluded(after, FUND)).toBe(false);
        expect(after.adhoc).toHaveLength(1);
    });

    it('isEnabled / isExcluded / enabledIndex', () => {
        const s = state();
        expect(isEnabled(s, FUND)).toBe(true);
        expect(isEnabled(s, EVENT)).toBe(false);
        expect(isExcluded(s, { type: 'FUND', id: 'egypt' })).toBe(true);
        expect(enabledIndex(s, FUND)).toBe(0);
        expect(enabledIndex(s, EVENT)).toBe(-1);
    });
});
