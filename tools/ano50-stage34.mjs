#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапам 3 и 4 — журнал и повторяющиеся правила.
 *
 * Ходит по API. Блоки, где суть в интерфейсе (калькулятор в поле суммы,
 * управляемость длинной цепочки), помечает «руками» и не притворяется, что их закрыл.
 *
 *   node tools/ano50-stage34.mjs        # прогон, МЕНЯЕТ ДАННЫЕ
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const eq = (a, b) => a !== null && b !== null && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const iso = (d) => d.toISOString().slice(0, 10);
const today = iso(new Date());
const shift = (n) => iso(new Date(Date.now() + n * 86400000));

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  →  ', 'НЕЛЬЗЯ': '  ✗  ' }[verdict];
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
  if (!r.ok) throw new Error(`${p} → ${r.status}: ${JSON.stringify(r.body).slice(0, 250)}`);
  return r.body;
}
const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});

const pocket = async () => Number((await must('/pocket')).pocket);
const events = (from, to) => must(`/events?startDate=${from}&endDate=${to}`);

async function main() {
  console.log(`\nANO-50 · первый проход, этапы 3 и 4 · ${today}`);
  console.log('='.repeat(78));

  const cats = await must('/categories');
  const rent = cats.find((c) => c.name === 'Ипотека') ?? cats.find((c) => c.type === 'EXPENSE');
  const food = cats.find((c) => c.name === 'Продукты') ?? cats.find((c) => c.type === 'EXPENSE');
  const salary = cats.find((c) => c.type === 'INCOME');
  let p0 = await pocket();
  console.log(`старт: кармашек ${money(p0)}\n`);

  // ── 3.1 плановый расход ──────────────────────────────────────────────────
  const nextMonth5 = (() => { const d = new Date(); d.setMonth(d.getMonth() + 1, 5); return iso(d); })();
  const plan = await must('/events', send('POST', {
    date: nextMonth5, categoryId: rent.id, type: 'EXPENSE', plannedAmount: 35000, description: 'Аренда ANO-50',
  }));
  plan.eventKind === 'PLAN' && plan.status === 'PLANNED'
    ? record('3.1', 'СОШЛОСЬ', 'плановый расход создан с меткой «план»')
    : record('3.1', 'РАСХОЖДЕНИЕ', 'создалось не плановым', { kind: plan.eventKind, status: plan.status });

  // ── 3.2 плановый доход, горизонт ─────────────────────────────────────────
  const hz0 = (await must('/pocket')).horizon;
  const soonIncome = shift(3);
  const inc = await must('/events', send('POST', {
    date: soonIncome, categoryId: salary.id, type: 'INCOME', plannedAmount: 75000, description: 'Доход ANO-50',
  }));
  const hz1 = (await must('/pocket')).horizon;
  hz1.endDate === soonIncome
    ? record('3.2', 'СОШЛОСЬ', `горизонт переехал на ближайший доход ${soonIncome}`)
    : record('3.2', 'СМОТРЕТЬ', 'горизонт не встал на новый доход',
      { было: hz0.endDate, стало: hz1.endDate, новыйДоход: soonIncome, подпись: hz1.label });
  await api(`/events/${inc.id}`, { method: 'DELETE' });

  // ── 3.3 факт без плана ───────────────────────────────────────────────────
  p0 = await pocket();
  const standalone = await must('/events/facts', send('POST', {
    date: today, categoryId: food.id, type: 'EXPENSE', factAmount: 3200, description: 'Факт ANO-50',
  }));
  let p1 = await pocket();
  eq(p1 - p0, -3200)
    ? record('3.3', 'СОШЛОСЬ', 'факт без плана: кармашек упал на 3 200')
    : record('3.3', 'СМОТРЕТЬ', 'кармашек сдвинулся не на 3 200 — вероятно окно якоря (ANO-82)',
      { сдвиг: p1 - p0, якорь: (await must('/pocket')).checkpointDate });

  // ── 3.8 правка суммы факта ───────────────────────────────────────────────
  p0 = await pocket();
  await must(`/events/${standalone.id}/fact`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ factAmount: 1200 }),
  });
  p1 = await pocket();
  eq(p1 - p0, 2000)
    ? record('3.8', 'СОШЛОСЬ', 'правка факта 3 200 → 1 200 вернула 2 000 в кармашек')
    : record('3.8', 'СМОТРЕТЬ', 'правка факта сдвинула кармашек не на 2 000', { сдвиг: p1 - p0 });

  // ── 3.9 удалить факт ─────────────────────────────────────────────────────
  p0 = await pocket();
  await api(`/events/${standalone.id}`, { method: 'DELETE' });
  p1 = await pocket();
  eq(p1 - p0, 1200)
    ? record('3.9', 'СОШЛОСЬ', 'удаление факта вернуло 1 200')
    : record('3.9', 'СМОТРЕТЬ', 'удаление факта сдвинуло кармашек не на 1 200', { сдвиг: p1 - p0 });

  // ── 3.4–3.6 факты на план ────────────────────────────────────────────────
  const near = shift(4);
  const planForFact = await must('/events', send('POST', {
    date: near, categoryId: food.id, type: 'EXPENSE', plannedAmount: 6000, description: 'План под факт ANO-50',
  }));
  p0 = await pocket();
  const child = await api(`/events/${planForFact.id}/facts`, send('POST', {
    date: near, factAmount: 5480, description: 'Факт к плану',
  }));
  if (child.ok) {
    p1 = await pocket();
    const list = await events(near, near);
    const family = list.filter((e) => e.id === planForFact.id || e.parentEventId === planForFact.id);
    const parent = family.find((e) => e.id === planForFact.id);
    eq(p1 - p0, 520)
      ? record('3.4', 'СОШЛОСЬ', 'факт 5 480 на план 6 000: кармашек вырос ровно на 520')
      : record('3.4', 'РАСХОЖДЕНИЕ', 'кармашек сдвинулся не на 520', { сдвиг: p1 - p0 });
    parent?.status === 'EXECUTED'
      ? record('3.4', 'СОШЛОСЬ', 'план перестал висеть ожидаемым, статус EXECUTED')
      : record('3.4', 'СМОТРЕТЬ', 'статус плана после факта', { статус: parent?.status });
    record('3.4', 'РУКАМИ', 'в журнале должна быть ОДНА строка, а не две — видно только на экране',
      { записейВБазеНаЭтуДату: family.length });

    // 3.6 ещё два факта на тот же план
    const f2 = await api(`/events/${planForFact.id}/facts`, send('POST', { date: near, factAmount: 100 }));
    const f3 = await api(`/events/${planForFact.id}/facts`, send('POST', { date: near, factAmount: 200 }));
    const after = (await events(near, near)).filter((e) => e.parentEventId === planForFact.id);
    after.length === 3
      ? record('3.6', 'СОШЛОСЬ', 'три факта на один план сосуществуют',
        { суммаФактов: after.reduce((s, e) => s + Number(e.factAmount ?? 0), 0), план: 6000 })
      : record('3.6', 'СМОТРЕТЬ', 'фактов на плане не три',
        { сколько: after.length, коды: [f2.status, f3.status] });
    await api(`/events/${planForFact.id}`, { method: 'DELETE', });
  } else {
    record('3.4', 'РАСХОЖДЕНИЕ', `привязка факта к плану вернула ${child.status}`, { тело: child.body });
  }

  // ── 3.5 факт больше плана ────────────────────────────────────────────────
  const plan5 = await must('/events', send('POST', {
    date: near, categoryId: food.id, type: 'EXPENSE', plannedAmount: 6000, description: 'План 3.5 ANO-50',
  }));
  p0 = await pocket();
  const over = await api(`/events/${plan5.id}/facts`, send('POST', { date: near, factAmount: 7300 }));
  p1 = await pocket();
  over.ok && eq(p1 - p0, -1300)
    ? record('3.5', 'СОШЛОСЬ', 'факт 7 300 на план 6 000: кармашек упал на 1 300')
    : record('3.5', over.ok ? 'РАСХОЖДЕНИЕ' : 'СМОТРЕТЬ',
      over.ok ? 'перерасход сдвинул кармашек не на −1 300' : `факт больше плана отвергнут ${over.status}`,
      { сдвиг: p1 - p0 });
  await api(`/events/${plan5.id}`, { method: 'DELETE' });

  record('3.7', 'РУКАМИ', 'калькулятор в поле суммы: 340+1200+89.50, 1000-250, 3*99, 100+, abc, -500, 0');

  // ── 3.10 просроченный план ───────────────────────────────────────────────
  const past = shift(-5);
  const overdue = await must('/events', send('POST', {
    date: past, categoryId: rent.id, type: 'EXPENSE', plannedAmount: 9000,
    priority: 'HIGH', description: 'Просрочка ANO-50',
  }));
  const pk = await must('/pocket');
  const od = pk.breakdown.find((b) => b.type === 'OVERDUE_RESERVE');
  const anchorDate = pk.checkpointDate;
  if (od && od.details?.some((d) => d.includes('Просрочка ANO-50'))) {
    record('3.10', 'СОШЛОСЬ', 'просроченный план зарезервирован в кармашке', { строка: od.amount });
  } else if (past <= anchorDate) {
    record('3.10', 'СМОТРЕТЬ', 'просрочка не зарезервирована: её дата не позже якоря — это ANO-28, не дефект',
      { датаСобытия: past, якорь: anchorDate, строкаПросрочки: od?.amount ?? 'нет' });
  } else {
    record('3.10', 'РАСХОЖДЕНИЕ', 'просроченный HIGH-план не попал в резерв',
      { датаСобытия: past, якорь: anchorDate, строка: od?.amount ?? 'нет' });
  }
  await api(`/events/${overdue.id}`, { method: 'DELETE' });

  // ── 3.11 далёкое будущее ─────────────────────────────────────────────────
  const far = (() => { const d = new Date(); d.setMonth(d.getMonth() + 8); return iso(d); })();
  p0 = await pocket();
  const farEv = await must('/events', send('POST', {
    date: far, categoryId: rent.id, type: 'EXPENSE', plannedAmount: 120000, description: 'Далёкое ANO-50',
  }));
  const pNear = await pocket();
  const p12 = Number((await must('/pocket?scope=MONTHS:12')).pocket);
  const p12before = p12 + 120000;
  eq(pNear, p0)
    ? record('3.11', 'СОШЛОСЬ', 'событие через 8 месяцев не тронуло кармашек «до дохода»')
    : record('3.11', 'РАСХОЖДЕНИЕ', 'далёкое событие сдвинуло ближний кармашек', { сдвиг: pNear - p0 });
  record('3.11', 'СМОТРЕТЬ', 'на скоупе 12 месяцев событие обязано быть учтено',
    { кармашек12мес: p12, датаСобытия: far });
  await api(`/events/${farEv.id}`, { method: 'DELETE' });

  // ── 3.12 порядок записей ─────────────────────────────────────────────────
  const d12 = shift(6);
  const trio = [];
  for (const n of [1, 2, 3]) {
    trio.push(await must('/events', send('POST', {
      date: d12, categoryId: food.id, type: 'EXPENSE', plannedAmount: n * 100,
      description: `Порядок ${n} ANO-50`,
    })));
  }
  const orders = [];
  for (let i = 0; i < 4; i++) {
    orders.push((await events(d12, d12)).map((e) => e.description).join('|'));
  }
  new Set(orders).size === 1
    ? record('3.12', 'СОШЛОСЬ', 'порядок записей стабилен между четырьмя запросами', { порядок: orders[0] })
    : record('3.12', 'РАСХОЖДЕНИЕ', 'порядок записей плавает между запросами', { варианты: [...new Set(orders)] });

  // ── 3.13 пустое описание ─────────────────────────────────────────────────
  const noDesc = await api('/events', send('POST', {
    date: d12, categoryId: food.id, type: 'EXPENSE', plannedAmount: 500,
  }));
  if (noDesc.ok) {
    const fetched = (await events(d12, d12)).find((e) => e.id === noDesc.body.id);
    record('3.13', 'СМОТРЕТЬ', 'событие без описания создано — чем подписано в API',
      { description: fetched?.description, categoryName: fetched?.categoryName,
        примечание: 'подстановку имени категории на экране проверять глазами' });
    await api(`/events/${noDesc.body.id}`, { method: 'DELETE' });
  } else {
    record('3.13', 'СОШЛОСЬ', `пустое описание отвергнуто, ${noDesc.status}`);
  }
  for (const t of trio) await api(`/events/${t.id}`, { method: 'DELETE' });

  record('3.14', 'НЕЛЬЗЯ', 'факт по кредитной карте: у события нет счёта (ANO-83), блок невыполним');
  record('3.15', 'НЕЛЬЗЯ', 'погашение кредитки переводом: счёта у события нет (ANO-83), блок невыполним');
  record('3.16', 'РУКАМИ', 'свободное исследование журнала и ввода');

  // ══ ЭТАП 4. ПОВТОРЯЮЩИЕСЯ ПРАВИЛА ═════════════════════════════════════════
  console.log('');

  // 4.1 бессрочное правило
  const rule = await must('/events', send('POST', {
    date: nextMonth5, categoryId: rent.id, type: 'EXPENSE', plannedAmount: 35000,
    description: 'Аренда повтор ANO-50',
    recurring: { frequency: 'MONTHLY', dayOfMonth: 5, startDate: nextMonth5 },
  }));
  const horizon12 = (() => { const d = new Date(); d.setMonth(d.getMonth() + 13); return iso(d); })();
  const gen = (await events(today, horizon12)).filter((e) => e.description === 'Аренда повтор ANO-50');
  gen.length >= 11 && gen.length <= 13
    ? record('4.1', 'СОШЛОСЬ', `бессрочное правило породило ${gen.length} событий на год вперёд`,
      { первое: gen[0]?.date, последнее: gen[gen.length - 1]?.date })
    : record('4.1', 'РАСХОЖДЕНИЕ', `на год вперёд породилось ${gen.length} событий, ожидалось около 12`,
      { даты: gen.map((e) => e.date) });
  const ruleId = gen[0]?.recurringRuleId ?? rule.recurringRuleId;

  // 4.3 клэмп дня месяца
  const rule31 = await must('/events', send('POST', {
    date: '2027-01-31', categoryId: rent.id, type: 'EXPENSE', plannedAmount: 1000,
    description: 'Клэмп31 ANO-50',
    recurring: { frequency: 'MONTHLY', dayOfMonth: 31, startDate: '2027-01-31', endDate: '2027-07-01' },
  }));
  const clamp = (await events('2027-01-01', '2027-08-01')).filter((e) => e.description === 'Клэмп31 ANO-50');
  const feb = clamp.find((e) => e.date.startsWith('2027-02'));
  const apr = clamp.find((e) => e.date.startsWith('2027-04'));
  feb?.date === '2027-02-28' && apr?.date === '2027-04-30'
    ? record('4.3', 'СОШЛОСЬ', 'день месяца подрезан: февраль 28, апрель 30, на 1 марта не уехало')
    : record('4.3', 'РАСХОЖДЕНИЕ', 'клэмп дня месяца отработал не так',
      { февраль: feb?.date, апрель: apr?.date, всеДаты: clamp.map((e) => e.date) });

  // 4.2 правило с датой окончания
  clamp.length === 7
    ? record('4.2', 'СОШЛОСЬ', 'правило с датой окончания дало ровно 7 событий (январь–июль)')
    : record('4.2', 'СМОТРЕТЬ', `правило с endDate=2027-07-01 дало ${clamp.length} событий`,
      { даты: clamp.map((e) => e.date) });

  // 4.4 удалить одно
  const before44 = clamp.length;
  const mid = clamp[3];
  await api(`/events/${mid.id}?scope=THIS`, { method: 'DELETE' });
  const after44 = (await events('2027-01-01', '2027-08-01')).filter((e) => e.description === 'Клэмп31 ANO-50');
  after44.length === before44 - 1
    ? record('4.4', 'СОШЛОСЬ', 'scope=THIS удалил ровно одно событие, остальные целы')
    : record('4.4', 'РАСХОЖДЕНИЕ', 'scope=THIS удалил не одно',
      { было: before44, стало: after44.length });

  // 4.7 правка суммы в режиме THIS
  const target = after44[1];
  await must(`/events/${target.id}?scope=THIS`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ date: target.date, categoryId: rent.id, type: 'EXPENSE', plannedAmount: 4444,
      description: 'Клэмп31 ANO-50' }),
  });
  const after47 = (await events('2027-01-01', '2027-08-01')).filter((e) => e.description === 'Клэмп31 ANO-50');
  const changed = after47.filter((e) => eq(e.plannedAmount, 4444));
  changed.length === 1
    ? record('4.7', 'СОШЛОСЬ', 'правка в режиме «только это» изменила ровно одно событие')
    : record('4.7', 'РАСХОЖДЕНИЕ', `«только это» изменило ${changed.length} событий`,
      { суммы: after47.map((e) => `${e.date}:${e.plannedAmount}`) });

  // 4.5 это и последующие
  const from45 = after47[2];
  const before45 = after47.length;
  await api(`/events/${from45.id}?scope=FOLLOWING`, { method: 'DELETE' });
  const after45 = (await events('2027-01-01', '2027-08-01')).filter((e) => e.description === 'Клэмп31 ANO-50');
  const survivors = after45.map((e) => e.date);
  after45.length === 2 && survivors.every((d) => d < from45.date)
    ? record('4.5', 'СОШЛОСЬ', 'scope=FOLLOWING снёс от выбранного вперёд, прошлые целы',
      { осталось: survivors })
    : record('4.5', 'СМОТРЕТЬ', 'FOLLOWING отработал не как ожидалось',
      { было: before45, стало: after45.length, осталось: survivors, отДаты: from45.date });

  // 4.6 удалить все
  if (after45.length) {
    await api(`/events/${after45[0].id}?scope=ALL`, { method: 'DELETE' });
    const after46 = (await events('2026-01-01', '2028-01-01')).filter((e) => e.description === 'Клэмп31 ANO-50');
    after46.length === 0
      ? record('4.6', 'СОШЛОСЬ', 'scope=ALL снёс всю цепочку, включая прошлые')
      : record('4.6', 'РАСХОЖДЕНИЕ', `после ALL осталось ${after46.length} событий`,
        { даты: after46.map((e) => e.date) });
  }

  // 4.8 факт на порождённом событии
  const gen2 = (await events(today, horizon12)).filter((e) => e.description === 'Аренда повтор ANO-50');
  if (gen2.length >= 3) {
    const child8 = await api(`/events/${gen2[2].id}/facts`, send('POST', {
      date: gen2[2].date, factAmount: 30000,
    }));
    if (child8.ok) {
      const edit = await api(`/events/${gen2[1].id}?scope=FOLLOWING`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ date: gen2[1].date, categoryId: rent.id, type: 'EXPENSE',
          plannedAmount: 40000, description: 'Аренда повтор ANO-50' }),
      });
      const factStill = (await events(today, horizon12))
        .find((e) => e.id === child8.body.id && !e.deleted);
      factStill
        ? record('4.8', 'СОШЛОСЬ', 'факт на порождённом событии пережил правку «это и последующие»',
          { фактСумма: factStill.factAmount, правка: edit.status })
        : record('4.8', 'РАСХОЖДЕНИЕ', 'факт исчез после правки правила вперёд', { правка: edit.status });
      record('4.8', 'РУКАМИ', 'предупреждал ли продукт, что правка затрагивает записанный факт');
    } else {
      record('4.8', 'СМОТРЕТЬ', `факт на порождённом событии не создался, ${child8.status}`, { тело: child8.body });
    }
  }

  // 4.9 длинный хвост
  const rules = await must('/events?startDate=2026-01-01&endDate=2051-12-31');
  const byRule = {};
  for (const e of rules) if (e.recurringRuleId) byRule[e.recurringRuleId] = (byRule[e.recurringRuleId] ?? 0) + 1;
  const longest = Object.entries(byRule).sort((a, b) => b[1] - a[1])[0];
  record('4.9', longest && longest[1] > 100 ? 'СМОТРЕТЬ' : 'СОШЛОСЬ',
    longest ? `самая длинная цепочка — ${longest[1]} событий` : 'правил с событиями нет',
    { всегоПравилССобытиями: Object.keys(byRule).length, длины: Object.values(byRule).sort((a, b) => b - a).slice(0, 5) });
  record('4.9', 'РУКАМИ', 'видно ли правило как единое целое или как список строк — только на экране');

  // 4.10 аванс и зарплата
  record('4.10', 'НЕЛЬЗЯ', 'ритм «5-го и 20-го» одним правилом невозможен: RecurringFrequency = {MONTHLY, YEARLY}',
    { нетТакже: 'раз в две недели (26 выплат в год)', основание: 'чек-лист B3, кандидат №1, ТК РФ ст. 136' });
  record('4.11', 'РУКАМИ', 'свободное исследование правил');

  // уборка
  const leftovers = (await must('/events?startDate=2026-01-01&endDate=2051-12-31'))
    .filter((e) => (e.description ?? '').includes('ANO-50'));
  for (const e of leftovers) await api(`/events/${e.id}?scope=ALL`, { method: 'DELETE' });
  const rest = (await must('/events?startDate=2026-01-01&endDate=2051-12-31'))
    .filter((e) => (e.description ?? '').includes('ANO-50'));

  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Расхождений: ${bad.length}   Требует глаз: ${log.filter((r) => r.verdict === 'СМОТРЕТЬ').length}   `
    + `Невыполнимо: ${log.filter((r) => r.verdict === 'НЕЛЬЗЯ').length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  console.log(`\nуборка: осталось событий с меткой ANO-50 — ${rest.length}`);
  console.log(`кармашек на выходе: ${money(await pocket())}\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });
