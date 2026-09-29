import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import {
    composeTimeline, scaleDelta, riskZones, calcPMT, canConfirmConversion, fixPatch, defaultActiveMap,
    effectiveDelta, conversionChoice, recurringPaymentsFor, dismissedNotice, trialParams, dismissQuestion,
    recomputeBasis, effectiveContribution,
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

describe('путь назад у отложенной (ANO-107)', () => {
    // «Отложить» было дорогой в один конец: строка пропадала отовсюду, раздел «Отклонено» в «Что с
    // капиталом» был нарисован, но сервер не отдавал ему данных. Теперь отложенная приходит с пустой
    // дельтой, раздел «Отложено» рисует её короткой карточкой, а примерка говорит, где её искать.
    const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

    /** Текст инициализатора `const имя = …` — тело обработчика. */
    const handlerBody = (path: string, name: string): string => {
        const src = read(path);
        const sf = ts.createSourceFile(path, src, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
        let body: string | null = null;
        const visit = (node: ts.Node): void => {
            if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name)
                && node.name.text === name && node.initializer) {
                body = node.initializer.getText(sf);
            }
            ts.forEachChild(node, visit);
        };
        visit(sf);
        if (body === null) throw new Error(`${path}: нет обработчика ${name}`);
        return body;
    };

    it('заметка после «Отложить» говорит, где вернуть', () => {
        expect(dismissedNotice('Велосипед'))
            .toBe('Отложено: «Велосипед». Вернуть можно ниже — в «Что с капиталом», раздел «Отложено».');
    });

    it('«Отложить» в примерке ставит эту заметку', () => {
        // Сторож по исходнику: компонентных тестов нет, а без заметки строка просто пропадает.
        expect(handlerBody('../../pages/Wishlist.tsx', 'dismiss'))
            .toMatch(/setNotice\(dismissedNotice\(item\.name\)\)/);
    });

    it('раздел «Отложено» рисует отложенные короткой карточкой', () => {
        expect(read('./WishlistItemList.tsx'))
            .toMatch(/<CollapsibleSection title="Отложено"[^>]*>\s*\{dismissed\.map\(item => <DismissedItemCard/);
    });

    it('у короткой карточки одно действие — вернуть; ни галочки, ни ползунков, ни фиксации', () => {
        // Отложенная в расчёт не входит: трогать в ней нечего.
        const card = read('./DismissedItemCard.tsx');
        expect(card).not.toMatch(/type="checkbox"|type="range"|onFix|onToggleActive/);
        expect(card).toMatch(/onStatusChange\('OPEN'\)/);
        expect(card).toContain('Вернуть в обсуждение');
    });

    it('на карточке обсуждаемой и зафиксированной — «Отложить»', () => {
        expect(read('./WishlistItemCard.tsx')).toMatch(/onStatusChange\('DISMISSED'\)[\s\S]{0,300}Отложить/);
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

    it('пересчитанная важнее исходной; сумма, не менявшаяся после пересчёта, её не трогает', () => {
        // Сервер считает дельту на сумме запроса: 200 000 уже внутри неё.
        expect(effectiveDelta(item(), { amount: 200000, delta: [month(-3000)], deltaAmount: 200000 }))
            .toEqual([month(-3000)]);
    });

    it('сумма, подкрученная после пересчёта, масштабирует пересчитанную — от суммы пересчёта (ANO-105)', () => {
        // Замер 29.09: после ставки кредита ползунок суммы график больше не двигал. С пересчётом по
        // дате то же случилось бы у любой хотелки после первого движения «Когда».
        expect(effectiveDelta(item(), { amount: 300000, delta: [month(-3000)], deltaAmount: 200000 })[0].accountDelta)
            .toBe(-4500);
    });

    it('подкрученная сумма масштабирует исходную', () => {
        expect(effectiveDelta(item(), { amount: 200000 })[0].accountDelta).toBe(-20000);
    });

    it('подкрученный ноль — не основа пересчёта: иначе поднятая после сумма не сдвинет график (ревью Codex #124)', () => {
        // От нуля дельту не масштабировать: scaleDelta оставляет её прежней при любой новой сумме.
        expect(recomputeBasis(0, 544544)).toBe(544544);
        expect(recomputeBasis(272272, 544544)).toBe(272272);
        // Пересчёт на сумме хотелки, подкрученная — ноль, потом 50 000: график идёт за суммой.
        const recomputed = { delta: [month(-6000)], deltaAmount: recomputeBasis(0, 100000) };
        expect(effectiveDelta(item(), { ...recomputed, amount: 0 })[0].accountDelta).toBeCloseTo(0);
        expect(effectiveDelta(item(), { ...recomputed, amount: 50000 })[0].accountDelta).toBe(-3000);
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

// ANO-162: «Что с капиталом» писал примерку полной перезаписью (PUT /funds, PUT /events) тем, что
// знал. Замер 28.09: копилка на счёте «Эталон» отвязывалась — накоплено 5 000 → 0; хотелка без
// описания получала имя категории и теряла исходный текст; у хотелки без срока подкрученная сумма
// не писалась вовсе. Теперь блок пишет только параметры примерки — отдельной записью.
describe('«Что с капиталом» пишет только параметры примерки (ANO-162)', () => {
    const item = (kind: WishlistItem['kind']) => ({ kind });

    it('хотелка — сумма и срок; срок из диалога фиксации главнее подкрученного', () => {
        expect(trialParams(item('WISHLIST'), { amount: 57000, targetDate: '2026-12-01' }))
            .toEqual({ kind: 'event', body: { plannedAmount: 57000, date: '2026-12-01' } });
        expect(trialParams(item('WISHLIST'), { amount: 57000, targetDate: '2026-12-01' }, '2027-01-15'))
            .toEqual({ kind: 'event', body: { plannedAmount: 57000, date: '2027-01-15' } });
    });

    it('хотелка без срока — только сумма: срок не выдумывается (ANO-29), а сумма больше не теряется', () => {
        expect(trialParams(item('WISHLIST'), { amount: 618800 }))
            .toEqual({ kind: 'event', body: { plannedAmount: 618800 } });
    });

    it('копилка — цель и срок', () => {
        expect(trialParams(item('SAVINGS'), { amount: 80000, targetDate: '2026-12-14' }))
            .toEqual({ kind: 'fund', body: { targetAmount: 80000, targetDate: '2026-12-14' } });
    });

    it('кредит — ещё ставка и срок кредита из примерки', () => {
        expect(trialParams(item('CREDIT'), { amount: 900000, targetDate: '2027-09-01', rate: 11.5, termMonths: 36 }))
            .toEqual({ kind: 'fund', body: { targetAmount: 900000, targetDate: '2027-09-01', creditRate: 11.5, creditTermMonths: 36 } });
    });

    it('ничего сверх параметров: ни имени, ни описания, ни счёта, ни вида', () => {
        for (const kind of ['WISHLIST', 'SAVINGS', 'CREDIT'] as const) {
            const { body } = trialParams(item(kind), { amount: 1, targetDate: '2026-12-01', rate: 5, termMonths: 12 });
            for (const key of Object.keys(body)) {
                expect(['plannedAmount', 'date', 'targetAmount', 'targetDate', 'creditRate', 'creditTermMonths'], `${kind}: ${key}`)
                    .toContain(key);
            }
        }
    });

    it('блок пишет примерку только через это правило и не зовёт полную перезапись', () => {
        // Сторож по исходнику: компонентных тестов нет, а вернуть persistTrial к updateFund —
        // правка в одну строку, и правило выше осталось бы зелёным.
        const block = readFileSync(new URL('../sandbox/CapitalWhatIf.tsx', import.meta.url), 'utf8');
        expect(block).toMatch(/trialParams\(item, fixPatch\(item, overrideMap\[item\.id\]\), explicitDate\)/);
        expect(block).toMatch(/setEventWishlistParams\(item\.id, write\.body\)/);
        expect(block).toMatch(/setFundWishlistParams\(item\.id, write\.body\)/);
        expect(block).not.toMatch(/\bupdateEvent\b|\bupdateFund\b/);
    });
});

// ANO-210: «Отложить» у хотелки, из которой создан план или копилка, молча оставлял созданное в
// расчёте. Замер 28.09: план на 10.10 на 1 111 ₽ остался в журнале, свободно −198 323. Спека модуля
// 29.05 — «FIXED → DISMISSED с подтверждением, артефакт остаётся или удаляется по явному выбору».
describe('«Отложить» у хотелки с созданным спрашивает (ANO-210)', () => {
    const item = (status: WishlistItem['status'], convertedTo: WishlistItem['convertedTo']) => ({ status, convertedTo });

    it('зафиксированная в план — строка про план и галочка «Удалить и созданный план»', () => {
        expect(dismissQuestion(item('FIXED', { kind: 'EVENT', id: 'p' }))).toEqual({
            stays: 'Созданный план останется в расчёте.', also: 'Удалить и созданный план',
        });
    });

    it('зафиксированная в копилку — про копилку', () => {
        expect(dismissQuestion(item('FIXED', { kind: 'FUND', id: 'f' }))).toEqual({
            stays: 'Созданная копилка останется в расчёте.', also: 'Удалить и созданную копилку',
        });
    });

    it('без созданного вопроса нет — ни у зафиксированной без конверсии, ни у обсуждаемой', () => {
        expect(dismissQuestion(item('FIXED', null))).toBeNull();
        expect(dismissQuestion(item('OPEN', null))).toBeNull();
    });

    it('вернувшаяся в обсуждение с созданным — тот же вопрос: план остался бы в расчёте молча', () => {
        expect(dismissQuestion(item('OPEN', { kind: 'EVENT', id: 'p' }))).not.toBeNull();
    });
});

describe('ползунок «Когда» двигает график (ANO-105, сторож по исходнику)', () => {
    // Компонентных тестов нет, а снять пересчёт с даты — правка в одну строку. Замер 29.09:
    // «Когда» октябрь → апрель график не двигал, запроса пересчёта не было.
    const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

    it('карточка: подкрученная дата и пауза — пересчёт с суммой, ставкой и сроком на момент срабатывания', () => {
        // Ревью Codex #124: таймер, взявший ставку в момент постановки, после ставки, изменённой за
        // паузу, отправлял старую — его ответ последний и перебивал новый, а примерка получала
        // прежнюю ставку, которую записала бы фиксация.
        const src = read('./WishlistItemCard.tsx');
        expect(src).toMatch(/recomputeNow\.current = \(\) => onParamsRecompute\(buildRecomputeReq\(\)\);/);
        expect(src).toMatch(/useEffect\(\(\) => \{\s*if \(dateOverride == null\) return;\s*pendingRecompute\.current = true;\s*const t = setTimeout\(\(\) => \{ pendingRecompute\.current = false; recomputeNow\.current\(\); \}, RECOMPUTE_PAUSE_MS\);\s*return \(\) => clearTimeout\(t\);\s*\}, \[dateOverride\]\);/);
    });

    it('карточка просит пересчёт на сумме, от которой можно масштабировать (ревью Codex #124)', () => {
        expect(read('./WishlistItemCard.tsx')).toMatch(/amount: recomputeBasis\(amount, item\.amount\),/);
    });

    it('свёрнутая до паузы карточка отправляет отложенный пересчёт сразу (ревью Codex #124, второй круг)', () => {
        // Раздел «Зафиксировано» рисует карточки только открытым: свернули в пределах паузы —
        // карточка ушла вместе с таймером, а график остался на прежней дате до нового открытия.
        const src = read('./WishlistItemCard.tsx');
        expect(src).toMatch(/pendingRecompute\.current = true;\s*const t = setTimeout\(\(\) => \{ pendingRecompute\.current = false; recomputeNow\.current\(\); \}, RECOMPUTE_PAUSE_MS\);/);
        expect(src).toMatch(/useEffect\(\(\) => \(\) => \{ if \(pendingRecompute\.current\) recomputeNow\.current\(\); \}, \[\]\);/);
    });

    it('хук кладёт рядом с пересчитанной дельтой сумму, на которой её считали, и взнос копилки', () => {
        expect(read('./useWishlistSimulation.ts'))
            .toMatch(/\(id: string, delta: MonthDelta\[\], amount: number, monthlyContribution\?: number \| null\) => \{\s*setOverrideMap\(prev => \(\{ \.\.\.prev, \[id\]: \{ \.\.\.prev\[id\], delta, deltaAmount: amount, monthlyContribution \} \}\)\);/);
    });

    it('«Что с капиталом» передаёт сумму запроса и взнос из ответа и отбрасывает опоздавший ответ', () => {
        const src = read('../sandbox/CapitalWhatIf.tsx');
        expect(src).toMatch(/const n = recomputes\.start\(item\.id\);/);
        expect(src).toMatch(/if \(recomputes\.isLatest\(item\.id, n\)\) \{\s*actions\.applyRecomputedDelta\(item\.id, resp\.delta, req\.amount, resp\.monthlyContribution\);\s*\}/);
    });

    it('строка «Взнос ≈» берёт взнос примерки, а не загруженный (ревью Codex #124, третий круг)', () => {
        // Сдвинули «Когда» у копилки — график шёл по новому графику взносов, а строка под карточкой
        // оставалась с прежней даты; за суммой она не следовала и до ANO-105.
        expect(read('./WishlistItemList.tsx'))
            .toMatch(/contribution=\{effectiveContribution\(item, p\.overrideMap\[item\.id\]\)\}/);
        const card = read('./WishlistItemCard.tsx');
        expect(card).toMatch(/Взнос ≈ \{fmtRub\(Math\.round\(contribution\)\)\}\/мес/);
        expect(card).not.toMatch(/item\.monthlyContribution/);
    });
});

describe('взнос копилки в примерке (ANO-105, ревью Codex #124)', () => {
    const month = (accountDelta: number): MonthDelta =>
        ({ monthIndex: 1, accountDelta, capitalDelta: accountDelta, fundDelta: null, liabilityDelta: null });
    const savings = (over: Partial<WishlistItem> = {}): WishlistItem => ({
        id: 's1', kind: 'SAVINGS', name: 'Отпуск', amount: 120000, targetDate: '2027-06-01', status: 'OPEN',
        convertedTo: null, delta: [month(-10000)], monthlyContribution: 10000, ...over,
    });

    it('ничего не подкручено — загруженный взнос', () => {
        expect(effectiveContribution(savings(), undefined)).toBe(10000);
    });

    it('подкрученная сумма масштабирует загруженный взнос: сервер делит сумму на число месяцев', () => {
        expect(effectiveContribution(savings(), { amount: 60000 })).toBe(5000);
    });

    it('после пересчёта — взнос из ответа; сумма, подкрученная после, масштабирует от суммы пересчёта', () => {
        // Дату сдвинули с 12 месяцев на 6: взнос 20 000 на сумме 120 000.
        const recomputed = { delta: [month(-20000)], deltaAmount: 120000, monthlyContribution: 20000 };
        expect(effectiveContribution(savings(), recomputed)).toBe(20000);
        expect(effectiveContribution(savings(), { ...recomputed, amount: 60000 })).toBe(10000);
    });

    it('у сконвертированной — загруженный: примерка её не трогает, как и график', () => {
        // Числа разведены: пересчитанный 30 000 от 120 000 к 60 000 — это 15 000, загруженный от суммы
        // хотелки к 60 000 — 5 000, нетронутый — 10 000. Мутация MC15 нашла, что прежние числа совпадали.
        const converted = savings({ convertedTo: { kind: 'FUND', id: 'f1' } });
        expect(effectiveContribution(converted, { amount: 60000, deltaAmount: 120000, monthlyContribution: 30000 }))
            .toBe(10000);
    });

    it('взноса нет ни в хотелке, ни в пересчёте — строки нет', () => {
        expect(effectiveContribution(savings({ monthlyContribution: null }), { amount: 60000 })).toBeNull();
    });
});
