#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапу 5 — кармашек. Главное число продукта.
 *
 * Ходит по API. Блоки, где суть в интерфейсе (фраза-ответ, календарь глазами, свободное
 * исследование), помечает «руками». Блоки, требующие чистого стенда (5.14, 5.15, 5.18, 5.21),
 * вынесены в ano50-stage5-clean.mjs.
 *
 *   node tools/ano50-stage5.mjs              # прогон, МЕНЯЕТ ДАННЫЕ и убирает за собой
 *   node tools/ano50-stage5.mjs --with-5-8   # плюс блок 5.8: он деструктивен и за собой НЕ убирает
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && a !== undefined && b !== undefined
  && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const iso = (d) => d.toISOString().slice(0, 10);
const today = iso(new Date());
const shift = (n) => iso(new Date(Date.now() + n * 86400000));
const addMonths = (n, day) => {
  const d = new Date();
  if (day) d.setDate(1);
  d.setMonth(d.getMonth() + n);
  if (day) d.setDate(day);
  return iso(d);
};

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  →  ', 'НЕЛЬЗЯ': '  x  ' }[verdict];
  console.log(`${mark}${block}  ${message}`);
  if (data && verdict !== 'СОШЛОСЬ') console.log(`        ${JSON.stringify(data)}`);
};

