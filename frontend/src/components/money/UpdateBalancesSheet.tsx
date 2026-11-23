import { useEffect, useState } from 'react';
import { Button, Field } from '@/ds';
import { fetchAccounts, fetchBalanceSheet, fetchPocket, previewBalances, saveBalances } from '../../api';
import type { Account, BalanceBatchResult, BalanceSheet } from '../../types/api';
import FormSheet from '../FormSheet';
import { cardAmount } from '../../lib/moneyCard';
import { batchOf, freeLine, lastCreditId, onCreditLine, rowCaption, rowName, rowNote, savedText } from '../../lib/updateBalances';
import { fmtNum } from '../../lib/format';
import { todayIso } from '../../lib/factDate';
import { writeFailure } from '../../lib/writeFailure';

/**
 * «Обновить остатки» (срез 4, п. 16; S4-U1, на 1440 — окном): все карты с остатком одним листом, пустое поле — остаток
 * прежний. Строки — ответ `GET /balance-checkpoints/sheet`, «Свободно станет…» — предпросмотр, запись — пакет одной
 * транзакцией. Входы — событие `UPDATE_BALANCES` (AppFrame). Заменил ReanchorSheet: опись 35–36.
 */

/** Сколько ждать после последней цифры, прежде чем спросить сервер, что станет со «Свободно». */
const PREVIEW_MS = 300;
const PREVIEW_FAILED = 'Не посчиталось, что станет со «Свободно». Сохранить можно и так.';

export default function UpdateBalancesSheet({ onClose, onSaved }: {
    onClose: () => void;
    /** Записано: строка внизу экрана (S4-U4) — её ведёт AppFrame. */
    onSaved: (text: string) => void;
}) {
    const [sheet, setSheet] = useState<BalanceSheet | null>(null);
    const [spending, setSpending] = useState<Account[]>([]);
    const [failed, setFailed] = useState(false);
    const [until, setUntil] = useState<string | null>(null);
    const load = () => { setFailed(false); fetchBalanceSheet().then(setSheet, () => setFailed(true)); };
    useEffect(() => {
        load();
        // Карты для трат — строкой «остаток не нужен» (S4-U1): человек видит, что карту не забыли. Не пришли — строк нет.
        fetchAccounts().then(a => setSpending(a.filter(x => !x.trackBalance)), () => setSpending([]));
        // Предпросмотр считает «Свободно» до ближайшего дохода, как шапка; на «Сегодня» период может быть другим — срок
        // называется словами. Не пришёл — строка без срока.
        fetchPocket().then(p => setUntil(p.horizon.endDate), () => setUntil(null));
    }, []);
    return (
        <FormSheet label="Обновить остатки" onClose={onClose}>
            <div className="flex flex-col gap-0.5 px-1 pr-10">
                <span className="text-[20px] font-semibold leading-6">Обновить остатки</span>
                <span className="text-sf-note text-sf-muted">Перепиши числа из приложения банка. Пустое поле — остаток прежний.</span>
            </div>
            {sheet ? <BalancesForm sheet={sheet} spending={spending} today={todayIso(new Date())} until={until} onSaved={onSaved} />
                : failed ? (
                    <div className="flex flex-col items-start gap-2 px-1">
                        <span className="text-sf-note">Не удалось загрузить карты</span>
                        <Button size="s" onClick={load}>Повторить</Button>
                    </div>
                ) : <span className="px-1 text-sf-note text-sf-muted">Загрузка…</span>}
        </FormSheet>
    );
}

