import type { SandboxRef, SandboxTryOn } from '../types/api';
import { refKey, sameRef } from './sandboxMath';

/**
 * Состояние окна примерки, переживающее перезагрузку (§7). Никогда не трогает
 * Dashboard — это чисто клиентский черновик «что я сейчас примеряю».
 */
export interface SandboxState {
    /** Включённые в примерку item'ы (по ref) с их — возможно подкрученными — параметрами. */
    enabled: SandboxTryOn[];
    /** Ad-hoc траты «а если». */
    adhoc: SandboxTryOn[];
}

const KEY = 'selfin.sandbox.v1';

export function emptyState(): SandboxState {
    return { enabled: [], adhoc: [] };
}

/** Читает черновик; поля старых версий (excluded) молча отбрасываются. */
export function loadSandbox(): SandboxState {
    try {
        const raw = localStorage.getItem(KEY);
        if (!raw) return emptyState();
        const p = JSON.parse(raw) as Partial<SandboxState>;
        return {
            enabled: Array.isArray(p.enabled) ? p.enabled : [],
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

/** Убирает ref из черновика (после фиксации/возврата в обсуждение, §8). */
export function forgetRef(state: SandboxState, ref: SandboxRef): SandboxState {
    return {
        enabled: state.enabled.filter(t => !sameRef(t.ref, ref)),
        adhoc: state.adhoc,
    };
}

/** Минимум от SandboxItem, нужный для сверки черновика. */
export interface DraftSyncItem {
    ref: SandboxRef;
    wishlistStatus: string | null;
}

/**
 * Сверяет черновик с тем, что реально знает сервер. Выкидывает из примерки:
 * 1) ссылки на исчезнувшие элементы (удалены/сконвертированы/DISMISSED в другом месте);
 * 2) ссылки на **зафиксированные** — они уже сидят в baseline, и повторная отправка
 *    их в tryOn даёт вечный 400 «нужен парный exclude», намертво вешая примерку.
 *
 * adhoc (без ref) сохраняется как есть.
 */
export function reconcile(state: SandboxState, items: DraftSyncItem[]): SandboxState {
    const usable = new Set(
        items.filter(i => i.wishlistStatus !== 'FIXED').map(i => refKey(i.ref)),
    );
    const alive = (ref: SandboxRef | null) => ref == null || usable.has(refKey(ref));
    return {
        enabled: state.enabled.filter(t => alive(t.ref)),
        adhoc: state.adhoc,
    };
}

/**
 * Изменил ли reconcile черновик. Сравнение по длине корректно ровно потому, что
 * reconcile только фильтрует enabled и не трогает adhoc — при изменении правила
 * фильтрации сравнение надо усилить.
 */
export function differs(a: SandboxState, b: SandboxState): boolean {
    return a.enabled.length !== b.enabled.length || a.adhoc.length !== b.adhoc.length;
}

/** Есть ли ref среди включённых. */
export function isEnabled(state: SandboxState, ref: SandboxRef): boolean {
    return state.enabled.some(t => sameRef(t.ref, ref));
}

/** Индекс включённого ref (для замены параметров), либо -1. */
export function enabledIndex(state: SandboxState, ref: SandboxRef): number {
    return state.enabled.findIndex(t => sameRef(t.ref, ref));
}

export { refKey };