async function api(path, init) {
  const res = await fetch(`${API}${path}`, init);
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  return { status: res.status, ok: res.ok, body };
}
async function must(p, i) {
  const r = await api(p, i);
  if (!r.ok) throw new Error(`${p} -> ${r.status}: ${JSON.stringify(r.body).slice(0, 250)}`);
  return r.body;
}
const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});
const put = (body) => ({ method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

const MARK = 'ANO-50';
const allEvents = () => must('/events?startDate=2026-01-01&endDate=2051-12-31');

/**
 * Удаление события. `scope=ALL` на неповторяющемся событии даёт 400 «Scope FOLLOWING/ALL
 * requires a recurring event», а удаление порождённого события без скоупа убирает одно
 * вхождение из тридцати четырёх — поэтому порядок попыток зависит от вида события.
 */
async function del(id, recurring) {
  if (recurring) {
    const all = await api(`/events/${id}?scope=ALL`, { method: 'DELETE' });
    if (all.ok) return all;
  }
  const plain = await api(`/events/${id}`, { method: 'DELETE' });
  if (plain.ok) return plain;
  return api(`/events/${id}?scope=ALL`, { method: 'DELETE' });
}

/** Сплошная зачистка по метке: сначала правила целиком, потом одиночные события. */
async function sweep() {
  for (let pass = 0; pass < 3; pass += 1) {
    const mine = (await allEvents()).filter((e) => (e.description ?? '').includes(MARK));
    if (!mine.length) return 0;
    const rules = [...new Set(mine.map((e) => e.recurringRuleId).filter(Boolean))];
    for (const r of rules) await del(mine.find((e) => e.recurringRuleId === r).id, true);
    for (const e of mine.filter((x) => !x.recurringRuleId)) await del(e.id, false);
  }
  return (await allEvents()).filter((e) => (e.description ?? '').includes(MARK)).length;
}

/** Сумма дневных потоков траектории. Сверять её с журналом «в лоб» нельзя: день 0 несёт
 *  резерв просрочки, исполненные планы в траекторию не входят второй раз, хотелки не входят
 *  вовсе, а взносы в копилки в журнале не события. Поэтому проверяются только дельты. */
const flows = (p) => (p.trajectory ?? []).reduce(
  (s, x) => ({ расход: s.расход + (num(x.expense) ?? 0), доход: s.доход + (num(x.income) ?? 0) }),
  { расход: 0, доход: 0 },
);

/** Строки ДО TRAJECTORY_MIN объясняют минимум; всё после POCKET — оговорки (Javadoc BreakdownType). */
const EXPLAINING = ['STARTING_BALANCE', 'OVERDUE_RESERVE', 'PLANNED_EXPENSES',
  'SAVINGS_CONTRIBUTIONS', 'PLANNED_INCOME', 'UNPLANNED_FORECAST'];
const INFO_ONLY = ['TRAJECTORY_MIN', 'BUFFER', 'POCKET', 'CREDIT_RESTORE', 'WISHLIST_INFO'];

const pocketOf = (scope) => must(`/pocket${scope ? `?scope=${encodeURIComponent(scope)}` : ''}`);
const byType = (p) => Object.fromEntries((p.breakdown ?? []).map((l) => [l.type, num(l.amount)]));

/** Инвариант объяснимости на одном скоупе. */
function explainability(p) {
  const t = byType(p);
  const explained = EXPLAINING.reduce((s, k) => s + (t[k] ?? 0), 0);
  const min = t.TRAJECTORY_MIN;
  const buffer = t.BUFFER ?? 0;
  return {
    ok: eq(explained, min) && eq(min + buffer, t.POCKET) && eq(t.POCKET, num(p.pocket)),
    сумма: explained,
    минимум: min ?? null,
    разница: min === undefined ? null : explained - min,
    буфер: buffer,
    кармашек: num(p.pocket),
    слагаемые: Object.fromEntries(EXPLAINING.filter((k) => t[k] !== undefined).map((k) => [k, t[k]])),
  };
}

/** Шесть чисел, снимаемых до и после любого действия (план, §4). Консоль браузера — руками. */
async function six() {
  const monthStart = `${today.slice(0, 8)}01`;
  const monthEnd = `${today.slice(0, 8)}28`;
  const [p, accounts, capital, events] = await Promise.all([
    must('/pocket'), must('/accounts'), must('/capital/summary'),
    must(`/events?startDate=${monthStart}&endDate=${monthEnd}`),
  ]);
  const cash = accounts.filter((a) => a.trackBalance && (a.kind === 'DEBIT' || a.kind === 'CASH'))
    .reduce((s, a) => s + (num(a.balance) ?? 0), 0);
  return {
    кармашек: num(p.pocket),
    минимум: num(p.minPoint?.balance),
    датаМинимума: p.minPoint?.date ?? null,
    остаток: cash,
    капиталИтого: num(capital.total ?? capital.netWorth ?? capital.capital),
    активы: num(capital.assets),
    обязательства: num(capital.liabilities),
    событийВМесяце: events.filter((e) => !e.deleted).length,
  };
}
const diffSix = (a, b) => Object.keys(a)
  .filter((k) => (typeof a[k] === 'number' ? !eq(a[k], b[k]) : a[k] !== b[k]));

async function main() {
  console.log(`\nANO-50 · первый проход, этап 5 — кармашек · ${today}`);
  console.log('='.repeat(78));

  const cats = await must('/categories');
  const expCat = cats.find((c) => c.name === 'Продукты') ?? cats.find((c) => c.type === 'EXPENSE');
  const isPrimary = (c) => Boolean(c.primaryIncome ?? c.isPrimaryIncome);
  // Горизонт цепляется только за «основной доход» (спека §4), поэтому доходы для проверок
  // горизонта берутся из основной категории, а разовая премия из побочной — это блок 5.19.
  const incCat = cats.find((c) => c.type === 'INCOME' && isPrimary(c)) ?? cats.find((c) => c.type === 'INCOME');
  const sideCat = cats.find((c) => c.type === 'INCOME' && !isPrimary(c)) ?? incCat;
  const created = [];
  const settings0 = await must('/settings/pocket');

  const p0 = await pocketOf();
  console.log(`старт: кармашек ${money(p0.pocket)}, минимум ${money(p0.minPoint?.balance)} на ${p0.minPoint?.date}, `
    + `горизонт ${p0.horizon.endDate} «${p0.horizon.label}», буфер ${money(settings0.bufferAmount)}\n`);

  // ── 5.1 расшифровка сходится ──────────────────────────────────────────────
  {
    const e = explainability(p0);
    const t = byType(p0);
    const infoPresent = INFO_ONLY.filter((k) => t[k] !== undefined && k !== 'POCKET' && k !== 'TRAJECTORY_MIN');
    e.ok
      ? record('5.1', 'СОШЛОСЬ', `слагаемые = минимум ${money(e.минимум)}, минимум − буфер = кармашек ${money(e.кармашек)}`)
      : record('5.1', 'РАСХОЖДЕНИЕ', 'инвариант объяснимости не держится', e);
    const withInfo = e.сумма + infoPresent.reduce((s, k) => s + t[k], 0);
    infoPresent.length && eq(withInfo, e.минимум)
      ? record('5.1', 'РАСХОЖДЕНИЕ', 'сумма сходится и С информационными строками — разделение слагаемых и итогов не проверяется', { сИтогами: withInfo })
      : record('5.1', 'СОШЛОСЬ', `информационные строки (${infoPresent.join(', ') || 'нет'}) в сумму не входят`);
  }

  // ── 5.2 скоуп «до дохода» ─────────────────────────────────────────────────
  {
    const primaryIds = new Set(cats.filter((c) => c.type === 'INCOME' && isPrimary(c)).map((c) => c.id));
    const future = (await must(`/events?startDate=${today}&endDate=${addMonths(4)}`))
      .filter((e) => !e.deleted && e.type === 'INCOME' && e.date > today)
      .sort((a, b) => a.date.localeCompare(b.date));
    const nearestPrimary = future.find((e) => primaryIds.has(e.categoryId));
    const nearestAny = future[0];
    const hz = p0.horizon;
    if (!nearestPrimary) {
      record('5.2', 'СМОТРЕТЬ', 'доходов основной категории впереди нет — блок закрывается в 5.14', { горизонт: hz });
    } else if (hz.endDate === nearestPrimary.date) {
      const dd = `${nearestPrimary.date.slice(8, 10)}.${nearestPrimary.date.slice(5, 7)}`;
      hz.label.includes(dd)
        ? record('5.2', 'СОШЛОСЬ', `горизонт ${hz.endDate} = ближайший доход основной категории, подпись «${hz.label}» согласована`)
        : record('5.2', 'РАСХОЖДЕНИЕ', 'горизонт верный, подпись не называет его дату', { подпись: hz.label, дата: nearestPrimary.date });
    } else {
      record('5.2', 'РАСХОЖДЕНИЕ', 'горизонт не равен ближайшему доходу основной категории',
        { горизонт: hz.endDate, ближайшийОсновной: nearestPrimary.date, подпись: hz.label });
    }
    // Побочный доход раньше основного горизонт не двигает — это спека §4, но подпись
    // «до дохода 15.09» об этом молчит, а в журнале человек видит доход 11.09.
    if (nearestAny && nearestPrimary && nearestAny.date < nearestPrimary.date) {
      record('5.2', 'СМОТРЕТЬ',
        `в журнале есть доход ${nearestAny.date} раньше горизонта ${hz.endDate}, но подпись «${hz.label}» не оговаривает, что считается только основной доход`,
        { побочныйДоход: nearestAny.date, сумма: num(nearestAny.plannedAmount), основной: nearestPrimary.date });
    }
  }

  // ── 5.3 скоуп на три месяца ───────────────────────────────────────────────
  {
    const p3 = await pocketOf('MONTHS:3');
    const e = explainability(p3);
    e.ok ? record('5.3', 'СОШЛОСЬ', `три месяца: минимум ${money(e.минимум)}, кармашек ${money(e.кармашек)}`)
      : record('5.3', 'РАСХОЖДЕНИЕ', 'инвариант объяснимости на трёх месяцах не держится', e);
    const short = byType(p0);
    const long = byType(p3);
    const появились = Object.keys(long).filter((k) => short[k] === undefined);
    появились.length
      ? record('5.3', 'СОШЛОСЬ', `на длинном скоупе добавились строки: ${появились.join(', ')}`)
      : record('5.3', 'СМОТРЕТЬ', 'набор строк на коротком и длинном скоупе одинаков', { строки: Object.keys(long) });

    // Разбивка объясняет минимум, а минимум может лежать в самом начале длинного скоупа.
    // Тогда всё, что происходит дальше, в разбивку не попадает ни одной строкой.
    const minDate = p3.minPoint?.date;
    const после = (p3.trajectory ?? []).filter((x) => x.date > minDate);
    const хвостРасхода = после.reduce((s, x) => s + (num(x.expense) ?? 0), 0);
    const строкаРасходов = (p3.breakdown ?? []).find((l) => l.type === 'PLANNED_EXPENSES');
    хвостРасхода > 0
      ? record('5.3', 'СМОТРЕТЬ',
        `на скоупе «3 месяца» минимум на ${minDate}, и ${money(хвостРасхода)} расходов за оставшиеся ${после.length} дней в разбивку не попадают ни одной строкой`,
        { подписьСтроки: строкаРасходов?.label ?? null, конецСкоупа: p3.horizon.endDate, датаМинимума: minDate })
      : record('5.3', 'СОШЛОСЬ', 'после даты минимума расходов в траектории нет — разбивка накрывает весь скоуп');
  }

  // ── 5.4 один день, два ответа ─────────────────────────────────────────────
  {
    const scopes = ['NEXT_INCOME', 'MONTHS:3', 'MONTHS:6', 'MONTHS:12'];
    const day = p0.horizon.endDate;
    const res = {};
    for (const s of scopes) {
      const p = await pocketOf(s);
      const t = byType(p);
      const point = (p.trajectory ?? []).find((x) => x.date === day);
      res[s] = {
        кармашек: num(p.pocket),
        минимум: num(p.minPoint?.balance),
        датаМинимума: p.minPoint?.date ?? null,
        балансДня: point ? num(point.balance) : null,
        прогноз: t.UNPLANNED_FORECAST ?? null,
      };
    }
    const balances = scopes.map((s) => res[s].балансДня).filter((v) => v !== null);
    const uniq = [...new Set(balances.map((v) => Math.round(v * 100)))];
    uniq.length > 1
      ? record('5.4', 'СМОТРЕТЬ', `баланс ${day} различается между скоупами — известное отклонение (спека §3.5)`, res)
      : record('5.4', 'СОШЛОСЬ', `баланс ${day} одинаков на всех четырёх скоупах: ${money(balances[0])}`, res);
    const mins = scopes.map((s) => res[s].минимум);
    const monotone = mins.every((v, i) => i === 0 || v === null || mins[i - 1] === null || v <= mins[i - 1] + EPS);
    monotone
      ? record('5.4', 'СОШЛОСЬ', 'минимум не растёт при расширении окна')
      : record('5.4', 'СМОТРЕТЬ', 'минимум по более широкому окну ВЫШЕ — нарушение здравого смысла (материал ANO-22)',
        Object.fromEntries(scopes.map((s, i) => [s, mins[i]])));
  }

  // ── 5.5 буфер безопасности ────────────────────────────────────────────────
  {
    const before = await pocketOf();
    const wBefore = (await api('/wishlist/simulation')).body?.constraints ?? null;
    await must('/settings/pocket', put({ bufferAmount: 15000 }));
    const after = await pocketOf();
    const t = byType(after);
    const dPocket = num(after.pocket) - num(before.pocket);
    eq(dPocket, -15000)
      ? record('5.5', 'СОШЛОСЬ', 'кармашек упал ровно на 15 000')
      : record('5.5', 'РАСХОЖДЕНИЕ', 'кармашек сдвинулся не на 15 000',
        { сдвиг: dPocket, было: num(before.pocket), стало: num(after.pocket) });
    eq(num(after.minPoint?.balance), num(before.minPoint?.balance))
      ? record('5.5', 'СОШЛОСЬ', 'минимум траектории не изменился — буфер вычитается после минимума')
      : record('5.5', 'РАСХОЖДЕНИЕ', 'буфер сдвинул минимум траектории',
        { было: num(before.minPoint?.balance), стало: num(after.minPoint?.balance) });
    eq(t.BUFFER, -15000)
      ? record('5.5', 'СОШЛОСЬ', 'строка буфера появилась со знаком минус')
      : record('5.5', 'РАСХОЖДЕНИЕ', 'строки буфера нет или знак не тот', { строка: t.BUFFER ?? null, полеBuffer: num(after.buffer) });
    // «Что ещё могло сдвинуться»: потолок доступного в хотелках обязан упасть на те же 15 000,
    // иначе буфер, который человек отложил, всё равно предлагается потратить на хотелку.
    const wAfter = (await api('/wishlist/simulation')).body?.constraints ?? null;
    if (wBefore && wAfter) {
      const сдвиги = Object.fromEntries(Object.keys(wBefore)
        .filter((k) => typeof wBefore[k] === 'number')
        .map((k) => [k, Number((num(wAfter[k]) - num(wBefore[k])).toFixed(2))])
        .filter(([, v]) => Math.abs(v) > EPS));
      Object.values(сдвиги).some((v) => eq(v, -15000))
        ? record('5.5', 'СОШЛОСЬ', 'потолок хотелок упал на те же 15 000', { сдвиги })
        : record('5.5', 'РАСХОЖДЕНИЕ', 'буфер не дошёл до хотелок: ни одно ограничение не упало на 15 000',
          { сдвиги: Object.keys(сдвиги).length ? сдвиги : 'ни одно ограничение не изменилось', было: wBefore, стало: wAfter });
    }
    await must('/settings/pocket', put({ bufferAmount: settings0.bufferAmount ?? 0 }));
    const back = await pocketOf();
    eq(num(back.pocket), num(before.pocket))
      ? record('5.5', 'СОШЛОСЬ', 'снятие буфера вернуло кармашек в точности')
      : record('5.5', 'РАСХОЖДЕНИЕ', 'после снятия буфера кармашек не вернулся',
        { было: num(before.pocket), стало: num(back.pocket) });
  }

  // ── 5.6 просрочка в расшифровке ───────────────────────────────────────────
  {
    const p = await pocketOf();
    const t = byType(p);
    const line = (p.breakdown ?? []).find((l) => l.type === 'OVERDUE_RESERVE');
    line
      ? record('5.6', 'СОШЛОСЬ', `строка просрочки есть: «${line.label}» на ${money(line.amount)}, подробностей ${(line.details ?? []).length}`)
      : record('5.6', 'СМОТРЕТЬ', 'строки просрочки нет — проверяю созданием');
    if (line) record('5.6', 'СМОТРЕТЬ', 'состав строки просрочки — глазами', { состав: line.details });

    const anchor = p.checkpointDate;
    const beforeAnchor = iso(new Date(new Date(anchor).getTime() - 4 * 86400000));
    const afterAnchor = iso(new Date(new Date(anchor).getTime() + 2 * 86400000));
    const base = t.OVERDUE_RESERVE ?? 0;

    const old = await must('/events', send('POST', {
      date: beforeAnchor, categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 4000, description: 'Просрочка до якоря ANO-50',
    }));
    created.push(old.id);
    const afterOld = byType(await pocketOf()).OVERDUE_RESERVE ?? 0;
    eq(afterOld, base)
      ? record('5.6', 'СОШЛОСЬ', `просрочка старше якоря (${beforeAnchor} < ${anchor}) не резервируется`)
      : record('5.6', 'РАСХОЖДЕНИЕ', 'просрочка старше якоря попала в резерв — она уже учтена в остатке, это двойной счёт',
        { было: base, стало: afterOld, датаСобытия: beforeAnchor, якорь: anchor });

    const fresh = await must('/events', send('POST', {
      date: afterAnchor, categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 7000, description: 'Просрочка после якоря ANO-50',
    }));
    created.push(fresh.id);
    const pFresh = await pocketOf();
    const afterFresh = byType(pFresh).OVERDUE_RESERVE ?? 0;
    eq(afterFresh - afterOld, -7000)
      ? record('5.6', 'СОШЛОСЬ', `просрочка внутри окна (${afterAnchor}) зарезервирована на 7 000`)
      : record('5.6', 'РАСХОЖДЕНИЕ', 'просрочка внутри окна зарезервирована не на 7 000',
        { сдвиг: afterFresh - afterOld, датаСобытия: afterAnchor, якорь: anchor, сегодня: today });
    const freshLine = (pFresh.breakdown ?? []).find((l) => l.type === 'OVERDUE_RESERVE');
    (freshLine?.details ?? []).some((d) => String(d).includes('ANO-50'))
      ? record('5.6', 'СОШЛОСЬ', 'новая просрочка названа в подробностях строки')
      : record('5.6', 'РАСХОЖДЕНИЕ', 'зарезервированной просрочки не видно в подробностях — человек не узнает, что именно вычтено',
        { подробности: freshLine?.details ?? null });
    for (const id of [old.id, fresh.id]) await del(id);
    created.length = 0;
  }

  // ── 5.7 три числа кармашка ────────────────────────────────────────────────
  {
    const p = await pocketOf();
    const accounts = await must('/accounts');
    const first = num(p.pocket);
    const second = num(p.pocketAfterCreditRestore);
    const third = num(p.pocketWithDeposits);
    const restoreNeed = accounts.filter((a) => a.kind === 'CREDIT' && a.availableFloor != null)
      .reduce((s, a) => s + Math.max(0, num(a.availableFloor) - num(a.balance)), 0);
    const deposits = accounts.filter((a) => a.kind === 'DEPOSIT' && a.trackBalance)
      .reduce((s, a) => s + (num(a.balance) ?? 0), 0);
    const t = byType(p);

    second !== null && second <= first + EPS && third !== null && third >= first - EPS
      ? record('5.7', 'СОШЛОСЬ', `второе ${money(second)} ≤ первое ${money(first)} ≤ третье ${money(third)}`)
      : record('5.7', 'РАСХОЖДЕНИЕ', 'порядок трёх чисел нарушен', { первое: first, второе: second, третье: third });
    eq(second, first - restoreNeed)
      ? record('5.7', 'СОШЛОСЬ', `второе = первое − возврат к планке (${money(restoreNeed)})`)
      : record('5.7', 'РАСХОЖДЕНИЕ', 'второе не равно первое − Σ(планка − доступно)',
        { первое: first, второе: second, расчётныйВозврат: restoreNeed, строка: t.CREDIT_RESTORE ?? null });
    eq(third, first + deposits)
      ? record('5.7', 'СОШЛОСЬ', `третье = первое + вклады (${money(deposits)})`)
      : record('5.7', 'РАСХОЖДЕНИЕ', 'третье не равно первое + сумма вкладов', { первое: first, третье: third, вклады: deposits });
    eq(t.CREDIT_RESTORE, -restoreNeed)
      ? record('5.7', 'СОШЛОСЬ', 'строка CREDIT_RESTORE равна расчётному возврату со знаком минус')
      : record('5.7', 'РАСХОЖДЕНИЕ', 'строка CREDIT_RESTORE не сходится с расчётом',
        { строка: t.CREDIT_RESTORE ?? null, расчёт: -restoreNeed });
    record('5.7', 'РУКАМИ', 'расставлены ли три числа по важности: первое крупно, второе обычным, третье мелким');
  }

  // ── 5.9 фраза-ответ, числовая часть ───────────────────────────────────────
  {
    const p = await pocketOf();
    const minDate = p.minPoint?.date;
    const drivenBy = p.minPoint?.drivenBy ?? null;
    const dayEvents = (await must(`/events?startDate=${minDate}&endDate=${minDate}`))
      .filter((e) => !e.deleted && e.type === 'EXPENSE');
    const biggest = dayEvents
      .sort((a, b) => num(b.plannedAmount ?? b.factAmount) - num(a.plannedAmount ?? a.factAmount))[0];
    if (!biggest) {
      record('5.9', 'СМОТРЕТЬ', `в день минимума ${minDate} расходов-событий нет, drivenBy = ${drivenBy} — минимум создан не событием`,
        { минимум: num(p.minPoint?.balance), дата: minDate, горизонт: p.horizon.endDate });
      record('5.9', 'РУКАМИ', 'что говорит фраза-ответ, когда виновника нет — край из оракула блока');
    } else if (drivenBy && String(biggest.description ?? '').includes(String(drivenBy).slice(0, 10))) {
      record('5.9', 'СОШЛОСЬ', `виновник «${drivenBy}» — действительно крупнейший расход ${minDate}`);
    } else if (!drivenBy && !biggest.description) {
      // Javadoc MinPoint: drivenBy = null, если у события нет описания. Правило соблюдено,
      // но человек видит день минимума с реальным расходом и без имени виновника.
      record('5.9', 'СМОТРЕТЬ',
        `виновник не назван, хотя ${minDate} есть расход ${money(biggest.plannedAmount ?? biggest.factAmount)}: у события пустое описание`,
        { категория: biggest.categoryName ?? biggest.categoryId, сумма: num(biggest.plannedAmount ?? biggest.factAmount) });
    } else {
      record('5.9', 'РАСХОЖДЕНИЕ', 'drivenBy не совпадает с крупнейшим расходом дня минимума',
        { drivenBy, крупнейший: biggest.description, сумма: num(biggest.plannedAmount ?? biggest.factAmount) });
    }
    record('5.9', 'РУКАМИ', 'сама фраза на дашборде: даты и суммы в тексте против чисел');
  }

  // ── 5.10 календарь-близнец, данные под ним ────────────────────────────────
  {
    const p = await pocketOf();
    const minDate = p.minPoint?.date;
    const trj = p.trajectory ?? [];
    const point = trj.find((x) => x.date === minDate);
    if (!point) {
      record('5.10', 'РАСХОЖДЕНИЕ', 'дня минимума нет в траектории — календарю нечего подсветить',
        { датаМинимума: minDate, первая: trj[0]?.date, последняя: trj[trj.length - 1]?.date });
    } else {
      eq(num(point.balance), num(p.minPoint?.balance))
        ? record('5.10', 'СОШЛОСЬ', `баланс дня ${minDate} в траектории = минимуму ${money(point.balance)}`)
        : record('5.10', 'РАСХОЖДЕНИЕ', 'баланс дня минимума в траектории ≠ minPoint.balance',
          { траектория: num(point.balance), minPoint: num(p.minPoint?.balance) });
      const lowest = trj.reduce((m, x) => (num(x.balance) < num(m.balance) ? x : m));
      lowest.date === minDate
        ? record('5.10', 'СОШЛОСЬ', 'минимум траектории — действительно самая низкая её точка')
        : record('5.10', 'РАСХОЖДЕНИЕ', 'в траектории есть точка ниже объявленного минимума',
          { объявлен: minDate, ниже: lowest.date, значение: num(lowest.balance) });
    }
    record('5.10', 'РУКАМИ', 'выделен ли день минимума в календаре и открывает ли тап состав дня');
  }

  // ── 5.11 минимальное окно календаря ───────────────────────────────────────
  {
    const soon = shift(2);
    const inc = await must('/events', send('POST', {
      date: soon, categoryId: incCat.id, type: 'INCOME', plannedAmount: 40000, description: 'Близкий доход ANO-50',
    }));
    created.push(inc.id);
    const p = await pocketOf();
    const days = (p.trajectory ?? []).length;
    p.horizon.endDate === soon
      ? record('5.11', 'СОШЛОСЬ', `горизонт сжался до ${soon} — два дня`)
      : record('5.11', 'СМОТРЕТЬ', 'горизонт не встал на близкий доход', { горизонт: p.horizon.endDate, доход: soon });
    days >= 7
      ? record('5.11', 'СОШЛОСЬ', `траектория ${days} точек — информационный хвост на неделю есть (ANO-24)`)
      : record('5.11', 'РАСХОЖДЕНИЕ', `траектория ${days} точек при горизонте ${p.horizon.endDate} — календарю не из чего показать неделю`,
        { точек: days, горизонт: p.horizon.endDate, спека: 'ANO-24: не меньше 7 дней' });
    await del(inc.id);
    created.length = 0;
    const back = await pocketOf();
    eq(num(back.pocket), num(p0.pocket))
      ? record('5.11', 'СОШЛОСЬ', 'удаление близкого дохода вернуло кармашек')
      : record('5.11', 'СМОТРЕТЬ', 'кармашек не вернулся после удаления близкого дохода',
        { было: num(p0.pocket), стало: num(back.pocket) });
  }

  // ── 5.12 скоуп «до даты» ──────────────────────────────────────────────────
  {
    const target = addMonths(5, 15);
    const p = await pocketOf(`DATE:${target}`);
    const e = explainability(p);
    p.horizon.endDate === target
      ? record('5.12', 'СОШЛОСЬ', `горизонт встал на ${target}, подпись «${p.horizon.label}»`)
      : record('5.12', 'РАСХОЖДЕНИЕ', 'горизонт не равен запрошенной дате', { запрошено: target, горизонт: p.horizon.endDate });
    e.ok
      ? record('5.12', 'СОШЛОСЬ', `инвариант держится: минимум ${money(e.минимум)}, кармашек ${money(e.кармашек)}`)
      : record('5.12', 'РАСХОЖДЕНИЕ', 'инвариант объяснимости на скоупе «до даты» не держится', e);
  }

  // ── 5.13 границы скоупа ───────────────────────────────────────────────────
  {
    const cases = [
      ['DATE:2020-01-01', 'дата в далёком прошлом'],
      [`DATE:${shift(-1)}`, 'вчера'],
      ['MONTHS:40', '40 месяцев при максимуме 36'],
      ['MONTHS:0', 'ноль месяцев'],
      ['MONTHS:37', '37 месяцев — на единицу выше максимума'],
      ['MONTHS:abc', 'не число'],
      ['DATE:32.13.2026', 'не дата'],
      ['WEEKS:2', 'неизвестный тип'],
    ];
    const out = {};
    for (const [scope, what] of cases) {
      const r = await api(`/pocket?scope=${encodeURIComponent(scope)}`);
      out[scope] = { что: what, статус: r.status };
      if (r.status === 500) out[scope].тело = String(JSON.stringify(r.body)).slice(0, 120);
      if (r.status === 200) out[scope].горизонт = r.body.horizon?.endDate ?? null;
    }
    const fives = Object.entries(out).filter(([, v]) => v.статус >= 500);
    const twoHundreds = Object.entries(out).filter(([, v]) => v.статус === 200);
    fives.length
      ? record('5.13', 'РАСХОЖДЕНИЕ', `${fives.length} невалидных скоупов дают 500 вместо 400 (материал ANO-85)`, Object.fromEntries(fives))
      : record('5.13', 'СОШЛОСЬ', 'пятисоток на невалидных скоупах нет');
    twoHundreds.length
      ? record('5.13', 'СМОТРЕТЬ', `${twoHundreds.length} невалидных скоупов приняты молча: ${twoHundreds.map(([k]) => k).join(', ')}`,
        Object.fromEntries(twoHundreds))
      : record('5.13', 'СОШЛОСЬ', 'все невалидные скоупы отвергнуты');
    record('5.13', 'РУКАМИ', 'что видит человек: понятная ошибка на экране или пустой дашборд');
  }

  // ── 5.16 кармашек ушёл в минус ────────────────────────────────────────────
  {
    const before = await pocketOf();
    const dayIn = shift(4);
    const sink = await must('/events', send('POST', {
      date: dayIn, categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 200000, description: 'Утопить траекторию ANO-50',
    }));
    created.push(sink.id);
    const p = await pocketOf();
    num(p.pocket) < 0
      ? record('5.16', 'СОШЛОСЬ', `кармашек ушёл в минус: ${money(p.pocket)}`)
      : record('5.16', 'РАСХОЖДЕНИЕ', 'расход 200 000 не утопил кармашек', { было: num(before.pocket), стало: num(p.pocket) });
    // Минимум законно может быть позже дня провала: траектория продолжает падать после него.
    // Проверяем не равенство дат, а что провал попал в траекторию и минимум не раньше него.
    const sinkPoint = (p.trajectory ?? []).find((x) => x.date === dayIn);
    eq(num(sinkPoint?.expense), 200000)
      ? record('5.16', 'СОШЛОСЬ', `траектория провела расход 200 000 в день ${dayIn}`)
      : record('5.16', 'РАСХОЖДЕНИЕ', 'расход не виден в траектории днём провала', { точка: sinkPoint ?? null });
    p.minPoint?.date >= dayIn
      ? record('5.16', 'СОШЛОСЬ', `минимум ${p.minPoint.date} не раньше дня провала ${dayIn}`)
      : record('5.16', 'РАСХОЖДЕНИЕ', 'минимум раньше дня провала', { провал: dayIn, минимум: p.minPoint?.date ?? null });
    const minDayEvents = (await must(`/events?startDate=${p.minPoint?.date}&endDate=${p.minPoint?.date}`))
      .filter((e) => !e.deleted && e.type === 'EXPENSE');
    p.minPoint?.drivenBy
      ? record('5.16', 'СОШЛОСЬ', `виновник назван: «${p.minPoint.drivenBy}»`)
      : record('5.16', 'СМОТРЕТЬ', 'на отрицательном кармашке виновник не назван — человек видит минус без причины',
        { minPoint: p.minPoint, расходовВДеньМинимума: minDayEvents.length, описания: minDayEvents.map((e) => e.description) });
    const e = explainability(p);
    e.ok ? record('5.16', 'СОШЛОСЬ', 'на отрицательном кармашке инвариант объяснимости держится')
      : record('5.16', 'РАСХОЖДЕНИЕ', 'на отрицательном кармашке инвариант поехал', e);
    record('5.16', 'РУКАМИ', 'тон фразы, предупреждение о кассовом разрыве, красные дни в календаре, зоны риска в примерке');
    await del(sink.id);
    created.length = 0;
  }

  // ── 5.17 метаморфика: добавил и убрал ─────────────────────────────────────
  {
    const s1 = await six();
    const ev = await must('/events', send('POST', {
      date: shift(3), categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 10000, description: 'Метаморфика ANO-50',
    }));
    created.push(ev.id);
    const s2 = await six();
    await del(ev.id);
    created.length = 0;
    const s3 = await six();
    eq(s2.кармашек - s1.кармашек, -10000)
      ? record('5.17', 'СОШЛОСЬ', 'расход 10 000 внутри горизонта уронил кармашек ровно на 10 000')
      : record('5.17', 'РАСХОЖДЕНИЕ', 'кармашек сдвинулся не на 10 000',
        { сдвиг: s2.кармашек - s1.кармашек, до: s1.кармашек, после: s2.кармашек });
    const разошлись = diffSix(s1, s3);
    разошлись.length === 0
      ? record('5.17', 'СОШЛОСЬ', 'все шесть чисел вернулись к исходным до копейки')
      : record('5.17', 'РАСХОЖДЕНИЕ', 'после отмены система не вернулась в исходное состояние', { разошлись, до: s1, после: s3 });
  }

  // ── 5.19 разовый доход при постоянном расходе ─────────────────────────────
  {
    const ruleStart = addMonths(1, 25);
    // recurring.dayOfMonth обязателен и в DTO не помечен как обязательный — вторые грабли
    // после recurring.startDate, найденные на этапе 4.
    const rule = await api('/events', send('POST', {
      date: ruleStart, categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 3000, description: 'Абонемент ANO-50',
      recurring: { frequency: 'MONTHLY', interval: 1, startDate: ruleStart, dayOfMonth: 25 },
    }));
    const bonusDate = shift(6);
    const bonus = await must('/events', send('POST', {
      date: bonusDate, categoryId: sideCat.id, type: 'INCOME', plannedAmount: 50000, description: 'Премия ANO-50',
    }));
    created.push(bonus.id);
    if (!rule.ok) {
      record('5.19', 'СМОТРЕТЬ', `повторяющееся правило не создалось, ${rule.status}`, { тело: rule.body });
    } else {
      created.push(rule.body.id);
      const horizon6 = addMonths(7, 1);
      const all = (await must(`/events?startDate=${today}&endDate=${horizon6}`)).filter((e) => !e.deleted);
      const gym = all.filter((e) => (e.description ?? '').includes('Абонемент ANO-50'));
      const months = [...new Set(gym.map((e) => e.date.slice(0, 7)))].sort();
      months.length >= 6
        ? record('5.19', 'СОШЛОСЬ', `постоянный расход виден в ${months.length} месяцах: ${months.join(', ')}`)
        : record('5.19', 'РАСХОЖДЕНИЕ', `постоянный расход виден только в ${months.length} месяцах из шести`, { месяцы: months });
      const bonusHits = all.filter((e) => (e.description ?? '').includes('Премия ANO-50'));
      bonusHits.length === 1
        ? record('5.19', 'СОШЛОСЬ', 'разовый доход остался разовым — одна запись за полгода')
        : record('5.19', 'РАСХОЖДЕНИЕ', `разовый доход размножился: ${bonusHits.length} записей`, { даты: bonusHits.map((e) => e.date) });
    }
    const p = await pocketOf();
    p.horizon.endDate === bonusDate
      ? record('5.19', 'РАСХОЖДЕНИЕ', `горизонт съехал на разовую премию ${bonusDate}, хотя её категория «${sideCat.name}» не отмечена основным доходом`,
        { подпись: p.horizon.label, основнаяКатегория: incCat.name })
      : record('5.19', 'СОШЛОСЬ', `горизонт остался на ${p.horizon.endDate} — премия из побочной категории «${sideCat.name}» его не увела`);
    await sweep();
    created.length = 0;
  }

  // ── 5.20 трата, софинансируемая двумя доходами ────────────────────────────
  {
    const p2before = await pocketOf('MONTHS:2');
    const base2 = {
      строки: byType(p2before),
      потоки: flows(p2before),
      поДням: Object.fromEntries((p2before.trajectory ?? []).map((x) => [x.date, num(x.expense) ?? 0])),
    };
    const d1 = shift(5);
    const d2 = shift(20);
    const dExp = shift(19);
    const i1 = await must('/events', send('POST', { date: d1, categoryId: incCat.id, type: 'INCOME', plannedAmount: 30000, description: 'Аванс ANO-50' }));
    const i2 = await must('/events', send('POST', { date: d2, categoryId: incCat.id, type: 'INCOME', plannedAmount: 30000, description: 'Зарплата ANO-50' }));
    const ex = await must('/events', send('POST', { date: dExp, categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 60000, description: 'Покупка на два дохода ANO-50' }));
    created.push(i1.id, i2.id, ex.id);

    const primaryIds = new Set(cats.filter((c) => c.type === 'INCOME' && isPrimary(c)).map((c) => c.id));
    const primaryDates = [...new Set((await must(`/events?startDate=${today}&endDate=${addMonths(4)}`))
      .filter((e) => !e.deleted && e.type === 'INCOME' && e.date > today && primaryIds.has(e.categoryId))
      .map((e) => e.date))].sort();
    const pFirst = await pocketOf('NEXT_INCOME');
    const pSecond = await pocketOf('SECOND_INCOME');
    pFirst.horizon.endDate === primaryDates[0]
      ? record('5.20', 'СОШЛОСЬ', `«до дохода» встал на первую выплату ${primaryDates[0]}`)
      : record('5.20', 'РАСХОЖДЕНИЕ', 'горизонт «до дохода» не встал на первую выплату',
        { горизонт: pFirst.horizon.endDate, перваяВыплата: primaryDates[0] });
    pSecond.horizon.endDate === primaryDates[1]
      ? record('5.20', 'СОШЛОСЬ', `«до второго дохода» накрывает обе выплаты, конец ${primaryDates[1]}`)
      : record('5.20', 'РАСХОЖДЕНИЕ', 'скоуп «до второго дохода» не встал на вторую выплату',
        { горизонт: pSecond.horizon.endDate, выплаты: primaryDates.slice(0, 3), фолбэк: pSecond.horizon.fallback, подпись: pSecond.horizon.label });

    // Расход и доходы учтены ровно один раз — по дельте потоков траектории до и после.
    const now2 = flows(await pocketOf('MONTHS:2'));
    const dExpFlow = now2.расход - base2.потоки.расход;
    const dIncFlow = now2.доход - base2.потоки.доход;
    eq(dExpFlow, 60000)
      ? record('5.20', 'СОШЛОСЬ', 'покупка 60 000 добавила в траекторию ровно 60 000 расхода')
      : record('5.20', 'РАСХОЖДЕНИЕ', 'расход учтён не один раз', { дельтаРасхода: dExpFlow, ожидалось: 60000 });
    eq(dIncFlow, 60000)
      ? record('5.20', 'СОШЛОСЬ', 'обе выплаты по 30 000 добавили в траекторию ровно 60 000 дохода')
      : record('5.20', 'РАСХОЖДЕНИЕ', 'доходы учтены не полностью', { дельтаДохода: dIncFlow, ожидалось: 60000 });
    const trj2 = (await pocketOf('MONTHS:2')).trajectory ?? [];
    const atExp = trj2.find((x) => x.date === dExp);
    eq(num(atExp?.expense) - (base2.поДням[dExp] ?? 0), 60000)
      ? record('5.20', 'СОШЛОСЬ', 'покупка проведена в свой день ровно один раз')
      : record('5.20', 'РАСХОЖДЕНИЕ', 'в день покупки траектория показывает не тот расход',
        { деньПокупки: dExp, было: base2.поДням[dExp] ?? 0, стало: num(atExp?.expense) });

    // Разбивка считается до даты минимума, а не до конца скоупа. Инвариант объяснимости от
    // этого не страдает, но на длинном скоупе человек видит строки за девять дней из шестидесяти.
    const nowCut = byType(await pocketOf('MONTHS:2')).PLANNED_EXPENSES ?? 0;
    record('5.20', 'СМОТРЕТЬ',
      'добавление расхода на 60 000 изменило строку «Плановые расходы» на скоупе двух месяцев в обратную сторону — строки режутся по дате минимума',
      { былоПлановыхРасходов: base2.строки.PLANNED_EXPENSES ?? 0, стало: nowCut, разница: nowCut - (base2.строки.PLANNED_EXPENSES ?? 0) });
    await sweep();
    created.length = 0;
  }

  record('5.14', 'РУКАМИ', 'фолбэк горизонта — в ano50-stage5-clean.mjs, нужен стенд без плановых доходов');
  record('5.15', 'РУКАМИ', 'кармашек на пустых данных — в ano50-stage5-clean.mjs');
  record('5.18', 'РУКАМИ', 'сценарий провала на фиксированных данных — в ano50-stage5-clean.mjs');
  record('5.21', 'РУКАМИ', 'вход в середине месяца — в ano50-stage5-clean.mjs');
  record('5.22', 'РУКАМИ', 'свободное исследование дашборда, сорок минут');

  // ── 5.8 деструктивный: убрать планки и вклад ──────────────────────────────
  if (process.argv.includes('--with-5-8')) {
    const accounts = await must('/accounts');
    for (const a of accounts.filter((x) => x.kind === 'CREDIT' && x.availableFloor != null)) {
      await must(`/accounts/${a.id}`, put({
        name: a.name, kind: a.kind, trackBalance: a.trackBalance, purposeCategoryId: a.purposeCategoryId,
        creditLimit: a.creditLimit, availableFloor: null, sortOrder: a.sortOrder,
      }));
    }
    const pNoFloor = await pocketOf();
    pNoFloor.pocketAfterCreditRestore === null && byType(pNoFloor).CREDIT_RESTORE === undefined
      ? record('5.8', 'СОШЛОСЬ', 'без планок второе число и строка CREDIT_RESTORE исчезли')
      : record('5.8', 'РАСХОЖДЕНИЕ', 'планок нет, а второе число или строка остались',
        { второеЧисло: num(pNoFloor.pocketAfterCreditRestore), строка: byType(pNoFloor).CREDIT_RESTORE ?? null });

    // Удалить вклад нельзя, пока на нём живёт копилка: 409 с внятным «unlink it first».
    // Поэтому «вкладов нет» воспроизводится обнулением якорем — полу-ликвид становится нулём.
    const deposits = (await must('/accounts')).filter((a) => a.kind === 'DEPOSIT');
    const путь = [];
    for (const d of deposits) {
      const drop = await api(`/accounts/${d.id}`, { method: 'DELETE' });
      if (drop.ok) { путь.push({ имя: d.name, как: 'удалён' }); continue; }
      const zero = await api('/balance-checkpoints', send('POST', { date: today, amount: 0, accountId: d.id }));
      путь.push({ имя: d.name, удаление: drop.status, причина: drop.body?.message ?? null, обнуление: zero.status });
    }
    const pNoDep = await pocketOf();
    pNoDep.pocketWithDeposits === null
      ? record('5.8', 'СОШЛОСЬ', 'когда полу-ликвида нет, третье число исчезает', { путь })
      : record('5.8', 'РАСХОЖДЕНИЕ', 'вкладов нет, а третье число осталось',
        { третье: num(pNoDep.pocketWithDeposits), путь });
    record('5.8', 'РУКАМИ', 'исчезли ли оба числа с экрана или остались как «—»');
    console.log('\n  ВНИМАНИЕ: блок 5.8 деструктивен, стенд восстанавливается из ano50-before-stage5-2026-09-05.sql\n');
  } else {
    record('5.8', 'РУКАМИ', 'запустить с --with-5-8: блок удаляет планки и вклад, после него стенд восстанавливается из дампа');
  }

  // ── уборка ────────────────────────────────────────────────────────────────
  await sweep();
  await must('/settings/pocket', put({ bufferAmount: settings0.bufferAmount ?? 0 }));
  const rest = await sweep();

  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  const look = log.filter((r) => r.verdict === 'СМОТРЕТЬ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Расхождений: ${bad.length}   Требует глаз: ${look.length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  if (look.length) {
    console.log('\nТРЕБУЕТ ГЛАЗ:');
    look.forEach((r) => console.log(`  ${r.block}: ${r.message}`));
  }
  console.log(`\nуборка: осталось событий с меткой ANO-50 — ${rest}`);
  const fin = await pocketOf();
  console.log(`кармашек на выходе: ${money(fin.pocket)} (на входе ${money(p0.pocket)})\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });
