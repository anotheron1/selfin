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

    it('ключ записи рождается только в attemptKey.ts: ключ, созданный рядом с запросом, живёт одно нажатие', () => {
        // Сторож по исходнику. Так было до ANO-192: ключ создавался внутри функции запроса,
        // и повтор после потерянного ответа уходил с новым ключом.
        const src = fileURLToPath(new URL('../', import.meta.url));
        const files = (dir: string): string[] => readdirSync(dir).flatMap(name => {
            const path = join(dir, name);
            return statSync(path).isDirectory() ? files(path) : [path];
        });
        const births = files(src)
            .filter(f => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f))
            .map(f => relative(src, f).split(sep).join('/'))
            .filter(f => f !== 'lib/attemptKey.ts')
            .filter(f => /randomUUID|generateUUID/.test(readFileSync(join(src, f), 'utf8')));

        expect(births, 'файлы, где ключ рождается мимо правила попытки').toEqual([]);
    });
});
