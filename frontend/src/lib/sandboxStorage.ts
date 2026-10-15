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

/** Общий ключ до ANO-152: один черновик на браузер. Чей он — не узнать, поэтому стирается, а не отдаётся. */
const SHARED_KEY = 'selfin.sandbox.v1';

/** Черновик — свой у каждого человека (ANO-152): двое на одном браузере не видят примерку друг друга. */
function key(owner: string): string {
    return `${SHARED_KEY}.${owner}`;
}

export function emptyState(): SandboxState {
    return { enabled: [], adhoc: [] };
}

/**
 * Читает черновик человека; поля старых версий (excluded) молча отбрасываются.
 *
 * @param owner id из `GET /api/v1/me`
 */
export function loadSandbox(owner: string): SandboxState {
    try {
        localStorage.removeItem(SHARED_KEY);
        const raw = localStorage.getItem(key(owner));
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

export function saveSandbox(owner: string, state: SandboxState): void {
    try {
        localStorage.setItem(key(owner), JSON.stringify(state));
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
