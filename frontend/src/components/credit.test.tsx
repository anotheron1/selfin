/**
 * «В кредит» в форме «+» и строка после записи (срез 4, пп. 17–18; доски S4-K1, S4-K2). Лист — Radix Dialog на портале,
 * на сервере он не рисуется: здесь подменён простым div. Нажатия проверяет стенд; связку числа «на картах» с вопросом и
 * отметки с запросом держит исходник.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactNode } from 'react';

vi.mock('./FormSheet', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
const { default: QuickAddModal } = await import('./Fab');
const { default: UndoToast } = await import('./UndoToast');

const FAB = readFileSync(new URL('./Fab.tsx', import.meta.url), 'utf8');
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');

describe('форма «+»: галочка «В кредит» (S4-K1)', () => {
    it('у нового расхода — галочка рядом с «Расход | Доход»; у дохода — нет', () => {
        expect(text(renderToStaticMarkup(<QuickAddModal onClose={() => {}} onSuccess={() => {}} />))).toContain('Расход Доход В кредит');
        expect(renderToStaticMarkup(<QuickAddModal preset={{ type: 'INCOME' }} onClose={() => {}} onSuccess={() => {}} />))
            .not.toContain('В кредит');
    });
    it('вопрос — по числу «на картах» из /pocket, отметка — одним касанием той же галочки', () => {
        // Правила отметки, вопроса и строки — lib/quickAdd.ts (creditForm, formLine); форма передаёт им своё.
        expect(FAB).toContain("offered: creditShown, isNew: !ev, record: main === 'record', onCredit, amount, cards: pocket?.currentBalance ?? null,");
        expect(FAB).toContain("{asks ? 'В кредит?' : 'В кредит'}");
        expect(FAB).toContain('note={overCards ? CREDIT_OVER_NOTE : undefined}');
        expect(FAB).toMatch(/<Flag ask=\{asks\} checked=\{onCredit\} onChange=\{\(e\) => setOnCredit\(e\.target\.checked\)\}>/);
        expect(FAB).toContain("isNew: !ev, record: main === 'record', credit, asks, transfer, amount, effect,");
    });
    it('отметка уходит только с «Записать»; правка шлёт её, только если галочка предложена', () => {
        expect(FAB).toContain('priority: kind, rawInput, onCredit: credit || undefined,');
        expect(FAB).toContain('onCredit: creditShown ? onCredit : undefined,');
        expect(FAB).toContain('setWritten({ text: creditWritten(name, amount), undo, credit: true });');
    });
});

describe('строка после записи в кредит (S4-K2)', () => {
    it('«Обновить» — лист «Обновить остатки»; «Отменить» — значком', () => {
        const html = renderToStaticMarkup(<UndoToast text="В кредит: Ремонт" onUndo={async () => {}} onDone={() => {}} onRefresh={() => {}} />);
        expect(html).toContain('aria-label="Обновить кредитку"');
        expect(text(html)).toContain('Обновить');
        expect(html).toContain('aria-label="Отменить"');
        expect(text(html)).not.toContain('Отменить');
        expect(FAB).toContain('onRefresh={written.credit ? () => { openUpdateBalances(); onClose(); } : undefined}');
        expect(readFileSync(new URL('../pages/Budget.tsx', import.meta.url), 'utf8'))
            .toContain('onRefresh={written.credit ? () => { openUpdateBalances(); setWritten(null); } : undefined}');
    });
    it('без кредита — прежняя строка: «Отменить» словом, «Обновить» нет', () => {
        const html = renderToStaticMarkup(<UndoToast text="Записано" onUndo={async () => {}} onDone={() => {}} />);
        expect(text(html)).toContain('Отменить');
        expect(html).not.toContain('Обновить');
    });
});
