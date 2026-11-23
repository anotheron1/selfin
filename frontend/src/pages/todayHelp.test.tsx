import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { MovePlate } from '../components/today/GapPlate';
import { UsualSpendingForm } from '../components/today/UsualSpendingForm';
import { WhyPlate } from '../components/today/WhyPlate';
import { buildGapMode } from '../lib/gapMode';
import { HELP } from '../lib/help';
import { numberHint, PLUS_HINT, SLOPE_HINT } from '../lib/hints';
import { buildBreakHint } from '../lib/reanchor';
import { buildTodayTiles, cardsTileLabel } from '../lib/todayTiles';
import type { PocketResponse, UpcomingItem } from '../types/api';

/**
 * Справка «Как считаются свободные деньги» → подача на «Сегодня» (решение 8, ANO-189; спека среза 2, таблица раздела
 * «Справка → подача»): каждый из 18 абзацев встал туда, где возникает вопрос, или уже сказан самим экраном.
 * Абзац без места или место без абзаца — красный: таблица и справка расходятся.
 */

const src = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8');
const today = src('./Today.tsx');

type Line = PocketResponse['breakdown'][number];
const line = (type: string, amount: number, details: string[] = []): Line =>
    ({ type: type as Line['type'], label: 'подпись бэка', amount, details });
const item = (description: string, date: string, overdue = false): UpcomingItem => ({
    id: `e-${description}`, date, categoryName: 'Обязательные платежи', amount: 1_000, description,
    overdue, wishlist: false, priority: 'HIGH', type: 'EXPENSE',
});

/** Человек после `/start` (S2-P1): доход 25 нояб., аренда и связь до него; строки — каждая со своим пояснением. */
function fresh(over: Partial<PocketResponse> = {}): PocketResponse {
    return {
        pocket: 23_000, currentBalance: 48_800, buffer: 0, checkpointDate: '2026-11-17',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-11-25', label: '', fallback: false },
        minPoint: { date: '2026-11-24', balance: 23_000, drivenBy: null },
        minPointWithForecast: null, pocketWithForecast: null, planHasExpectations: false, pocketWithDeposits: 47_000,
        breakdown: [
            line('STARTING_BALANCE', 48_800), line('ENVELOPE_FUNDS', -1_000), line('OVERDUE_RESERVE', -1_000),
            line('PLANNED_EXPENSES', -2_000), line('SAVINGS_CONTRIBUTIONS', -5_000, ['Отпуск']),
            line('TRAJECTORY_MIN', 23_000), line('POCKET', 23_000), line('CREDIT_RESTORE', -15_000),
        ],
        upcoming: [item('Коммуналка', '2026-10-01', true), item('Аренда', '2026-11-18'), item('Связь', '2026-11-20')],
        trajectory: [{ date: '2026-11-17', balance: 48_800, income: 0, expense: 0, balanceWithForecast: null, items: [] }],
        ...over,
    } as unknown as PocketResponse;
}

const why = (p: PocketResponse, size: 'narrow' | 'wide' = 'narrow') => renderToStaticMarkup(
    <WhyPlate data={p} size={size} initialOpen onNz={() => {}} onReanchor={() => {}} overdue={{ busy: () => false, onMark: () => {} }} />,
).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
const short = (balance: number) => fresh({ pocket: balance, minPoint: { date: '2026-11-24', balance, drivenBy: null } });
const pocketCard = src('../components/PocketCard.tsx');

/** Ключ — «раздел справки, номер абзаца»; проверка — что абзац на своём месте. */
const PLACES: Record<string, () => void> = {
    'Что это за число 1': () => {
        expect(numberHint(fresh())).toBe('Столько можно тратить до 25 нояб. Аренда и Связь уже вычтены.');
        expect(today).toContain("bubble('number', numberHint(data)");
    },
    'Что это за число 2': () => {
        expect(why(fresh())).toMatch(/На картах .*Ещё уйдёт до 24 ноября .*Свободно .* самый низкий остаток — 24 ноября/);
    },
    'Что это за число 3': () => {
        expect(why(fresh())).toContain('по плану: Аренда, Связь; бронь без оплаты держится');
        expect(why(fresh({ upcoming: [item('Аренда', '2026-11-18')] }))).not.toContain('держится');
        // Хотелки с датой — «Цели», срез 6: там справка пока целиком.
        expect(pocketCard).toContain('<HelpDialog topic="pocket"');
    },
    'Сначала взнос, остальное твоё 1': () => expect(why(fresh())).toContain('Взносы в копилки -5 000 ₽ Отпуск; сначала взнос, остальное твоё'),
    'Сначала взнос, остальное твоё 2': () => expect(why(fresh())).toContain('сначала взнос, остальное твоё'),
    'Сначала взнос, остальное твоё 3': () => {
        expect(today).toContain('Обычных трат в плане нет');
        const form = renderToStaticMarkup(<UsualSpendingForm data={fresh()} onDone={() => {}} />);
        expect(form).toContain('Без этого число выше, чем будет на деле.');
    },
    'Сначала взнос, остальное твоё 4': () => {
        expect(PLUS_HINT).toBe('Купил — запиши здесь. Можно не всё: число держится на плане и остатке.');
        expect(today).toContain("bubble('plus', PLUS_HINT");
    },
    'НЗ 1': () => {
        expect(why(fresh())).toContain('НЗ не задан');
        expect(src('../components/pocket/NzForm.tsx')).toContain('{NZ_MEANING}');
    },
    'НЗ 2': () => expect(pocketCard).toContain('<HelpDialog topic="pocket"'),
    'Когда денег не хватает 1': () => {
        const gap = buildGapMode(short(-5_000))!;
        expect([gap.lead, gap.shortfall]).toEqual(['24 ноября по плану не хватит', 5_000]);
    },
    'Когда денег не хватает 2': () => {
        const html = renderToStaticMarkup(<MovePlate gap={buildGapMode(short(-5_000))!} horizonEnd="2026-11-25" onReanchor={() => {}} />);
        expect(html).toContain('Брони не двигаются — их дата известна заранее.');
    },
    'Когда денег не хватает 3': () => expect(buildGapMode(fresh({ buffer: 30_000 }))!.lead).toBe('24 ноября по плану придётся взять из НЗ'),
    'Срок, оговорки и остаток 1': () => {
        for (const label of ['До дохода', '2-й доход', '3 мес', '6 мес']) expect(today).toContain(`label: '${label}'`);
    },
    'Срок, оговорки и остаток 2': () => {
        const html = why(fresh(), 'wide');
        expect(html).toContain('Вернуть на кредитку -15 000 ₽ столько внести на кредитки, чтобы доступное дошло до «возвращаю до» в карте');
        expect(html).toContain('Если распечатать вклад 47 000 ₽ столько будет свободно, если снять вклад');
    },
    'Срок, оговорки и остаток 3': () => expect(why(fresh())).toMatch(/Из чего число .* Свободно .* Кроме этого — в число не входит/),
    'Срок, оговорки и остаток 4': () => {
        expect(buildTodayTiles(fresh(), null).find(t => t.key === 'cards')!.caption).toBe('17 нояб.');
        expect(cardsTileLabel(fresh()).replace(/\s/g, ' ')).toBe('На картах 48 800 ₽, обновлён 17 нояб. Обновить');
    },
    'Срок, оговорки и остаток 5': () => {
        expect(buildBreakHint('2026-11-01', '2026-10-30', '2026-11-17')).toBe('С 2 по 16 ноября записей нет — обновить остаток?');
    },
    'Срок, оговорки и остаток 6': () => expect(why(fresh())).toContain('Уже в копилках -1 000 ₽ на карте, но отложены на цели'),
};

describe('справка «Как считаются свободные деньги» → подача на «Сегодня» (ANO-189)', () => {
    it('у каждого из 18 абзацев — своё место, и мест не больше, чем абзацев', () => {
        const paragraphs = HELP.pocket.sections.flatMap(s => s.paragraphs.map((_, i) => `${s.heading} ${i + 1}`));
        expect(paragraphs).toHaveLength(18);
        expect(Object.keys(PLACES).sort()).toEqual([...paragraphs].sort());
    });

    for (const [paragraph, check] of Object.entries(PLACES)) it(paragraph, check);

    it('новое — склон и нажатие на день: подсказка у склона', () => {
        expect(SLOPE_HINT).toBe('Склон — сколько останется по дням. Нажми на день — что в нём.');
        expect(today).toContain("bubble('slope', SLOPE_HINT");
    });

    it('подсказки стоят на местах досок на обеих ширинах: S2-P1 и S2-P-1440', () => {
        for (const id of ['number', 'slope', 'plus']) {
            expect(today.split(`bubble('${id}'`).length - 1, id).toBe(2);
        }
    });
});
