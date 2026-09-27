import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AttemptKeys } from './attemptKey';

/** Ключи по счётчику: видно, какой по счёту ключ выдан. */
const counting = () => {
    let n = 0;
    return new AttemptKeys(() => `k${++n}`);
};

describe('ключ попытки записи (ANO-192)', () => {
    it('повтор той же записи — тот же ключ: сервер вернёт уже записанное', () => {
        const keys = counting();

        const first = keys.keyFor('fact:plan-1', { factAmount: 192 });
        const retry = keys.keyFor('fact:plan-1', { factAmount: 192 });

        expect(retry).toBe(first);
    });

    it('другие данные — новый ключ, а прежний забыт: возврат к старым данным — тоже новая запись', () => {
        // Иначе сервер вернул бы первую запись, и новый ввод молча пропал бы.
        const keys = counting();

        const first = keys.keyFor('fact:plan-1', { factAmount: 1000 });
        const changed = keys.keyFor('fact:plan-1', { factAmount: 1200 });
        const back = keys.keyFor('fact:plan-1', { factAmount: 1000 });

        expect(changed).not.toBe(first);
        expect(back).not.toBe(first);
    });

    it('запись удалась — такая же следующая получает новый ключ: две одинаковые покупки — два факта', () => {
        const keys = counting();

        const first = keys.keyFor('fact:plan-1', { factAmount: 100 });
        keys.done('fact:plan-1', first);
        const next = keys.keyFor('fact:plan-1', { factAmount: 100 });

        expect(next).not.toBe(first);
    });

    it('успех забывает только свой ключ: более новая попытка той же цели остаётся (ревью #104)', () => {
        // Две записи в одну цель наложились: вторая, с другими данными, заменила попытку. Первая
        // завершилась — её успех не должен стереть ключ второй, иначе повтор второй после
        // потерянного ответа пришёл бы с новым ключом.
        const keys = counting();

        const older = keys.keyFor('transfer:fund-1', { amount: 100 });
        const newer = keys.keyFor('transfer:fund-1', { amount: 200 });
        keys.done('transfer:fund-1', older);
        const newerRetry = keys.keyFor('transfer:fund-1', { amount: 200 });

        expect(newerRetry).toBe(newer);
    });

    it('у каждой цели своя попытка: запись в другую не сбивает ключ этой', () => {
        const keys = counting();

        const a = keys.keyFor('fact:plan-a', { factAmount: 100 });
        const b = keys.keyFor('fact:plan-b', { factAmount: 100 });
        keys.done('fact:plan-b', b);
        const aRetry = keys.keyFor('fact:plan-a', { factAmount: 100 });

        expect(b).not.toBe(a);
        expect(aRetry).toBe(a);
    });

});

/** Сторожа по исходнику: где рождается ключ и где живёт память попыток. */
describe('ключ и память попыток — только там, где им место (ANO-192)', () => {
    const src = fileURLToPath(new URL('../', import.meta.url));
    const files = (dir: string): string[] => readdirSync(dir).flatMap(name => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? files(path) : [path];
    });
    const sources = files(src)
        .filter(f => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f))
        .map(f => relative(src, f).split(sep).join('/'));
    const read = (f: string) => readFileSync(join(src, f), 'utf8');

    it('ключ записи рождается только в attemptKey.ts: ключ, созданный рядом с запросом, живёт одно нажатие', () => {
        // Так было до ANO-192: ключ создавался внутри функции запроса, и повтор после
        // потерянного ответа уходил с новым ключом.
        const births = sources
            .filter(f => f !== 'lib/attemptKey.ts')
            .filter(f => /randomUUID|generateUUID/.test(read(f)));

        expect(births, 'файлы, где ключ рождается мимо правила попытки').toEqual([]);
    });

    it('общей памяти попыток на приложение нет — она живёт в экране записи (ревью #104)', () => {
        // Общая память держала оставленную попытку до конца сессии: такая же законная запись
        // позже получала её ключ, сервер возвращал прежнюю, и новая молча пропадала.
        const screens = /useState\(\(\) => new AttemptKeys\(\)\)|setAttempts\(new AttemptKeys\(\)\)/;
        const elsewhere = sources
            .filter(f => f !== 'lib/quickCloseGuard.ts')
            .flatMap(f => read(f).split('\n').map((line, i) => ({ f, i, line })))
            .filter(({ line }) => line.includes('new AttemptKeys(') && !screens.test(line))
            .map(({ f, i }) => `${f}:${i + 1}`);

        expect(elsewhere, 'память попыток мимо экрана записи').toEqual([]);
        expect(read('components/FactCreateSheet.tsx'), 'лист факта: новая память на каждое открытие')
            .toContain('setAttempts(new AttemptKeys())');
        expect(read('components/Fab.tsx')).toContain('useState(() => new AttemptKeys())');
        expect(read('pages/Funds.tsx')).toContain('useState(() => new AttemptKeys())');
        expect(read('pages/Budget.tsx'), 'кружок — память сторожа журнала').toContain('createLinkedFact(guard.attempts, plan.id');
    });
});
