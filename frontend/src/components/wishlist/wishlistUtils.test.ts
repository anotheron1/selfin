import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import {
    composeTimeline, scaleDelta, riskZones, calcPMT, canConfirmConversion, fixPatch, defaultActiveMap,
    effectiveDelta, conversionChoice, recurringPaymentsFor,
} from './wishlistUtils';
import type { MonthDelta, WishlistItem } from '../../types/api';

describe('calcPMT', () => {
    it('computes the annuity payment for a positive rate', () => {
        // 2,000,000 @ 16.5%/yr over 60 months ≈ 49,180/mo
        const pmt = calcPMT(2000000, 16.5, 60);
        expect(pmt).toBeGreaterThan(49000);
        expect(pmt).toBeLessThan(49400);
    });

    it('falls back to straight-line division at zero rate', () => {
        expect(calcPMT(120000, 0, 12)).toBe(10000);
    });
});

const baseline = [
    { account: 100000, capital: 500000 },
    { account: 90000, capital: 500000 },
    { account: 80000, capital: 500000 },
]; // index 0 = current+1

describe('composeTimeline', () => {
    it('returns baseline when no active items', () => {
        const out = composeTimeline(baseline, []);
        expect(out).toEqual(baseline);
    });

    it('applies a single delta cumulatively from its month', () => {
        const delta: MonthDelta[] = [{ monthIndex: 1, accountDelta: -50000, capitalDelta: -50000 }];
        const out = composeTimeline(baseline, [{ active: true, delta }]);
        expect(out[0].account).toBe(100000);          // before
        expect(out[1].account).toBe(40000);           // 90000 - 50000
        expect(out[2].account).toBe(30000);           // 80000 - 50000 (cumulative)
    });

    it('excludes disabled items', () => {
        const delta: MonthDelta[] = [{ monthIndex: 0, accountDelta: -50000, capitalDelta: 0 }];
        const out = composeTimeline(baseline, [{ active: false, delta }]);
        expect(out).toEqual(baseline);
    });

    it('sums multiple items in the same month', () => {
        const a: MonthDelta[] = [{ monthIndex: 0, accountDelta: -10000, capitalDelta: 0 }];
        const b: MonthDelta[] = [{ monthIndex: 0, accountDelta: -20000, capitalDelta: 0 }];
        const out = composeTimeline(baseline, [{ active: true, delta: a }, { active: true, delta: b }]);
        expect(out[0].account).toBe(70000);   // 100000 - 30000
    });
});

describe('scaleDelta', () => {
    it('scales linearly by amount ratio', () => {
        const delta: MonthDelta[] = [{ monthIndex: 0, accountDelta: -100000, capitalDelta: -100000 }];
        const scaled = scaleDelta(delta, 100000, 150000);  // baseAmount=100k, override=150k
        expect(scaled[0].accountDelta).toBe(-150000);
        expect(scaled[0].capitalDelta).toBe(-150000);
    });
});