export function BalancesForm({ sheet, spending, today, until = null, onSaved, initial = {} }: {
    sheet: BalanceSheet;
    /** Карты без остатка — строкой внизу. */
    spending: Account[];
    /** Местная дата, ISO: подписи «обновлён 12 нояб.». */
    today: string;
    /** Конец срока «Свободно», ISO: «Свободно до 5 дек.». */
    until?: string | null;
    onSaved: (text: string) => void;
    /** Введённое при открытии — для тестов разметки. */
    initial?: Record<string, string>;
}) {
    const [raws, setRaws] = useState(initial);
    /** Ответ предпросмотра на введённое `key`; result null — не пришёл. */
    const [preview, setPreview] = useState<{ key: string; result: BalanceBatchResult | null } | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const batch = batchOf(sheet.cards, raws);
    // Что введено сейчас: предпросмотр показывается, только пока отвечает на это же — устаревший ответ молчит.
    const key = batch && batch.entries.length > 0 ? JSON.stringify(batch.entries) : null;
    useEffect(() => {
        if (!key) return;
        let live = true;
        const t = setTimeout(() => previewBalances({ entries: JSON.parse(key) })
            .then(result => { if (live) setPreview({ key, result }); },
                () => { if (live) setPreview({ key, result: null }); }), PREVIEW_MS);
        return () => { live = false; clearTimeout(t); };
    }, [key]);
    const shown = preview && preview.key === key ? preview : null;
    const credit = lastCreditId(sheet.cards);

    const save = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!batch || !key) return;
        setBusy(true);
        setError(null);
        try {
            onSaved(savedText(await saveBalances(batch), sheet.cards, until));
        } catch (err) {
            setError(writeFailure(err));   // ANO-245: текст сервера — по-английски
            setBusy(false);
        }
    };

    return (
        <form onSubmit={save} className="flex flex-col gap-3">
            <div className="flex flex-col">
                {sheet.cards.map((c, i) => {
                    const raw = raws[c.accountId] ?? '';
                    const note = rowNote(c, raw);
                    return (
                        <div key={c.accountId} className="flex flex-col gap-1.5 border-b border-sf-rule py-2.5">
                            <div className="flex items-baseline justify-between gap-2.5">
                                <span className="min-w-0 text-sf-body font-semibold">{rowName(c)}</span>
                                <span className="shrink-0 whitespace-nowrap text-sf-label text-sf-muted">{rowCaption(c, today)}</span>
                            </div>
                            {c.accountId === credit && sheet.onCredit && (
                                <span className="text-sf-label text-sf-muted">{onCreditLine(sheet.onCredit, today)}</span>
                            )}
                            {/* Фокус — в первое поле: окно открылось с «Загрузка…» и поставило его на крестик (ревью #244). */}
                            <Field autoFocus={i === 0} aria-label={rowName(c)} inputMode="decimal" value={raw} suffix="₽" className="text-[20px] font-light"
                                placeholder={c.byRecords != null ? fmtNum(c.byRecords) : '0'} invalid={Number.isNaN(cardAmount(raw))}
                                onChange={e => setRaws(r => ({ ...r, [c.accountId]: e.target.value }))} />
                            {note && <span className="text-sf-note">{note}</span>}
                        </div>
                    );
                })}
                {spending.map(a => (
                    <div key={a.id} className="flex items-baseline justify-between gap-2.5 pt-2.5">
                        <span className="min-w-0 text-sf-note text-sf-muted">{a.name}</span>
                        <span className="shrink-0 text-sf-label text-sf-muted">остаток не нужен</span>
                    </div>
                ))}
            </div>
            {/* Прежний лист говорил «Якорится на сегодня… Задним числом — через Настройки» (опись 35, ревью #244). */}
            <span className="px-1 text-sf-label text-sf-muted">Обновление — на сегодня: сегодняшние записи уже в числе из банка. На другую дату — в листе карты.</span>
            {shown && <span role="status" className="px-1 text-sf-note">{shown.result ? freeLine(shown.result, until) : PREVIEW_FAILED}</span>}
            {error && <p role="alert" className="px-1 text-sf-note text-sf-error">{error}</p>}
            <Button type="submit" className="w-full" disabled={busy || !key}>{busy ? 'Сохраняю…' : 'Сохранить'}</Button>
        </form>
    );
}
