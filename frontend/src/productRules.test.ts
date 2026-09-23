/**
 * Сторож словаря (ANO-175): строки интерфейса проверяются на правила продукта 1, 2, 5, 12, 13.
 *
 * Канон — docs/superpowers/specs/2026-09-01-product-rules.md. Из тринадцати правил пять
 * проверяемы по словам на экране, и все пять ловит этот один тест. Остальные восемь — про то,
 * чего в продукте нет, или про форму работы; делать вид, что сторож их ловит, нельзя.
 *
 * Строка интерфейса — строковый литерал, текстовая часть шаблона или JSX-текст, но не
 * комментарий и не имя. Фронт разбирает компилятор TypeScript, бэк — маленький лексер ниже.
 * Поэтому в комментариях запрещённое слово писать можно: прежним сторожам по исходнику это
 * стоило запрета на слово даже в комментариях.
 *
 * Нарушение, которое сегодня на экране, здесь не чинится, а записывается долгом со ссылкой на
 * задачу. Долги только сокращаются: запись, которая находит меньше, чем записано, валит тест —
 * обнови число или вычеркни строку.
 *
 * Спека: docs/superpowers/specs/2026-09-23-dictionary-guard-design.md
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));

type Rule = 1 | 2 | 5 | 12 | 13;

/** Основы слов, сравнение без учёта регистра. */
const DICTIONARY: { rule: Rule; stem: string; why: string }[] = [
    { rule: 1, stem: 'лимит', why: 'назначаемый потолок' },
    { rule: 1, stem: 'бюджет', why: 'бюджет — это потолок, а план — прогноз' },
    { rule: 1, stem: 'превыш', why: 'приговор за превышение потолка' },
    { rule: 2, stem: 'отклонени', why: 'отчёт отклонений — только по явному запросу' },
    { rule: 5, stem: 'неправильн', why: 'экран не говорит, что человек завёл неправильно' },
    { rule: 5, stem: 'некорректн', why: 'экран не говорит, что человек завёл неправильно' },
    { rule: 5, stem: 'проверьте заполненные', why: 'обвиняет ввод, когда причина в другом' },
    { rule: 12, stem: 'перерасход', why: 'упрёк' },
    { rule: 12, stem: 'сэкономил', why: 'оценка задним числом' },
    { rule: 12, stem: 'выполнен', why: 'вердикт исполнения и счётчик несделанного' },
    { rule: 12, stem: 'просроч', why: 'упрёк за прошедшую дату' },
    { rule: 12, stem: 'забыл', why: 'упрёк' },
    { rule: 12, stem: 'превысил', why: 'упрёк' },
    { rule: 12, stem: 'провал', why: 'упрёк' },
    { rule: 13, stem: 'траектори', why: 'наше слово; у пользователей — «бегущий остаток»' },
    { rule: 13, stem: 'зарезервирован', why: 'у пользователей — «забронировано»' },
];

/** Сегодняшние нарушения. Каждое — со своей задачей; число — сколько строк с этой основой в файле. */
const DEBTS: { file: string; stem: string; count: number; task: string }[] = [
    // «Структура месяца»: «сэкономил», «перерасход», «% бюджета», «0 из 5 выполнена».
    { file: 'frontend/src/components/BudgetStructureSection.tsx', stem: 'сэкономил', count: 1, task: 'ANO-165' },
    { file: 'frontend/src/components/BudgetStructureSection.tsx', stem: 'перерасход', count: 2, task: 'ANO-165' },
    { file: 'frontend/src/components/BudgetStructureSection.tsx', stem: 'бюджет', count: 1, task: 'ANO-165' },
    { file: 'frontend/src/components/BudgetStructureSection.tsx', stem: 'выполнен', count: 2, task: 'ANO-165' },
    // «Траектория» вместо «бегущего остатка»; экран «Журнал», названный «Бюджетом».
    { file: 'frontend/src/components/CapitalSheet.tsx', stem: 'траектори', count: 1, task: 'ANO-124' },
    { file: 'frontend/src/components/CapitalTheoryDialog.tsx', stem: 'бюджет', count: 1, task: 'ANO-124' },
    { file: 'frontend/src/components/pocket/PocketTrajectoryChart.tsx', stem: 'траектори', count: 1, task: 'ANO-124' },
    { file: 'frontend/src/pages/Strategy.tsx', stem: 'траектори', count: 2, task: 'ANO-124' },
    { file: 'backend/src/main/java/ru/selfin/backend/service/PocketEngine.java', stem: 'траектори', count: 1, task: 'ANO-124' },
    // «Просроченные обязательства» в расшифровке кармашка.
    { file: 'backend/src/main/java/ru/selfin/backend/service/PocketEngine.java', stem: 'просроч', count: 1, task: 'ANO-101' },
    // Быстрый ввод на любую ошибку винит заполненные поля.
    { file: 'frontend/src/components/Fab.tsx', stem: 'проверьте заполненные', count: 1, task: 'ANO-170' },
    // «Снимки бюджета» — имя функции, чья судьба решается там же.
    { file: 'frontend/src/pages/Settings.tsx', stem: 'бюджет', count: 1, task: 'ANO-121' },
];

/** Не нарушения: слово словаря, которое здесь значит другое. Каждое — с причиной. */
const ALLOWED: { file: string; stem: string; count: number; why: string }[] = [
    {
        file: 'frontend/src/components/accounts/accountsCopy.ts', stem: 'лимит', count: 1,
        why: 'кредитный лимит банка — свойство карты, а не потолок трат, который человек назначает себе',
    },
];

// ── Где живут строки интерфейса ──────────────────────────────────────────────

function walk(dir: string, accept: (path: string) => boolean): string[] {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) return walk(path, accept);
        return accept(path) ? [path] : [];
    });
}

const key = (path: string) => relative(ROOT, path).split(sep).join('/');

const FRONTEND = walk(join(ROOT, 'frontend', 'src'), (p) =>
    /\.tsx?$/.test(p) && !/\.test\.tsx?$/.test(p) && !p.endsWith('.d.ts'));

// Контроллеры не сканируются: их строки — описания API для Swagger, а не текст экрана.
const BACKEND = walk(join(ROOT, 'backend', 'src', 'main', 'java'), (p) =>
    p.endsWith('.java') && !p.split(sep).includes('controller'));

type Found = { file: string; line: number; text: string };

/** Литералы, части шаблонов и JSX-текст — ровно то, что может попасть на экран. */
function frontendStrings(path: string): Found[] {
    const source = readFileSync(path, 'utf8');
    const kind = path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
    const sf = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true, kind);
    const out: Found[] = [];
    const visit = (node: ts.Node): void => {
        if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) return;
        if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)
            || ts.isTemplateHead(node) || ts.isTemplateMiddle(node) || ts.isTemplateTail(node)
            || ts.isJsxText(node)) {
            const line = sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
            out.push({ file: key(path), line, text: node.text });
        }
        ts.forEachChild(node, visit);
    };
    visit(sf);
    return out;
}

/**
 * Строковые литералы Java вне комментариев и вне аргументов аннотаций. Лексер, а не регулярка:
 * кавычка внутри комментария или символьного литерала не должна открывать строку.
 */
function backendStrings(path: string): Found[] {
    const src = readFileSync(path, 'utf8');
    const out: Found[] = [];
    let line = 1;
    let i = 0;
    const advance = (to: number) => {
        for (let k = i; k < to; k++) if (src[k] === '\n') line++;
        i = to;
    };
    /** Конец литерала, начинающегося в i: текстовый блок, строка или символ. */
    const literalEnd = (at: number): number => {
        if (src.startsWith('"""', at)) {
            const end = src.indexOf('"""', at + 3);
            return end < 0 ? src.length : end + 3;
        }
        const quote = src[at];
        let k = at + 1;
        while (k < src.length && src[k] !== quote) k += src[k] === '\\' ? 2 : 1;
        return k + 1;
    };
    const commentEnd = (at: number): number => {
        if (src.startsWith('//', at)) {
            const end = src.indexOf('\n', at);
            return end < 0 ? src.length : end;
        }
        const end = src.indexOf('*/', at + 2);
        return end < 0 ? src.length : end + 2;
    };
    while (i < src.length) {
        const c = src[i];
        if (src.startsWith('//', i) || src.startsWith('/*', i)) {
            advance(commentEnd(i));
        } else if (c === '@' && /[A-Za-z]/.test(src[i + 1] ?? '')) {
            let k = i + 1;
            while (k < src.length && /[\w.]/.test(src[k])) k++;
            while (k < src.length && (src[k] === ' ' || src[k] === '\t')) k++;
            if (src[k] !== '(') { advance(k); continue; }
            let depth = 0;
            do {
                if (src[k] === '"' || src[k] === "'") { k = literalEnd(k); continue; }
                if (src.startsWith('//', k) || src.startsWith('/*', k)) { k = commentEnd(k); continue; }
                if (src[k] === '(') depth++;
                if (src[k] === ')') depth--;
                k++;
            } while (depth > 0 && k < src.length);
            advance(k);
        } else if (c === '"') {
            const end = literalEnd(i);
            const block = src.startsWith('"""', i);
            const text = src.slice(i + (block ? 3 : 1), end - (block ? 3 : 1));
            out.push({ file: key(path), line, text });
            advance(end);
        } else if (c === "'") {
            advance(literalEnd(i));
        } else {
            advance(i + 1);
        }
    }
    return out;
}

const STRINGS: Found[] = [
    ...FRONTEND.flatMap(frontendStrings),
    ...BACKEND.flatMap(backendStrings),
];

type Hit = Found & { rule: Rule; stem: string };
const HITS: Hit[] = STRINGS.flatMap((s) => DICTIONARY
    .filter((d) => s.text.toLowerCase().includes(d.stem))
    .map((d) => ({ ...s, rule: d.rule, stem: d.stem })));

const counted = new Map<string, Hit[]>();
for (const h of HITS) {
    const id = `${h.file} «${h.stem}»`;
    counted.set(id, [...(counted.get(id) ?? []), h]);
}
const recorded = new Map<string, number>();
for (const r of [...DEBTS, ...ALLOWED]) {
    const id = `${r.file} «${r.stem}»`;
    recorded.set(id, (recorded.get(id) ?? 0) + r.count);
}
const show = (h: Hit) => `  правило ${h.rule}, «${h.stem}» — ${h.file}:${h.line}  ${JSON.stringify(h.text.trim())}`;

describe('словарь правил продукта на строках интерфейса (ANO-175)', () => {
    it('извлекатель видит интерфейс — иначе зелёный цвет ничего не значит', () => {
        // Ловушка, в которую уже попадали инструменты ANO-50: проверка искала поле, которого нет,
        // ничего не нашла и доложила «нарушений нет». Здесь такое не пройдёт молча.
        expect(FRONTEND.length).toBeGreaterThan(60);
        expect(BACKEND.length).toBeGreaterThan(40);
        const texts = STRINGS.map((s) => s.text);
        expect(texts.some((t) => t.includes('раньше по плану'))).toBe(true);    // UpcomingList.tsx
        expect(texts.some((t) => t.includes('Взносы в копилки'))).toBe(true);   // PocketEngine.java
    });

    it('каждое слово словаря на экране — либо долг с задачей, либо разрешение с причиной', () => {
        const fresh = [...counted].flatMap(([id, hits]) =>
            hits.length > (recorded.get(id) ?? 0) ? hits : []);
        expect(fresh.map(show).join('\n'), 'новые нарушения правил продукта').toBe('');
    });

    it('характер плановой строки не называется очерёдностью (канон, «Характер плановой строки»)', () => {
        // Точное сравнение, а не основа: «средний» законно живёт в «среднем расходе».
        const orderWords = ['высокий', 'средний', 'низкий'];
        const found = STRINGS.filter((s) => orderWords.includes(s.text.trim().toLowerCase()));
        expect(found.map((s) => `  ${s.file}:${s.line}  ${JSON.stringify(s.text.trim())}`).join('\n'),
            'имена характера — в lib/priority.ts').toBe('');
    });

    it('долги только сокращаются: запись, которая находит меньше записанного, устарела', () => {
        const stale = [...recorded].flatMap(([id, count]) => {
            const now = counted.get(id)?.length ?? 0;
            return now < count ? [`  ${id}: записано ${count}, на экране ${now}`] : [];
        });
        expect(stale.join('\n'), 'обнови число или вычеркни строку').toBe('');
    });
});
