/**
 * Разбор суммы-выражения в поле ввода (ANO-33): «450+1230+890» одной записью
 * вместо трёх транзакций или счёта в уме.
 *
 * Считаем сами, без `eval`: это пользовательский ввод, и подпускать его к
 * интерпретатору нельзя даже в локальном приложении.
 *
 * Скоуп v1: `+`, `-`, `*`, без скобок. Умножение приоритетнее сложения.
 */

export type AmountError =
    | 'empty'      // пусто — не ошибка ввода, просто нечего считать
    | 'syntax'     // не разбирается
    | 'nonPositive'; // ноль или минус: бэк отклонит (@Positive на factAmount)

export interface ParsedAmount {
    /** Итог в рублях, округлённый до копеек. null, если считать нечего или ввод битый. */
    value: number | null;
    /** Во вводе есть оператор — значит стоит показать предпросмотр суммы. */
    isExpression: boolean;
    error: AmountError | null;
}

/**
 * Приводим ввод к машинному виду. Пользователь пишет «3 х 250» кириллицей и
 * вставляет «1 234,56 ₽» из банковского приложения — с неразрывными пробелами
 * и символом валюты. Без нормализации это ошибка на пустом месте.
 */
function normalize(raw: string): string {
    return raw
        .replace(/[\s  ]/g, '')   // обычные, неразрывные и узкие пробелы
        .replace(/[₽]|руб\.?|р\.$/gi, '')
        .replace(/,/g, '.')
        .replace(/[x×х]/gi, '*')            // латинская x, знак умножения, кириллическая х
        .replace(/[–—−]/g, '-');            // тире и минус-юникод
}

type Token = number | '+' | '-' | '*';

function tokenize(s: string): Token[] | null {
    const tokens: Token[] = [];
    let i = 0;
    let expectNumber = true;

    while (i < s.length) {
        const ch = s[i];
        if (ch === '+' || ch === '-' || ch === '*') {
            // Знак в начале допускаем только как унарный минус
            if (expectNumber) {
                if (ch === '-' && tokens.length === 0) {
                    tokens.push(0, '-');   // -N === 0 - N
                    i++;
                    continue;
                }
                return null;               // два оператора подряд
            }
            tokens.push(ch);
            expectNumber = true;
            i++;
            continue;
        }
        const match = /^\d+(?:\.\d+)?/.exec(s.slice(i));
        if (!match || !expectNumber) return null;
        tokens.push(Number(match[0]));
        expectNumber = false;
        i += match[0].length;
    }

    if (expectNumber) return null;          // висящий оператор в конце
    return tokens;
}

/** Свёртка умножений, затем сложений/вычитаний слева направо. */
function evaluate(tokens: Token[]): number {
    const terms: number[] = [];
    const ops: ('+' | '-')[] = [];

    let current = tokens[0] as number;
    for (let i = 1; i < tokens.length; i += 2) {
        const op = tokens[i] as '+' | '-' | '*';
        const operand = tokens[i + 1] as number;
        if (op === '*') {
            current *= operand;
        } else {
            terms.push(current);
            ops.push(op);
            current = operand;
        }
    }
    terms.push(current);

    let total = terms[0];
    for (let i = 0; i < ops.length; i++) {
        total = ops[i] === '+' ? total + terms[i + 1] : total - terms[i + 1];
    }
    return total;
}

export function parseAmountExpression(raw: string): ParsedAmount {
    const s = normalize(raw ?? '');
    const isExpression = /[+\-*]/.test(s.slice(1));   // ведущий минус оператором не считаем

    if (!s) return { value: null, isExpression: false, error: 'empty' };

    const tokens = tokenize(s);
    if (!tokens) return { value: null, isExpression, error: 'syntax' };

    const result = evaluate(tokens);
    if (!Number.isFinite(result)) return { value: null, isExpression, error: 'syntax' };

    // Округляем только итог: 0.1+0.2 иначе даёт 0.30000000000000004,
    // а бэк ждёт BigDecimal с копейками.
    const value = Math.round(result * 100) / 100;
    if (value <= 0) return { value: null, isExpression, error: 'nonPositive' };

    return { value, isExpression, error: null };
}
