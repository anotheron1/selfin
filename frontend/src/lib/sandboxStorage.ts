import type { SandboxRef, SandboxTryOn } from '../types/api';
import { refKey, sameRef } from './sandboxMath';

/**
 * Состояние окна примерки, переживающее перезагрузку (§7). Никогда не трогает
 * Dashboard — это чисто клиентский черновик «что я сейчас примеряю».
 */
export interface SandboxState {
    /** Включённые item'ы (по ref) с их — возможно подкрученными — параметрами. */
    enabled: SandboxTryOn[];
    /** Выключенные из baseline FIXED-элементы («примерка отказа»). */
    excluded: SandboxRef[];
    /** Ad-hoc траты «а если». */
    adhoc: SandboxTryOn[];
}

const KEY = 'selfin.sandbox.v1';

export function emptyState(): SandboxState {
    return { enabled: [], excluded: [], adhoc: [] };
}

export function loadSandbox(): SandboxState {
    try {
        const raw = localStorage.getItem(KEY);
        if (!raw) return emptyState();
        const p = JSON.parse(raw) as Partial<SandboxState>;
        return {
            enabled: Array.isArray(p.enabled) ? p.enabled : [],
            excluded: Array.isArray(p.excluded) ? p.excluded : [],
            adhoc: Array.isArray(p.adhoc) ? p.adhoc : [],
        };
    } catch {
        return emptyState();
    }
}

export function saveSandbox(state: SandboxState): void {
    try {
        localStorage.setItem(KEY, JSON.stringify(state));
    } catch {
        /* приватный режим/переполнение — молча теряем черновик, не роняем UI */
    }
}

/** Убирает ref из всех коллекций состояния (после фиксации, §8). */
export function forgetRef(state: SandboxState, ref: SandboxRef): SandboxState {
    return {
        enabled: state.enabled.filter(t => !sameRef(t.ref, ref)),
        excluded: state.excluded.filter(e => !sameRef(e, ref)),
        adhoc: state.adhoc,
    };
}

/** Есть ли ref среди включённых. */
export function isEnabled(state: SandboxState, ref: SandboxRef): boolean {
    return state.enabled.some(t => sameRef(t.ref, ref));
}

/** Есть ли ref среди выключенных из baseline. */
export function isExcluded(state: SandboxState, ref: SandboxRef): boolean {
    return state.excluded.some(e => sameRef(e, ref));
}

/** Индекс включённого ref (для замены параметров), либо -1. */
export function enabledIndex(state: SandboxState, ref: SandboxRef): number {
    return state.enabled.findIndex(t => sameRef(t.ref, ref));
}

export { refKey };
