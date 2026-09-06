#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапу 8 — хотелки и примерка.
 *
 * Здесь висят оба симптома владельца от 15 августа: ANO-48 «хотелка не переводится в план»
 * и ANO-49 «примерка работает не так». Обе задачи — сообщения о симптоме без диагноза,
 * задача прохода: воспроизвести и уточнить формулировку.
 *
 * Три двери в одну комнату — статус хотелки меняют три эндпоинта:
 *   POST  /wishlist/items/{id}/fix          — «зафиксировать» из примерки, переносит параметры
 *   POST  /wishlist/items/{id}/convert      — конверсия в PLAN_EVENT | FUND | FUND_WITH_CREDIT
 *   PATCH /events/{id}/wishlist-status      — голая смена статуса (второй путь, ANO-38)
 *
 *   node tools/ano50-stage8.mjs        # прогон, МЕНЯЕТ ДАННЫЕ и убирает за собой
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
const addMonths = (n, day) => {
  const d = new Date();
  if (day) d.setDate(1);
  d.setMonth(d.getMonth() + n);
  if (day) d.setDate(day);
  return iso(d);
};
const MARK = 'ANO-50';

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  ->  ', 'НЕЛЬЗЯ': '  x  ' }[verdict];
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
  if (!r.ok) throw new Error(`${p} -> ${r.status}: ${JSON.stringify(r.body).slice(0, 300)}`);
  return r.body;
}
const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});
const patch = (body) => ({ method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

const sim = (h = 12) => must(`/wishlist/simulation?horizonMonths=${h}`);
const pocketOf = (scope) => must(`/pocket${scope ? `?scope=${encodeURIComponent(scope)}` : ''}`);
const byType = (p) => Object.fromEntries((p.breakdown ?? []).map((l) => [l.type, num(l.amount)]));
const itemById = (s, id) => (s.items ?? []).find((i) => i.id === id);
const itemByName = (s, name) => (s.items ?? []).find((i) => (i.name ?? '').includes(name));

/** Сумма дельты по счёту за весь горизонт — «во сколько обойдётся» одной цифрой. */
const deltaSum = (d) => (d ?? []).reduce((s, m) => s + (num(m.accountDelta) ?? 0), 0);

/** Снимок объёмов: сколько событий, копилок, правил. Для проверок «ничего не создалось». */
async function volumes() {
  const [events, funds] = await Promise.all([
    must(`/events?startDate=2020-01-01&endDate=2051-12-31`),
    must('/funds').then((r) => r.funds ?? r),
  ]);
  const live = events.filter((e) => !e.deleted);
  return {
    событий: live.length,
    копилок: funds.filter((f) => !f.deleted).length,
    правил: new Set(live.map((e) => e.recurringRuleId).filter(Boolean)).size,
  };
}

async function createWish(description, amount, date) {
  return must('/events/wishlist', send('POST', { description, plannedAmount: amount, date }));
}

async function main() {
  console.log(`\nANO-50 · первый проход, этап 8 — хотелки и примерка · ${today}`);
  console.log('='.repeat(78));

  const madeEvents = [];
  const madeFunds = [];
  const p0 = await pocketOf();
  const v0 = await volumes();
  const s0 = await sim();
  console.log(`старт: кармашек ${money(p0.pocket)}, хотелок ${s0.items.length}, `
    + `событий ${v0.событий}, копилок ${v0.копилок}\n`);

  // ── 8.1 создать хотелку ───────────────────────────────────────────────────
  const laptopDate = addMonths(1, 15);
  let laptop;
  {
    const infoBefore = byType(p0).WISHLIST_INFO ?? 0;
    laptop = await createWish(`Ноутбук ${MARK}`, 180000, laptopDate);
    madeEvents.push(laptop.id);
    const p = await pocketOf();
    const s = await sim();
    const it = itemById(s, laptop.id);

    it && it.status === 'OPEN' && it.kind === 'WISHLIST'
      ? record('8.1', 'СОШЛОСЬ', `хотелка создана со статусом «обсуждается», сумма ${money(it.amount)}`)
      : record('8.1', 'РАСХОЖДЕНИЕ', 'хотелка не появилась в примерке или не в статусе OPEN', { item: it ?? null });
    eq(num(p.pocket), num(p0.pocket))
      ? record('8.1', 'СОШЛОСЬ', 'кармашек не изменился — намерение не есть трата')
      : record('8.1', 'РАСХОЖДЕНИЕ', 'создание хотелки сдвинуло кармашек',
        { было: num(p0.pocket), стало: num(p.pocket), сдвиг: num(p.pocket) - num(p0.pocket) });
    const infoAfter = byType(p).WISHLIST_INFO ?? 0;
    eq(infoAfter - infoBefore, 180000)
      ? record('8.1', 'СОШЛОСЬ', 'строка кандидатов выросла ровно на 180 000 и осталась информационной')
      : record('8.1', 'СМОТРЕТЬ', 'строка кандидатов сдвинулась не на 180 000',
        { было: infoBefore, стало: infoAfter, сдвиг: infoAfter - infoBefore });
  }

  // ── 8.2 примерка ползунком ────────────────────────────────────────────────
  {
    const vBefore = await volumes();
    const steps = [100000, 175000, 250000];
    const got = [];
    for (const amount of steps) {
      const r = await must('/wishlist/simulation/recompute', send('POST', {
        kind: 'WISHLIST', amount, targetDate: laptopDate,
      }));
      got.push({ сумма: amount, дельта: deltaSum(r.delta), точек: (r.delta ?? []).length });
    }
    const monotone = got.every((g, i) => i === 0 || g.дельта <= got[i - 1].дельта + EPS);
    monotone
      ? record('8.2', 'СОШЛОСЬ', `больше сумма — глубже провал: ${got.map((g) => money(g.дельта)).join(' -> ')}`)
      : record('8.2', 'РАСХОЖДЕНИЕ', 'монотонность нарушена: больше сумма, а картина не хуже', { шаги: got });
    got.every((g) => eq(g.дельта, -g.сумма))
      ? record('8.2', 'СОШЛОСЬ', 'разовая покупка вычитается ровно один раз, на всю сумму')
      : record('8.2', 'СМОТРЕТЬ', 'дельта разовой покупки не равна её сумме', { шаги: got });

    const vAfter = await volumes();
    JSON.stringify(vBefore) === JSON.stringify(vAfter)
      ? record('8.2', 'СОШЛОСЬ', 'примерка ничего не записала: события, копилки и правила не изменились')
      : record('8.2', 'РАСХОЖДЕНИЕ', 'примерка изменила базу — это черновик, он писать не должен',
        { до: vBefore, после: vAfter });
    const itAfter = itemById(await sim(), laptop.id);
    eq(num(itAfter?.amount), 180000)
      ? record('8.2', 'СОШЛОСЬ', 'сумма самой хотелки от движения ползунка не поехала')
      : record('8.2', 'РАСХОЖДЕНИЕ', 'ползунок изменил сохранённую сумму хотелки', { сумма: num(itAfter?.amount) });
    record('8.2', 'РУКАМИ', 'мгновенность отклика графика и поведение зон риска — только на экране');
  }

  // ── 8.3 воспроизвести ANO-49: базовая линия примерки против реальности ────
  {
    const s = await sim(12);
    const strategy = await must('/strategy/timeline');
    const simPoints = s.baseline?.points ?? [];
    const strPoints = strategy.points ?? [];
    const byMonth = Object.fromEntries(strPoints.map((p) => [p.yearMonth, num(p.balance)]));
    const mismatched = simPoints
      .filter((p) => byMonth[p.yearMonth] !== undefined && !eq(num(p.balance), byMonth[p.yearMonth]))
      .map((p) => ({ месяц: p.yearMonth, примерка: num(p.balance), стратегия: byMonth[p.yearMonth] }));
    mismatched.length === 0
      ? record('8.3', 'СОШЛОСЬ', `базовая линия примерки совпадает со стратегией во всех ${simPoints.length} месяцах`)
      : record('8.3', 'РАСХОЖДЕНИЕ', `базовая линия примерки расходится со стратегией в ${mismatched.length} месяцах`,
        { первые: mismatched.slice(0, 4) });

    // Вторая примерка — окно «что если» на кармашке. При пустом входе обязано равняться кармашку.
    const sandbox = await api('/pocket/sandbox', send('POST', { items: [] }));
    if (!sandbox.ok) {
      record('8.3', 'СМОТРЕТЬ', `примерка кармашка с пустым входом отвечает ${sandbox.status}`, { тело: sandbox.body });
    } else {
      const real = await pocketOf();
      const base = num(sandbox.body.baseline?.pocket);
      const fitted = num(sandbox.body.fitted?.pocket);
      eq(base, num(real.pocket))
        ? record('8.3', 'СОШЛОСЬ', `базовая линия примерки кармашка равна реальности: ${money(base)}`)
        : record('8.3', 'РАСХОЖДЕНИЕ', 'базовая линия примерки не равна обычному кармашку',
          { примерка: base, кармашек: num(real.pocket) });
      eq(fitted, base)
        ? record('8.3', 'СОШЛОСЬ', 'примерка с пустым входом ничего не меняет: примеренное равно базовому')
        : record('8.3', 'РАСХОЖДЕНИЕ', 'пустая примерка сдвинула ответ',
          { базовое: base, примеренное: fitted });
    }

    // Третья дверь того же вопроса: ограничения примерки против кармашка.
    const c = s.constraints ?? {};
    record('8.3', 'СМОТРЕТЬ', 'ограничения примерки — сверить с кармашком глазами',
      { кармашек: num((await pocketOf()).pocket), ограничения: c });
  }

  // ── 8.4 зафиксировать хотелку ─────────────────────────────────────────────
  const fixedDate = addMonths(2, 10);
  {
    const r = await api(`/wishlist/items/${laptop.id}/fix`, send('POST', {
      sourceKind: 'WISHLIST', amount: 165000, date: fixedDate, stretchMonths: 0,
    }));
    if (!r.ok) {
      record('8.4', 'РАСХОЖДЕНИЕ', `фиксация упала с ${r.status}`, { тело: r.body });
    } else {
      const it = itemById(await sim(), laptop.id);
      it?.status === 'FIXED'
        ? record('8.4', 'СОШЛОСЬ', 'статус стал «зафиксировано»')
        : record('8.4', 'РАСХОЖДЕНИЕ', 'после фиксации статус не FIXED', { статус: it?.status ?? null });
      eq(num(it?.amount), 165000) && it?.targetDate === fixedDate
        ? record('8.4', 'СОШЛОСЬ', `параметры примерки перенесены: ${money(it.amount)} на ${it.targetDate} (регресс ANO-34 не воспроизводится)`)
        : record('8.4', 'РАСХОЖДЕНИЕ', 'фиксация потеряла подкрученные параметры — регресс ANO-34',
          { ждали: { сумма: 165000, дата: fixedDate }, получили: { сумма: num(it?.amount), дата: it?.targetDate } });
      record('8.4', 'СМОТРЕТЬ', 'ответ фиксации целиком', { ответ: r.body });
    }
  }

  // ── 8.5 второй путь фиксации (ANO-38) ─────────────────────────────────────
  {
    const twin = await createWish(`Двойник для второго пути ${MARK}`, 165000, laptopDate);
    madeEvents.push(twin.id);
    // Путь CapitalWhatIf: updateEvent + setEventWishlistStatus, мимо /fix.
    const upd = await api(`/events/${twin.id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        date: fixedDate, categoryId: twin.categoryId, type: 'EXPENSE',
        plannedAmount: 165000, description: `Двойник для второго пути ${MARK}`, priority: 'LOW',
      }),
    });
    const st = await api(`/events/${twin.id}/wishlist-status`, patch({ status: 'FIXED' }));
    const s = await sim();
    const a = itemById(s, laptop.id);
    const b = itemById(s, twin.id);
    const same = a && b && a.status === b.status && eq(num(a.amount), num(b.amount)) && a.targetDate === b.targetDate;
    same
      ? record('8.5', 'СОШЛОСЬ', 'оба пути фиксации дали одинаковое состояние хотелки')
      : record('8.5', 'РАСХОЖДЕНИЕ', 'пути фиксации разошлись — подтверждение ANO-38',
        { через_fix: a ? { статус: a.status, сумма: num(a.amount), дата: a.targetDate } : null,
          через_put_patch: b ? { статус: b.status, сумма: num(b.amount), дата: b.targetDate } : null,
          put: upd.status, patch: st.status });
  }

  // ── 8.6 и 8.7 конверсия в плановое событие, воспроизведение ANO-48 ────────
  {
    const vBefore = await volumes();
    // Скоуп, накрывающий дату хотелки: разбивка режется по дате минимума, поэтому
    // сравнивать надо конец траектории, а не строку «плановые расходы».
    const coverScope = `DATE:${addMonths(4, 1)}`;
    const tail = (p) => num((p.trajectory ?? [])[(p.trajectory ?? []).length - 1]?.balance);
    const before3 = byType(await pocketOf('MONTHS:3'));
    const tailBefore = tail(await pocketOf(coverScope));
    const r = await api(`/wishlist/items/${laptop.id}/convert`, send('POST', {
      sourceKind: 'WISHLIST', target: 'PLAN_EVENT',
    }));
    if (!r.ok) {
      record('8.6', 'РАСХОЖДЕНИЕ', `конверсия в план упала с ${r.status}`, { тело: r.body });
      record('8.7', 'РАСХОЖДЕНИЕ', 'ANO-48 воспроизведён: хотелка не переводится в план',
        { статус: r.status, ответ: r.body });
    } else {
      const conv = r.body;
      const it = itemById(await sim(), laptop.id);
      const vAfter = await volumes();
      const created = conv.convertedTo?.id
        ? (await must(`/events?startDate=2020-01-01&endDate=2051-12-31`)).find((e) => e.id === conv.convertedTo.id && !e.deleted)
        : null;
      if (created) madeEvents.push(created.id);

      // Приоритет остаётся LOW осознанно, а в траекторию событие пускает wishlistStatus = null
      // (PocketEngine.allowedInTrajectory). Проверяем именно это, а не приоритет.
      created && created.eventKind === 'PLAN' && created.status === 'PLANNED'
        ? record('8.6', 'СОШЛОСЬ', `в журнале появилось плановое событие ${money(created.plannedAmount)} на ${created.date}`)
        : record('8.6', 'РАСХОЖДЕНИЕ', 'событие после конверсии не найдено или создано не планом',
          { convertedTo: conv.convertedTo, событие: created ? { kind: created.eventKind, статус: created.status } : null });
      it?.convertedTo?.id === conv.convertedTo?.id
        ? record('8.6', 'СОШЛОСЬ', 'хотелка помечена сконвертированной и ссылается на созданное событие')
        : record('8.6', 'РАСХОЖДЕНИЕ', 'ссылка на артефакт не проставилась', { item: it?.convertedTo ?? null });
      itemById(await sim(), conv.convertedTo?.id)
        ? record('8.6', 'РАСХОЖДЕНИЕ', 'созданное событие само попало в список хотелок — задвоение в примерке')
        : record('8.6', 'СОШЛОСЬ', 'созданное событие не вернулось в список хотелок');

      // Главная проверка на задвоение: зафиксированная датированная хотелка уже была в
      // траектории, конверсия лишь переносит её на другую строку. Хвост обязан не двинуться.
      const tailAfter = tail(await pocketOf(coverScope));
      eq(tailAfter, tailBefore)
        ? record('8.6', 'СОШЛОСЬ', `задвоения нет: хвост траектории не сдвинулся (${money(tailAfter)})`)
        : record('8.6', 'РАСХОЖДЕНИЕ', 'конверсия сдвинула траекторию — расход учтён дважды или потерян',
          { до: tailBefore, после: tailAfter, сдвиг: tailAfter - tailBefore, суммаХотелки: 165000 });
      const after3 = byType(await pocketOf('MONTHS:3'));
      record('8.6', 'СМОТРЕТЬ', 'плановые расходы в разбивке до и после конверсии',
        { до: before3.PLANNED_EXPENSES ?? null, после: after3.PLANNED_EXPENSES ?? null });
      vAfter.событий - vBefore.событий === 1
        ? record('8.6', 'СОШЛОСЬ', 'создано ровно одно событие')
        : record('8.6', 'СМОТРЕТЬ', 'число событий изменилось не на единицу', { до: vBefore, после: vAfter });
      record('8.7', 'СОШЛОСЬ', 'ANO-48 по API не воспроизводится: путь «хотелка -> план» отрабатывает целиком',
        { артефакт: conv.artifactKind, статус: conv.newStatus });
      record('8.7', 'РУКАМИ', 'пройти тот же путь кнопками: симптом может быть в кнопке, а не в эндпоинте');
    }
  }

  // ── 8.10 повторная конверсия ──────────────────────────────────────────────
  {
    const vBefore = await volumes();
    const r = await api(`/wishlist/items/${laptop.id}/convert`, send('POST', {
      sourceKind: 'WISHLIST', target: 'PLAN_EVENT',
    }));
    const vAfter = await volumes();
    r.status === 409
      ? record('8.10', 'СОШЛОСЬ', 'повторная конверсия отвергнута с 409')
      : record('8.10', 'РАСХОЖДЕНИЕ', `повторная конверсия ответила ${r.status}, а не 409`, { тело: r.body });
    JSON.stringify(vBefore) === JSON.stringify(vAfter)
      ? record('8.10', 'СОШЛОСЬ', 'вторая копилка и второе событие не появились')
      : record('8.10', 'РАСХОЖДЕНИЕ', 'повтор что-то создал', { до: vBefore, после: vAfter });
  }

  // ── 8.11 вернуть из зафиксированного в обсуждение ─────────────────────────
  {
    const before = itemById(await sim(), laptop.id);
    const r = await api(`/events/${laptop.id}/wishlist-status`, patch({ status: 'OPEN' }));
    const after = itemById(await sim(), laptop.id);
    r.ok
      ? record('8.11', 'СОШЛОСЬ', 'путь назад существует, статус сменился')
      : record('8.11', 'РАСХОЖДЕНИЕ',
        `возврат сконвертированной хотелки в обсуждение упал с ${r.status}: ограничение схемы `
        + 'chk_event_converted_only_fixed требует у сконвертированной строки статус FIXED',
        { тело: r.body, ограничение: "CHECK (converted_to_* IS NULL OR wishlist_status = 'FIXED')" });
    // Тем же ограничением накрыто и отклонение: у сконвертированной строки нет НИ ОДНОГО
    // разрешённого перехода, кроме остаться FIXED.
    const dismissConverted = await api(`/events/${laptop.id}/wishlist-status`, patch({ status: 'DISMISSED' }));
    dismissConverted.ok
      ? record('8.11', 'СОШЛОСЬ', 'сконвертированную хотелку можно отклонить')
      : record('8.11', 'РАСХОЖДЕНИЕ',
        `отклонить сконвертированную хотелку тоже нельзя, ${dismissConverted.status} — из FIXED нет выхода вообще`,
        { статус: dismissConverted.status });
    after?.status === 'OPEN'
      ? record('8.11', 'СОШЛОСЬ', 'статус стал «обсуждается»')
      : record('8.11', 'РАСХОЖДЕНИЕ', 'статус не вернулся в OPEN', { статус: after?.status ?? null });
    after?.convertedTo?.id === before?.convertedTo?.id && after?.convertedTo?.id
      ? record('8.11', 'СОШЛОСЬ', 'ссылка на созданное событие сохранилась — как решено в спеке')
      : record('8.11', 'РАСХОЖДЕНИЕ', 'возврат в обсуждение потерял ссылку на артефакт',
        { было: before?.convertedTo ?? null, стало: after?.convertedTo ?? null });
    const stillThere = (await must(`/events?startDate=2020-01-01&endDate=2051-12-31`))
      .some((e) => e.id === before?.convertedTo?.id && !e.deleted);
    stillThere
      ? record('8.11', 'СОШЛОСЬ', 'созданное событие на месте, возврат его не удалил')
      : record('8.11', 'РАСХОЖДЕНИЕ', 'возврат в обсуждение уничтожил созданное событие');
  }

  // ── 8.8 и 8.9 конверсия в копилку, с правилом и без ───────────────────────
  {
    const wishFund = await createWish(`Отпуск копилкой ${MARK}`, 120000, addMonths(6, 1));
    madeEvents.push(wishFund.id);
    const vBefore = await volumes();
    const r = await api(`/wishlist/items/${wishFund.id}/convert`, send('POST', {
      sourceKind: 'WISHLIST', target: 'FUND', createRecurringPayments: false,
    }));
    if (!r.ok) {
      record('8.8', 'РАСХОЖДЕНИЕ', `конверсия в копилку упала с ${r.status}`, { тело: r.body });
    } else {
      const fundId = r.body.convertedTo?.id;
      if (fundId) madeFunds.push(fundId);
      const fund = (await must('/funds').then((r) => r.funds ?? r)).find((f) => f.id === fundId);
      const vAfter = await volumes();
      fund && eq(num(fund.targetAmount ?? fund.target), 120000)
        ? record('8.8', 'СОШЛОСЬ', `копилка создана с целью ${money(fund.targetAmount ?? fund.target)}`)
        : record('8.8', 'РАСХОЖДЕНИЕ', 'цель копилки не равна сумме хотелки',
          { копилка: fund ? { цель: num(fund.targetAmount ?? fund.target), имя: fund.name } : null, хотелка: 120000 });
      vAfter.копилок - vBefore.копилок === 1
        ? record('8.8', 'СОШЛОСЬ', 'создана ровно одна копилка')
        : record('8.8', 'СМОТРЕТЬ', 'число копилок изменилось не на единицу', { до: vBefore, после: vAfter });
    }

    const wishRule = await createWish(`Отпуск со взносами ${MARK}`, 120000, addMonths(6, 1));
    madeEvents.push(wishRule.id);
    const v2Before = await volumes();
    const r2 = await api(`/wishlist/items/${wishRule.id}/convert`, send('POST', {
      sourceKind: 'WISHLIST', target: 'FUND', createRecurringPayments: true,
    }));
    if (!r2.ok) {
      record('8.9', 'РАСХОЖДЕНИЕ', `конверсия с правилом взносов упала с ${r2.status}`, { тело: r2.body });
    } else {
      if (r2.body.convertedTo?.id) madeFunds.push(r2.body.convertedTo.id);
      const v2After = await volumes();
      r2.body.recurringRuleId
        ? record('8.9', 'СОШЛОСЬ', 'копилка и правило взносов созданы одной операцией')
        : record('8.9', 'РАСХОЖДЕНИЕ',
          'флаг createRecurringPayments принят с кодом 200 и молча проигнорирован: '
          + 'в WishlistConversionService он читается только в ветке FUND_WITH_CREDIT, у копилки ветки нет',
          { ответ: r2.body, запрошено: { target: 'FUND', createRecurringPayments: true } });
      v2After.правил - v2Before.правил === 1
        ? record('8.9', 'СОШЛОСЬ', 'появилось ровно одно новое правило')
        : record('8.9', 'СМОТРЕТЬ', 'число правил изменилось не на единицу', { до: v2Before, после: v2After });
      // Задвоение взносов: и как правило, и как строка взносов копилки.
      const t = byType(await pocketOf('MONTHS:6'));
      record('8.9', 'СМОТРЕТЬ', 'взносы в кармашке на шести месяцах — проверить, не считаются ли дважды',
        { строкаВзносов: t.SAVINGS_CONTRIBUTIONS ?? null, плановыеРасходы: t.PLANNED_EXPENSES ?? null });
    }
  }

  // ── 8.12 отклонить хотелку ────────────────────────────────────────────────
  {
    const doomed = await createWish(`Отклоняемая ${MARK}`, 99000, addMonths(3, 1));
    madeEvents.push(doomed.id);
    const withIt = await pocketOf();
    const infoWith = byType(withIt).WISHLIST_INFO ?? 0;
    const r = await api(`/events/${doomed.id}/wishlist-status`, patch({ status: 'DISMISSED' }));
    const s = await sim();
    const after = await pocketOf();
    const infoAfter = byType(after).WISHLIST_INFO ?? 0;

    r.ok ? record('8.12', 'СОШЛОСЬ', 'отклонение принято')
      : record('8.12', 'РАСХОЖДЕНИЕ', `отклонение упало с ${r.status}`, { тело: r.body });
    itemById(s, doomed.id)
      ? record('8.12', 'РАСХОЖДЕНИЕ', 'отклонённая хотелка осталась в активном списке примерки')
      : record('8.12', 'СОШЛОСЬ', 'отклонённая ушла из активного списка');
    const stillInDb = (await must(`/events?startDate=2020-01-01&endDate=2051-12-31`))
      .some((e) => e.id === doomed.id && !e.deleted);
    stillInDb
      ? record('8.12', 'СОШЛОСЬ', 'запись не исчезла бесследно — осталась в журнале')
      : record('8.12', 'СМОТРЕТЬ', 'отклонённая хотелка пропала из журнала совсем');
    eq(infoWith - infoAfter, 99000)
      ? record('8.12', 'СОШЛОСЬ', 'строка кандидатов уменьшилась ровно на сумму отклонённой')
      : record('8.12', 'СМОТРЕТЬ', 'строка кандидатов изменилась не на 99 000',
        { было: infoWith, стало: infoAfter });
  }

  // ── 8.13 хотелка-кредит ───────────────────────────────────────────────────
  {
    const P = 180000; const rate = 24; const n = 12;
    const r = await api('/wishlist/simulation/recompute', send('POST', {
      kind: 'CREDIT', amount: P, targetDate: addMonths(1, 1), rate, termMonths: n,
    }));
    if (!r.ok) {
      record('8.13', 'СМОТРЕТЬ', `примерка кредита ответила ${r.status}`, { тело: r.body });
    } else {
      const pmt = num(r.body.monthlyPMT);
      const i = rate / 100 / 12;
      const expected = P * i / (1 - Math.pow(1 + i, -n));
      const asFraction = (() => { const j = rate / 12; return P * j / (1 - Math.pow(1 + j, -n)); })();
      if (eq(Math.round(pmt), Math.round(expected))) {
        record('8.13', 'СОШЛОСЬ', `аннуитет сходится с формулой: ${money(pmt)} при ${rate}% годовых на ${n} мес`);
      } else if (Math.abs(pmt - asFraction) < 1) {
        record('8.13', 'РАСХОЖДЕНИЕ', 'ставка трактуется как доля, а не как проценты годовых',
          { платёжПродукта: pmt, приСтавкеВПроцентах: Math.round(expected), приСтавкеВДолях: Math.round(asFraction) });
      } else {
        record('8.13', 'РАСХОЖДЕНИЕ', 'аннуитетный платёж не сходится с формулой',
          { платёжПродукта: pmt, поФормуле: Math.round(expected), ставка: rate, срок: n, сумма: P });
      }
      const total = deltaSum(r.body.delta);
      record('8.13', 'СМОТРЕТЬ', 'нагрузка кредита по горизонту — проверить равномерность глазами',
        { суммарнаяДельта: Math.round(total), платёж: pmt, месяцев: (r.body.delta ?? []).length });
      const c = (await sim()).constraints ?? {};
      record('8.13', 'СМОТРЕТЬ', 'потолок кредита в ограничениях (вклад в него по ANO-46 не входит)',
        { потолокКредита: c.maxCreditAmount ?? null, потолокХотелки: c.maxWishlistAmount ?? null });
    }
  }

  record('8.14', 'РУКАМИ', 'свободное исследование хотелок, сорок минут: разница «примерить» и «зафиксировать», край сумм и дат');

  // ── уборка ────────────────────────────────────────────────────────────────
  for (const id of madeFunds) await api(`/funds/${id}`, { method: 'DELETE' });
  const all = await must(`/events?startDate=2020-01-01&endDate=2051-12-31`);
  for (const e of all.filter((x) => !x.deleted && (x.description ?? '').includes(MARK))) {
    const plain = await api(`/events/${e.id}`, { method: 'DELETE' });
    if (!plain.ok) await api(`/events/${e.id}?scope=ALL`, { method: 'DELETE' });
  }
  const rest = (await must(`/events?startDate=2020-01-01&endDate=2051-12-31`))
    .filter((e) => !e.deleted && (e.description ?? '').includes(MARK));
  const v1 = await volumes();

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
    look.forEach((r) => console.log(`  ${r.block}: ${r.message}  ${r.data ? JSON.stringify(r.data) : ''}`));
  }
  console.log(`\nуборка: осталось с меткой ${MARK} — ${rest.length}`);
  console.log(`объёмы: было ${JSON.stringify(v0)}, стало ${JSON.stringify(v1)}`);
  const fin = await pocketOf();
  console.log(`кармашек на выходе: ${money(fin.pocket)} (на входе ${money(p0.pocket)})\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });
