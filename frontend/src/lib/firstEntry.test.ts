import { describe, expect, it } from 'vitest';
import { balanceAnswer, firstPayDate, incomeAnswer, incomeDto, needsFirstEntry, paymentAnswer, paymentDto } from './firstEntry';

const cats = [
    { id: 'credit', name: 'Кредит', type: 'EXPENSE' as const, isSystem: true },
    { id: 'home', name: 'Жильё и быт', type: 'EXPENSE' as const, isSystem: false },
    { id: 'tel', name: 'Связь и подписки', type: 'EXPENSE' as const, isSystem: false },
    { id: 'must', name: 'Обязательные платежи', type: 'EXPENSE' as const, isSystem: false },
    { id: 'side', name: 'Подработка', type: 'INCOME' as const, isSystem: false },
    { id: 'pay', name: 'Зарплата', type: 'INCOME' as const, isSystem: false },
];

describe('needsFirstEntry — когда открывается первый вход (спека среза 2, п. 11)', () => {
    it('ни сверки остатка, ни строк впереди — первый вход', () => {
        expect(needsFirstEntry({ checkpointDate: null, upcoming: [] })).toBe(true);
    });

    it('есть сверка — нет', () => {
        expect(needsFirstEntry({ checkpointDate: '2026-11-17', upcoming: [] })).toBe(false);
    });

    it('есть строки впереди — нет', () => {
        expect(needsFirstEntry({ checkpointDate: null, upcoming: [{} as never] })).toBe(false);
    });
});

describe('incomeDto — вопрос 2: доход в «Зарплате», бронь', () => {
    it('каждый месяц — повтор тем же числом', () => {
        expect(incomeDto(cats, { date: '2026-11-25', amount: 80_000, monthly: true })).toEqual({
            date: '2026-11-25', categoryId: 'pay', type: 'INCOME', plannedAmount: 80_000, priority: 'HIGH',
            recurring: { frequency: 'MONTHLY', dayOfMonth: 25, startDate: '2026-11-25' },
        });
    });

    it('не каждый месяц — без повтора', () => {
        expect(incomeDto(cats, { date: '2026-11-25', amount: 80_000, monthly: false }).recurring).toBeNull();
    });

    it('«Зарплаты» нет — первая своя доходная', () => {
        expect(incomeDto(cats.filter(c => c.id !== 'pay'), { date: '2026-11-25', amount: 1, monthly: false }).categoryId).toBe('side');
    });
});

describe('firstPayDate — ближайшее такое число начиная с сегодня', () => {
    it('число ещё впереди в этом месяце', () => {
        expect(firstPayDate(25, '2026-11-17')).toBe('2026-11-25');
    });

    it('сегодня — сегодня', () => {
        expect(firstPayDate(17, '2026-11-17')).toBe('2026-11-17');
    });

    it('число прошло — в следующем месяце', () => {
        expect(firstPayDate(12, '2026-11-17')).toBe('2026-12-12');
    });

    it('31-е в коротком месяце — последний день, как у генератора повторов', () => {
        expect(firstPayDate(31, '2026-11-17')).toBe('2026-11-30');
        expect(firstPayDate(31, '2027-02-03')).toBe('2027-02-28');
    });

    it('декабрь — в январь следующего года', () => {
        expect(firstPayDate(5, '2026-12-20')).toBe('2027-01-05');
    });

    it('прошло и в следующем месяце короче — его последний день', () => {
        expect(firstPayDate(31, '2027-01-31')).toBe('2027-01-31');
        expect(firstPayDate(30, '2027-01-31')).toBe('2027-02-28');
    });
});

