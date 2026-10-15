import { describe, it, expect, beforeEach } from 'vitest';
import {
    emptyState, loadSandbox, saveSandbox, forgetRef, isEnabled, enabledIndex,
    reconcile, differs,
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

/** Владелец черновика — id из /api/v1/me. */
const OWNER = '00000000-0000-0000-0000-00000000000a';
const FUND: SandboxRef = { type: 'FUND', id: 'f1' };
const EVENT: SandboxRef = { type: 'EVENT', id: 'e1' };

function state(): SandboxState {
    return {
        enabled: [{ ref: FUND, amount: 60000, date: '2026-12-14', stretchMonths: 5 }],
        adhoc: [{ ref: null, amount: 12000, date: '2026-08-20' }],
    };
}

describe('sandboxStorage', () => {
    beforeEach(() => localStorage.clear());

    it('load без записи → пустое состояние', () => {
        expect(loadSandbox(OWNER)).toEqual(emptyState());
    });

    it('save затем load — round-trip переживает перезагрузку', () => {
        saveSandbox(OWNER, state());
        expect(loadSandbox(OWNER)).toEqual(state());
    });

    it('битый JSON → пустое состояние, не падает', () => {
        localStorage.setItem('selfin.sandbox.v1.' + OWNER, '{ не json');
        expect(loadSandbox(OWNER)).toEqual(emptyState());
    });

    // ANO-152: двое на одном браузере — у каждого своя примерка.
    it('черновик одного человека не читается другим', () => {
        saveSandbox(OWNER, state());
        expect(loadSandbox('другой-человек')).toEqual(emptyState());
        expect(loadSandbox(OWNER)).toEqual(state());
    });

    it('общий черновик до ANO-152 никому не отдаётся и стирается: чей он — не узнать', () => {
        localStorage.setItem('selfin.sandbox.v1', JSON.stringify(state()));
        expect(loadSandbox(OWNER)).toEqual(emptyState());
        expect(localStorage.getItem('selfin.sandbox.v1')).toBeNull();
    });

    it('forgetRef убирает ref из enabled и excluded', () => {
        const s = { ...state(), excluded: [FUND] };
        const after = forgetRef(s, FUND);
        expect(isEnabled(after, FUND)).toBe(false);
        expect(after.adhoc).toHaveLength(1);
    });

    it('isEnabled / enabledIndex', () => {
        const s = state();
        expect(isEnabled(s, FUND)).toBe(true);
        expect(isEnabled(s, EVENT)).toBe(false);
        expect(enabledIndex(s, FUND)).toBe(0);
        expect(enabledIndex(s, EVENT)).toBe(-1);
    });

    it('reconcile вычищает исчезнувшие refs, adhoc сохраняет', () => {
        const s = state(); // enabled FUND:f1, adhoc без ref
        const r = reconcile(s, [{ ref: { type: 'FUND', id: 'other' }, wishlistStatus: 'OPEN' }]);
        expect(r.enabled).toHaveLength(0);
        expect(r.adhoc).toHaveLength(1);          // adhoc не трогаем
        expect(differs(s, r)).toBe(true);
    });

    it('reconcile ничего не трогает, если ref жив и не зафиксирован', () => {
        const s = state();
        const r = reconcile(s, [{ ref: FUND, wishlistStatus: 'OPEN' }]);
        expect(differs(s, r)).toBe(false);
    });

    it('reconcile выкидывает зафиксированный ref — иначе tryOn вечно 400-ит', () => {
        const s = state();
        const r = reconcile(s, [{ ref: FUND, wishlistStatus: 'FIXED' }]);
        expect(r.enabled).toHaveLength(0);
        expect(differs(s, r)).toBe(true);
    });

    it('старый черновик с полем excluded читается без падения (поле отбрасывается)', () => {
        localStorage.setItem('selfin.sandbox.v1.' + OWNER, JSON.stringify({
            enabled: [], adhoc: [], excluded: [{ type: 'FUND', id: 'old' }],
        }));
        expect(loadSandbox(OWNER)).toEqual(emptyState());
    });
});
