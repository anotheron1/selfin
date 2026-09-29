import { Button } from './ui/button';
import type { FundMoney } from '../types/api';
import { FUND_MONEY_ANSWERS, FUND_MONEY_KEEP, FUND_MONEY_QUESTION } from '../lib/fundMoney';

interface Props {
    /** Идёт удаление: ответы заняты. */
    busy: boolean;
    /** Ответ — удалить, сделав с деньгами выбранное. */
    onAnswer: (money: FundMoney) => void;
    /** Закрыть вопрос, ничего не записав. */
    onKeep: () => void;
}

/**
 * Вопрос «что с деньгами» при удалении копилки (ANO-198). Появляется на отказе 409: когда
 * спрашивать, решает сервер (ANO-86) — у копилки со счётом своих денег нет, у пустой спрашивать
 * не о чем. Слова — `lib/fundMoney.ts`, одни на оба экрана.
 */
export default function FundMoneyQuestion({ busy, onAnswer, onKeep }: Props) {
    return (
        <div className="space-y-2">
            <p className="text-sm font-medium">{FUND_MONEY_QUESTION}</p>
            {FUND_MONEY_ANSWERS.map(a => (
                <div key={a.money} className="space-y-1">
                    <Button variant="outline" className="w-full" disabled={busy} onClick={() => onAnswer(a.money)}>
                        {a.label}
                    </Button>
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{a.effect}</p>
                </div>
            ))}
            <Button variant="ghost" className="w-full" disabled={busy} onClick={onKeep}>
                {FUND_MONEY_KEEP}
            </Button>
        </div>
    );
}