describe('paymentDto — вопрос 3: строка платежа — бронь с повтором каждый месяц', () => {
    it('дата — ближайшее число, повтор тем же числом, имя — в описании', () => {
        expect(paymentDto(cats, { name: 'Аренда', day: 12, amount: 25_000 }, '2026-11-17')).toEqual({
            date: '2026-12-12', categoryId: 'home', type: 'EXPENSE', plannedAmount: 25_000, priority: 'HIGH',
            description: 'Аренда',
            recurring: { frequency: 'MONTHLY', dayOfMonth: 12, startDate: '2026-12-12' },
        });
    });

    it.each([
        ['Аренда', 'home'], ['аренда квартиры', 'home'], ['Ипотека', 'home'],
        ['Связь', 'tel'], ['Мобильная связь', 'tel'], ['Интернет', 'tel'],
        ['Кредит', 'must'], ['Детский сад', 'must'], ['', 'must'],
    ])('категория по имени: «%s» → %s', (name, id) => {
        expect(paymentDto(cats, { name, day: 20, amount: 1 }, '2026-11-17').categoryId).toBe(id);
    });

    it('без имени — без описания', () => {
        expect(paymentDto(cats, { name: '  ', day: 20, amount: 1 }, '2026-11-17').description).toBeUndefined();
    });

    it('31-е в ноябре: первая дата — 30-е, а повтор — 31-го', () => {
        const dto = paymentDto(cats, { name: 'Аренда', day: 31, amount: 1 }, '2026-11-17');
        expect(dto.date).toBe('2026-11-30');
        expect(dto.recurring).toMatchObject({ dayOfMonth: 31, startDate: '2026-11-30' });
    });

    it('своей категории нет совсем — системная не берётся, отказ', () => {
        expect(() => paymentDto(cats.filter(c => c.isSystem || c.type === 'INCOME'), { name: 'Кредит', day: 20, amount: 1 }, '2026-11-17'))
            .toThrow();
    });

    it('категории по имени нет — «Обязательные платежи»', () => {
        expect(paymentDto(cats.filter(c => c.id !== 'home'), { name: 'Аренда', day: 20, amount: 1 }, '2026-11-17').categoryId).toBe('must');
    });
});

describe('ответы — когда шаг можно пройти', () => {
    it('вопрос 1: сумма или выражение; ноль — тоже ответ; пусто и битое — нет', () => {
        expect(balanceAnswer('48 800')).toBe(48_800);
        expect(balanceAnswer('40000+8800')).toBe(48_800);
        expect(balanceAnswer('0')).toBe(0);
        expect(balanceAnswer('')).toBeNull();
        expect(balanceAnswer('abc')).toBeNull();
        expect(balanceAnswer('-5')).toBeNull();
    });

    it('вопрос 2: дата не раньше сегодня и сумма больше нуля', () => {
        const raw = { date: '2026-11-25', amount: '80 000', monthly: true };
        expect(incomeAnswer(raw, '2026-11-17')).toEqual({ date: '2026-11-25', amount: 80_000, monthly: true });
        expect(incomeAnswer({ ...raw, date: '2026-11-17' }, '2026-11-17')).not.toBeNull();
        expect(incomeAnswer({ ...raw, date: '2026-11-16' }, '2026-11-17')).toBeNull();
        expect(incomeAnswer({ ...raw, date: '' }, '2026-11-17')).toBeNull();
        expect(incomeAnswer({ ...raw, amount: '0' }, '2026-11-17')).toBeNull();
    });

    it('вопрос 3: строка пишется, когда в ней есть число и сумма; имя — не обязательно', () => {
        expect(paymentAnswer({ name: 'Аренда', day: '12', amount: '20000+5000' })).toEqual({ name: 'Аренда', day: 12, amount: 25_000 });
        expect(paymentAnswer({ name: '', day: '12', amount: '800' })).toEqual({ name: '', day: 12, amount: 800 });
        expect(paymentAnswer({ name: 'Аренда', day: '', amount: '25000' })).toBeNull();
        expect(paymentAnswer({ name: 'Аренда', day: '12', amount: '' })).toBeNull();
        expect(paymentAnswer({ name: 'Аренда', day: '12', amount: '0' })).toBeNull();
    });
});
