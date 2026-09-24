import { describe, expect, it } from 'vitest';
import { ruPlural } from './plural';

describe('ruPlural', () => {
    const forms: [string, string, string] = ['строка', 'строки', 'строк'];

    it.each([
        [1, 'строка'], [21, 'строка'], [101, 'строка'],
        [2, 'строки'], [4, 'строки'], [22, 'строки'],
        [0, 'строк'], [5, 'строк'], [11, 'строк'], [12, 'строк'], [14, 'строк'], [111, 'строк'], [25, 'строк'],
    ])('%i — %s', (n, word) => {
        expect(ruPlural(n, forms)).toBe(word);
    });
});
