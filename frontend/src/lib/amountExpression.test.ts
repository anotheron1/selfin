import { describe, it, expect } from 'vitest';
import { parseAmountExpression as parse } from './amountExpression';

describe('amountExpression: кейс из тикета (ANO-33)', () => {
    it('три чека из продуктового складываются в одну сумму', () => {
        const r = parse('450+1230+890');
        expect(r.value).toBe(2570);
        expect(r.isExpression).toBe(true);
        expect(r.error).toBeNull();
    });

    it('обычное число — не выражение, предпросмотр не нужен', () => {
        const r = parse('2570');
        expect(r.value).toBe(2570);
        expect(r.isExpression).toBe(false);
    });
});

describe('amountExpression: арифметика', () => {
    it('вычитание', () => {
        expect(parse('1000-250').value).toBe(750);
    });

    it('умножение приоритетнее сложения', () => {
        // Неочевидно для пользователя, но это стандартная математика
        expect(parse('100+2*50').value).toBe(200);
        expect(parse('3*250').value).toBe(750);
    });

    it('дроби не дают хвостов с плавающей точкой', () => {
        // 0.1+0.2 в double = 0.30000000000000004; бэк ждёт копейки
        expect(parse('0.1+0.2').value).toBe(0.3);
        expect(parse('19.99+0.01').value).toBe(20);
    });
});

describe('amountExpression: реальный ввод', () => {
    it('запятая как десятичный разделитель', () => {
        expect(parse('1234,56').value).toBe(1234.56);
        expect(parse('10,5+10,5').value).toBe(21);
    });

    it('вставка из банковского приложения: пробелы и знак валюты', () => {
        expect(parse('1 234,56 ₽').value).toBe(1234.56);
        expect(parse('1 234,56 ₽').value).toBe(1234.56);   // неразрывные пробелы
    });

    it('пробел — разделитель тысяч, а не разделитель чисел', () => {
        // Осознанный компромисс: в русском формате «450 890» это 450890, и на этом
        // же держится разбор вставки «1 234,56 ₽». Цена — «450 890» нельзя понять
        // как два чека; для двух чеков пользователь ставит плюс.
        expect(parse('450 890').value).toBe(450890);
        expect(parse('450 890').isExpression).toBe(false);
    });

    it('умножение пишут четырьмя разными символами', () => {
        for (const sym of ['*', 'x', '×', 'х']) {   // последняя — кириллическая
            expect(parse(`3${sym}250`).value).toBe(750);
        }
        expect(parse('3 х 250').value).toBe(750);
    });
});

describe('amountExpression: ошибки', () => {
    it('пусто', () => {
        expect(parse('').error).toBe('empty');
        expect(parse('   ').error).toBe('empty');
    });

    it('битый ввод не превращается молча в число', () => {
        // parseFloat('450+abc') вернул бы 450 — ровно та тихая порча, которой избегаем
        expect(parse('450+').error).toBe('syntax');
        expect(parse('+450').error).toBe('syntax');
        expect(parse('450++890').error).toBe('syntax');
        expect(parse('abc').error).toBe('syntax');
        expect(parse('450+abc').error).toBe('syntax');
    });

    it('ноль и отрицательный итог отклоняются до отправки', () => {
        // Бэк ответил бы 400 (@Positive на factAmount) уже после сабмита
        expect(parse('100-500').error).toBe('nonPositive');
        expect(parse('100-100').error).toBe('nonPositive');
        expect(parse('0').error).toBe('nonPositive');
    });

    it('ведущий минус разбирается, но как отрицательный итог', () => {
        expect(parse('-100').error).toBe('nonPositive');
        expect(parse('-100+500').value).toBe(400);
    });
});
