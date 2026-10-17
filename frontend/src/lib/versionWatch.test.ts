import { describe, expect, it } from 'vitest';
import { stale } from './versionWatch';

describe('stale — экран и сервер из разных сборок (ANO-222)', () => {
    it('коммит сервера совпал с коммитом сборки экрана — обновляться не нужно', () => {
        expect(stale('aaa', 'aaa')).toBe(false);
    });

    it('сервер из другого коммита — экран старый: открытая вкладка или старый фронт во время выкладки', () => {
        expect(stale('aaa', 'bbb')).toBe(true);
    });

    it('сборка без GIT_COMMIT с любой стороны — сравнивать нечего', () => {
        expect(stale('unknown', 'bbb')).toBe(false);
        expect(stale('aaa', 'unknown')).toBe(false);
        expect(stale(undefined, 'bbb')).toBe(false);
    });

    it('сервер не ответил — не повод звать обновляться', () => {
        expect(stale('aaa', null)).toBe(false);
    });
});