describe('riskZones', () => {
    const thresholds = { capitalThresholdRub: 1000000, cashBufferMonths: 1 };
    const monthlyExpenses = 95000;

    it('account negative is red', () => {
        const zones = riskZones([{ account: -1, capital: 2000000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('red');
    });
    it('account below buffer is yellow', () => {
        const zones = riskZones([{ account: 50000, capital: 2000000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('yellow');
    });
    it('capital below threshold is red', () => {
        const zones = riskZones([{ account: 500000, capital: 900000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('red');
    });
    it('capital near threshold is yellow', () => {
        const zones = riskZones([{ account: 500000, capital: 1050000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('yellow');
    });
    it('null capital threshold disables capital criterion', () => {
        const zones = riskZones([{ account: 500000, capital: 1 }],
            { capitalThresholdRub: null, cashBufferMonths: 1 }, monthlyExpenses);
        expect(zones[0]).toBe('green');
    });
    it('combined risk takes the worse of the two', () => {
        const zones = riskZones([{ account: 50000, capital: 900000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('red');   // account=yellow, capital=red → red
    });
});

describe('canConfirmConversion (ANO-138)', () => {
    const TODAY = '2026-09-15';

    it('плановое событие без срока подтвердить нельзя', () => {
        expect(canConfirmConversion('PLAN_EVENT', '', TODAY)).toBe(false);
    });

    it('плановое событие со сроком в будущем подтвердить можно', () => {
        expect(canConfirmConversion('PLAN_EVENT', '2026-12-01', TODAY)).toBe(true);
    });

    it('прошедший срок подтвердить нельзя: сервер такую конверсию отвергает', () => {
        // Найдено на стенде: у хотелки срок 01.09, поле предзаполнялось им,
        // кнопка была активна, а /convert отвечал 400 — и ошибку глотал .catch(refetch).
        expect(canConfirmConversion('PLAN_EVENT', '2026-09-01', TODAY)).toBe(false);
    });

    it('сегодняшний срок подтвердить нельзя: сегодняшний план не резервируется', () => {
        expect(canConfirmConversion('PLAN_EVENT', TODAY, TODAY)).toBe(false);
    });

    it('завтрашний — можно: граница ровно та же, что у requireFutureDate', () => {
        expect(canConfirmConversion('PLAN_EVENT', '2026-09-16', TODAY)).toBe(true);
    });

    it('копилке срок в этом диалоге не нужен: фонд без targetDate — законное состояние', () => {
        expect(canConfirmConversion('FUND', '', TODAY)).toBe(true);
    });

    it('кредиту срок в этом диалоге не нужен', () => {
        expect(canConfirmConversion('FUND_WITH_CREDIT', '', TODAY)).toBe(true);
    });
});

describe('график платежей — только у «Кредита» (ANO-104)', () => {
    // Галочка видна только у «Кредита», а уходила при любой цели: включена по умолчанию, и каждая
    // фиксация в копилку или план просила правило платежей, о котором человек не знал. Сервер такую
    // просьбу теперь отвергает — экран обязан её не слать.
    it('«Кредит» с галочкой — график просится', () => {
        expect(recurringPaymentsFor('FUND_WITH_CREDIT', true)).toBe(true);
    });

    it('«Кредит» без галочки — нет', () => {
        expect(recurringPaymentsFor('FUND_WITH_CREDIT', false)).toBe(false);
    });

    it.each(['FUND', 'PLAN_EVENT'] as const)('%s — нет, хотя невидимая галочка включена', (target) => {
        expect(recurringPaymentsFor(target, true)).toBe(false);
    });

    const dialog = () => readFileSync(new URL('./FixWishlistDialog.tsx', import.meta.url), 'utf8');

    it('диалог шлёт галочку только через это правило', () => {
        // Сторож по исходнику: компонентных тестов нет, а вернуть в onConfirm сырую галочку —
        // правка в одну строку, и функция выше осталась бы зелёной.
        expect(dialog()).toMatch(/onConfirm\(target, recurringPaymentsFor\(target, createRecurring\)/);
        expect(dialog()).not.toMatch(/onConfirm\(target, createRecurring\b/);
    });

    it('галочка называется «график платежей», как пункт «Кредит», и без английского слова', () => {
        // Правило 13: одна вещь — одно имя; «график платежей» говорят банки.
        expect(dialog()).toContain('Создать график платежей');
        expect(dialog()).toContain("'Кредит (копилка + график платежей)'");
        expect(dialog()).not.toMatch(/\(recurring\)/);
    });
});

describe('fixPatch (ANO-139)', () => {
    // Примерка перестала писать по жесту, и запись переехала на «Зафиксировать».
    // Значит в неё обязано уйти ровно то, что человек видит на экране: иначе он
    // подкрутил до 120 000, нажал «зафиксировать» и получил план на записанные 50 000.
    const wish = (over: Partial<WishlistItem> = {}): WishlistItem => ({
        id: 'i1', kind: 'WISHLIST', name: 'Велокресло', amount: 50000,
        targetDate: '2026-12-01', status: 'OPEN', convertedTo: null, delta: [],
        ...over,
    });

    it('без примерки уходит записанное', () => {
        expect(fixPatch(wish(), undefined)).toEqual({ amount: 50000, targetDate: '2026-12-01' });
    });

    it('подкрученная сумма важнее записанной', () => {
        expect(fixPatch(wish(), { amount: 120000 }).amount).toBe(120000);
    });

    it('подкрученный срок важнее записанного', () => {
        expect(fixPatch(wish(), { targetDate: '2027-03-01' }).targetDate).toBe('2027-03-01');
    });

    it('ноль — законная сумма, а не «ничего не подкручивали»', () => {
        // Через || вместо ?? нуль схлопнулся бы в записанные 50 000.
        expect(fixPatch(wish(), { amount: 0 }).amount).toBe(0);
    });

    it('у хотелки без срока даты в патче нет: выдумывать дату нельзя (ANO-29)', () => {
        // Ползунок показывает подставной ближайший месяц — он не значит «человек назначил срок».
        expect(fixPatch(wish({ targetDate: null }), undefined).targetDate).toBeUndefined();
    });

    it('у хотелки без срока подкрученный срок уходит: его задали руками', () => {
        expect(fixPatch(wish({ targetDate: null }), { targetDate: '2027-01-01' }).targetDate)
            .toBe('2027-01-01');
    });

    it('кредитные параметры: подкрученные важнее записанных, записанные важнее пустоты', () => {
        const credit = wish({ kind: 'CREDIT', rate: 16.5, termMonths: 60 });
        expect(fixPatch(credit, { rate: 21 })).toEqual({
            amount: 50000, targetDate: '2026-12-01', rate: 21, termMonths: 60,
        });
    });

    it('нулевая ставка не подменяется записанной', () => {
        const credit = wish({ kind: 'CREDIT', rate: 16.5, termMonths: 60 });
        expect(fixPatch(credit, { rate: 0 }).rate).toBe(0);
    });
});

describe('что включено, когда открываешь «Что с капиталом» (ANO-142)', () => {
    // Решение владельца 25.09 (вариант Б): блок открывается той же картиной, что Стратегия
    // и кармашек. Обсуждаемая хотелка — не трата, пока её не примеришь галочкой.
    const item = (id: string, status: WishlistItem['status']): WishlistItem => ({
        id, kind: 'WISHLIST', name: id, amount: 50000,
        targetDate: '2026-12-01', status, convertedTo: null, delta: [],
    });

    it('зафиксированная включена, обсуждаемая и отклонённая — нет', () => {
        expect(defaultActiveMap([item('f', 'FIXED'), item('o', 'OPEN'), item('d', 'DISMISSED')]))
            .toEqual({ f: true, o: false, d: false });
    });

    it('хук примерки берёт включённое отсюда, а не решает сам', () => {
        // Сторож по исходнику: компонентных тестов нет, а вернуть «OPEN || FIXED» в хук —
        // правка в одну строку, и функция выше осталась бы зелёной.
        const src = readFileSync(new URL('./useWishlistSimulation.ts', import.meta.url), 'utf8');
        expect(src).toMatch(/defaultActiveMap\(/);
        expect(src).not.toMatch(/status === 'OPEN'/);
    });
});

describe('дельта строки примерки (ANO-142)', () => {
    const month = (accountDelta: number): MonthDelta =>
        ({ monthIndex: 1, accountDelta, capitalDelta: accountDelta, fundDelta: null, liabilityDelta: null });
    const item = (over: Partial<WishlistItem> = {}): WishlistItem => ({
        id: 'i1', kind: 'CREDIT', name: 'Машина', amount: 100000,
        targetDate: '2026-12-01', status: 'FIXED', convertedTo: null, delta: [month(-10000)],
        ...over,
    });
    const converted = item({ convertedTo: { kind: 'FUND', id: 'f1' }, delta: [] });

    it('подкрученного нет — исходная дельта', () => {
        expect(effectiveDelta(item(), undefined)).toEqual([month(-10000)]);
    });

    it('пересчитанная важнее масштабированной суммой', () => {
        expect(effectiveDelta(item(), { amount: 200000, delta: [month(-3000)] })).toEqual([month(-3000)]);
    });

    it('подкрученная сумма масштабирует исходную', () => {
        expect(effectiveDelta(item(), { amount: 200000 })[0].accountDelta).toBe(-20000);
    });

    it('у сконвертированной дельты нет, что бы ни подкрутили: деньги несёт артефакт', () => {
        // Ревью Codex #73: ставка или срок на карточке сконвертированного кредита запрашивали
        // пересчёт, он возвращал полную дельту кредита, и покупка ложилась второй раз.
        expect(effectiveDelta(converted, { delta: [month(-10000)] })).toEqual([]);
        expect(effectiveDelta(converted, { amount: 200000 })).toEqual([]);
    });

    it('хук и «Что с капиталом» берут дельту отсюда, а не считают сами', () => {
        // Сторож по исходнику: копий было две, и правило про сконвертированную жило бы в одной.
        const hook = readFileSync(new URL('./useWishlistSimulation.ts', import.meta.url), 'utf8');
        const block = readFileSync(new URL('../sandbox/CapitalWhatIf.tsx', import.meta.url), 'utf8');
        for (const src of [hook, block]) {
            expect(src).toMatch(/effectiveDelta\(/);
            expect(src).not.toMatch(/function effectiveDelta/);
            expect(src).not.toMatch(/scaleDelta\(/);
        }
    });
});

describe('примерка не пишет по жесту (ANO-139)', () => {
    // Сторож по исходнику: компонентных тестов в проекте нет, а вернуть персист
    // на отпускание ползунка — правка в одну строку. Та же техника, что нашла
    // второе место записи даты в ANO-155.
    it('карточка примерки не содержит записи в базу', () => {
        const src = readFileSync(new URL('./WishlistItemCard.tsx', import.meta.url), 'utf8');
        expect(src).not.toMatch(/persist/i);
        expect(src).not.toMatch(/updateEvent|updateFund/);
    });
});

describe('во что превратить хотелку — только то, что примет сервер (ANO-141)', () => {
    // Сервер: WishlistConversionService. У хотелки-события ветки «Кредит» нет вовсе — 400
    // «Unsupported target for WISHLIST source». Копилке «Кредит» — только со ставкой и сроком
    // больше нуля — 400 «credit rate and positive term are required». Замер 28.09 — спека
    // 2026-09-28-wishlist-write-errors-design.md, пути 1 и 2.
    const WITHOUT_CREDIT = ['PLAN_EVENT', 'FUND'];
    const ALL = ['PLAN_EVENT', 'FUND', 'FUND_WITH_CREDIT'];

    it('хотелке-событию — без «Кредита», даже со ставкой и сроком', () => {
        expect(conversionChoice('WISHLIST', null, null).targets).toEqual(WITHOUT_CREDIT);
        expect(conversionChoice('WISHLIST', 12, 12).targets).toEqual(WITHOUT_CREDIT);
    });

    it('копилке без ставки и срока — без «Кредита»', () => {
        expect(conversionChoice('SAVINGS', null, null).targets).toEqual(WITHOUT_CREDIT);
        expect(conversionChoice('SAVINGS', undefined, undefined).targets).toEqual(WITHOUT_CREDIT);
    });

    it('кредиту со ставкой и сроком — «Кредит» есть', () => {
        expect(conversionChoice('CREDIT', 24, 12).targets).toEqual(ALL);
    });

    it('границы — ровно те, что примет запись копилки: 0,01–99,99 % и 1–360 месяцев', () => {
        // Запись примерки (PUT /funds, TargetFundCreateDto) идёт до конверсии — ревью Codex, #108.
        expect(conversionChoice('CREDIT', 0.01, 1).targets).toEqual(ALL);
        expect(conversionChoice('CREDIT', 99.99, 360).targets).toEqual(ALL);
    });

    it('ставка 0 — рассрочка: запись копилки её не примет, «Кредита» нет', () => {
        expect(conversionChoice('CREDIT', 0, 12).targets).toEqual(WITHOUT_CREDIT);
    });

    it('срок 0 или ставки нет — «Кредита» нет', () => {
        expect(conversionChoice('CREDIT', 24, 0).targets).toEqual(WITHOUT_CREDIT);
        expect(conversionChoice('CREDIT', null, 12).targets).toEqual(WITHOUT_CREDIT);
        expect(conversionChoice('CREDIT', 24, null).targets).toEqual(WITHOUT_CREDIT);
    });

    it('ставка или срок вне границ — не записать ничего: запись примерки идёт первой', () => {
        for (const [rate, term] of [[0, 12], [100, 12], [24, 0], [24, 361], [24, 1.5]]) {
            expect(conversionChoice('CREDIT', rate, term).savable, `${rate} % на ${term} мес.`).toBe(false);
        }
    });

    it('ставки и срока нет — запись пройдёт, просто без кредита', () => {
        expect(conversionChoice('SAVINGS', null, null).savable).toBe(true);
        expect(conversionChoice('CREDIT', null, null).savable).toBe(true);
        expect(conversionChoice('CREDIT', 24, 12).savable).toBe(true);
    });

    it('хотелке-событию ставка и срок не пишутся — записать можно всегда', () => {
        expect(conversionChoice('WISHLIST', 0, 0).savable).toBe(true);
    });

    it('не число — всё равно что нет: в JSON NaN уходит пустым', () => {
        expect(conversionChoice('CREDIT', Number.NaN, 12).targets).toEqual(WITHOUT_CREDIT);
        expect(conversionChoice('CREDIT', 24, Number.NaN).targets).toEqual(WITHOUT_CREDIT);
        // Пустое сервер запишет — запирать кнопки не за что.
        expect(conversionChoice('CREDIT', Number.NaN, 12).savable).toBe(true);
    });

    it('цель по умолчанию — прежняя по виду хотелки', () => {
        expect(conversionChoice('WISHLIST', null, null).initial).toBe('PLAN_EVENT');
        expect(conversionChoice('SAVINGS', null, null).initial).toBe('FUND');
        expect(conversionChoice('CREDIT', 24, 12).initial).toBe('FUND_WITH_CREDIT');
    });

    it('кредиту без ставки или срока — первая из доступных и строка «почему нет пункта»', () => {
        const choice = conversionChoice('CREDIT', 24, 0);
        expect(choice.initial).toBe('PLAN_EVENT');
        expect(choice.creditNeedsParams).toBe(true);
    });

    it('строки «почему нет пункта» нет там, где «Кредит» и не был намерением', () => {
        expect(conversionChoice('WISHLIST', null, null).creditNeedsParams).toBe(false);
        expect(conversionChoice('SAVINGS', null, null).creditNeedsParams).toBe(false);
        expect(conversionChoice('CREDIT', 24, 12).creditNeedsParams).toBe(false);
    });
});
