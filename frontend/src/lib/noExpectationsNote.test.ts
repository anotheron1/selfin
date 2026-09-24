import { describe, expect, it } from 'vitest';
import { noExpectationsNote } from './noExpectationsNote';

const NOTE = 'В плане нет ожиданий — продукты, бензин, кафе. Пока их нет, число выше, чем будет на деле.';

describe('noExpectationsNote (ANO-185)', () => {
    it('ожиданий в плане нет и прогноза нет — строка есть', () => {
        expect(noExpectationsNote({ planHasExpectations: false, pocketWithForecast: null })).toBe(NOTE);
    });

    it('ожидание в плане есть — строки нет', () => {
        expect(noExpectationsNote({ planHasExpectations: true, pocketWithForecast: null })).toBeNull();
    });

    it('строка «с обычными тратами» уже есть — честное число показывает она, второе сообщение не нужно', () => {
        expect(noExpectationsNote({ planHasExpectations: false, pocketWithForecast: 12000 })).toBeNull();
    });

    it('прогноз, равный нулю, — тоже прогноз: его строка на экране есть', () => {
        expect(noExpectationsNote({ planHasExpectations: false, pocketWithForecast: 0 })).toBeNull();
    });
});
