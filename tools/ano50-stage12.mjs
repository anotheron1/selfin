#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапам 1 и 2 — счета, категории, якорь.
 *
 * Это НЕ замена ручному прогону. Скрипт ходит по API и проверяет числа; пути
 * пользователя, тексты и реакцию интерфейса он не видит принципиально. Его задача —
 * сузить ручную работу до мест, где что-то разъехалось.
 *
 *   node tools/ano50-stage12.mjs            # прогон (МЕНЯЕТ ДАННЫЕ)
 *   node tools/ano50-stage12.mjs --dry      # только показать план шагов
 *
 * Перед прогоном снимается дамп, откат — восстановлением дампа.
 */

const args = process.argv.slice(2);
const API = 'http://localhost:8081/api/v1';
const DRY = args.includes('--dry');
const EPS = 0.005;

const eq = (a, b) => a !== null && b !== null && Math.abs(a - b) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const today = new Date().toISOString().slice(0, 10);
const daysAgo = (n) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  →  ' }[verdict];
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
async function must(path, init) {
  const r = await api(path, init);
  if (!r.ok) throw new Error(`${path} → ${r.status}: ${JSON.stringify(r.body).slice(0, 200)}`);
  return r.body;
}
const post = (p, body) => must(p, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});
const put = (p, body) => must(p, {
  method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

/** Снимок наблюдаемых чисел. */
async function snap() {
  const [p, accounts] = await Promise.all([must('/pocket'), must('/accounts')]);
  return {
    pocket: Number(p.pocket),
    second: p.pocketAfterCreditRestore === null ? null : Number(p.pocketAfterCreditRestore),
    balance: Number(p.currentBalance),
    anchorDate: p.checkpointDate,
    horizonLabel: p.horizon?.label,
    horizonEnd: p.horizon?.endDate,
    accounts,
  };
}
const byName = (accs, n) => accs.find((a) => a.name === n);

async function main() {
  console.log(`\nANO-50 · первый проход, этапы 1 и 2 · ${today}`);
  console.log('='.repeat(78));
  if (DRY) { console.log('режим --dry: ничего не выполняется'); return; }

  const cats = await must('/categories');
  const transport = cats.find((c) => c.name === 'Авто' || c.name === 'Транспорт');
  let s0 = await snap();
  console.log(`старт: кармашек ${money(s0.pocket)}, остаток ${money(s0.balance)}, второе ${money(s0.second)}\n`);

  // ── 1.1 дебетовый счёт со слежением ──────────────────────────────────────
  const alfa = await post('/accounts', { name: 'Альфа', kind: 'DEBIT', trackBalance: true });
  await post('/balance-checkpoints', { date: today, amount: 30000, accountId: alfa.id });
  let s = await snap();
  eq(s.pocket - s0.pocket, 30000)
    ? record('1.1', 'СОШЛОСЬ', 'счёт создан, кармашек вырос ровно на 30 000')
    : record('1.1', 'РАСХОЖДЕНИЕ', 'кармашек сдвинулся не на 30 000',
      { было: s0.pocket, стало: s.pocket, сдвиг: s.pocket - s0.pocket });
  s0 = s;

  // ── 1.2 дебетовый без слежения, с зоной ответственности ──────────────────
  const benz = await post('/accounts', {
    name: 'Бензин', kind: 'DEBIT', trackBalance: false, purposeCategoryId: transport?.id,
  });
  s = await snap();
  eq(s.pocket, s0.pocket)
    ? record('1.2', 'СОШЛОСЬ', 'счёт без слежения не тронул кармашек')
    : record('1.2', 'РАСХОЖДЕНИЕ', 'кармашек изменился, хотя слежение выключено',
      { сдвиг: s.pocket - s0.pocket });
  record('1.2', 'РУКАМИ', 'проверить глазами: есть ли на карточке поле остатка и что оно делает');
  s0 = s;

  // ── 1.3 кредитная карта ──────────────────────────────────────────────────
  const tink = await post('/accounts', {
    name: 'Тинькофф', kind: 'CREDIT', trackBalance: true, creditLimit: 200000,
  });
  await post('/balance-checkpoints', { date: today, amount: 180000, accountId: tink.id });
  s = await snap();
  const tinkCard = byName(s.accounts, 'Тинькофф');
  eq(Number(tinkCard.debt), 20000)
    ? record('1.3', 'СОШЛОСЬ', 'долг 20 000 = лимит 200 000 − доступно 180 000')
    : record('1.3', 'РАСХОЖДЕНИЕ', 'долг посчитан неверно', { долг: tinkCard.debt });
  eq(s.pocket, s0.pocket)
    ? record('1.3', 'СОШЛОСЬ', 'доступный кредит не попал в свободные деньги')
    : record('1.3', 'РАСХОЖДЕНИЕ', 'кармашек сдвинулся от кредитки', { сдвиг: s.pocket - s0.pocket });
  s0 = s;

  // ── 1.4 планка возврата ──────────────────────────────────────────────────
  await put(`/accounts/${tink.id}`, {
    name: 'Тинькофф', kind: 'CREDIT', trackBalance: true, creditLimit: 200000, availableFloor: 200000,
  });
  s = await snap();
  eq(s.second - s0.second, -20000)
    ? record('1.4', 'СОШЛОСЬ', 'второе число упало ровно на 20 000')
    : record('1.4', 'РАСХОЖДЕНИЕ', 'второе число сдвинулось не на 20 000',
      { было: s0.second, стало: s.second, сдвиг: s.second - s0.second });
  s0 = s;

  // ── 1.5 планка ниже доступного ───────────────────────────────────────────
  await put(`/accounts/${tink.id}`, {
    name: 'Тинькофф', kind: 'CREDIT', trackBalance: true, creditLimit: 200000, availableFloor: 50000,
  });
  s = await snap();
  eq(s.second - s0.second, 20000)
    ? record('1.5', 'СОШЛОСЬ', 'вклад Тинькофф в возврат обнулился, отрицательного возврата нет')
    : record('1.5', 'РАСХОЖДЕНИЕ', 'планка ниже доступного посчиталась не нулём',
      { было: s0.second, стало: s.second, сдвиг: s.second - s0.second });
  s0 = s;

  // ── 1.6 смена счёта по умолчанию ─────────────────────────────────────────
  await must(`/accounts/${alfa.id}/default`, { method: 'PATCH' });
  s = await snap();
  const defaults = s.accounts.filter((a) => a.isDefault);
  defaults.length === 1 && defaults[0].name === 'Альфа'
    ? record('1.6', 'СОШЛОСЬ', 'метка переехала, счёт по умолчанию ровно один')
    : record('1.6', 'РАСХОЖДЕНИЕ', 'счетов по умолчанию не один либо метка не та',
      { счета: defaults.map((a) => a.name) });
  record('1.6', 'СМОТРЕТЬ', 'смена дефолта меняет, чьи факты вообще учитываются в остатке (ANO-83)',
    { остатокДо: s0.balance, остатокПосле: s.balance });
  s0 = s;

  // ── 1.8 удалить счёт по умолчанию (идёт раньше 1.7: Альфа теперь дефолтный)
  const delDefault = await api(`/accounts/${alfa.id}`, { method: 'DELETE' });
  if (delDefault.status === 409) {
    record('1.8', 'СОШЛОСЬ', 'удаление счёта по умолчанию запрещено, 409');
  } else if (delDefault.ok) {
    const after = await snap();
    const d = after.accounts.filter((a) => a.isDefault);
    d.length === 1
      ? record('1.8', 'СМОТРЕТЬ', 'счёт удалён, метка автоматически переехала', { наКого: d[0].name })
      : record('1.8', 'РАСХОЖДЕНИЕ', 'счёт удалён и система осталась без счёта по умолчанию',
        { дефолтных: d.length });
  } else {
    record('1.8', 'РАСХОЖДЕНИЕ', `удаление ответило ${delDefault.status}`, { тело: delDefault.body });
  }

  // ── 1.7 удалить счёт с остатком ──────────────────────────────────────────
  const mainAcc = (await must('/accounts')).find((a) => a.name === 'Основная карта');
  if (mainAcc) await must(`/accounts/${mainAcc.id}/default`, { method: 'PATCH' });
  s0 = await snap();
  const delAlfa = await api(`/accounts/${alfa.id}`, { method: 'DELETE' });
  s = await snap();
  if (delAlfa.ok) {
    eq(s.pocket - s0.pocket, -30000)
      ? record('1.7', 'СОШЛОСЬ', 'счёт удалён, кармашек упал ровно на 30 000')
      : record('1.7', 'РАСХОЖДЕНИЕ', 'после удаления счёта кармашек сдвинулся не на 30 000',
        { сдвиг: s.pocket - s0.pocket });
  } else {
    record('1.7', 'СМОТРЕТЬ', `удаление вернуло ${delAlfa.status}`, { тело: delAlfa.body });
  }
  s0 = s;

  // ── 1.9 создать категорию ────────────────────────────────────────────────
  const pool = await post('/categories', { name: 'Бассейн', type: 'EXPENSE', priority: 'MEDIUM' });
  const catsNow = await must('/categories');
  catsNow.some((c) => c.id === pool.id)
    ? record('1.9', 'СОШЛОСЬ', 'категория создана и видна в списке')
    : record('1.9', 'РАСХОЖДЕНИЕ', 'категория не появилась в списке');
  record('1.10', 'РУКАМИ', 'приоритет: видно ли разделение на экране обязательных расходов');

  // ── 1.11 флаг «основной доход» ───────────────────────────────────────────
  const incomeCats = catsNow.filter((c) => c.type === 'INCOME' && !c.deleted);
  const flagged = incomeCats.filter((c) => c.primaryIncome);
  const before11 = await snap();
  for (const c of flagged) {
    await put(`/categories/${c.id}`, { name: c.name, type: c.type, priority: c.priority, primaryIncome: false });
  }
  const noFlags = await snap();
  const salary = incomeCats.find((c) => c.name === 'Зарплата') ?? incomeCats[0];
  await put(`/categories/${salary.id}`, {
    name: salary.name, type: salary.type, priority: salary.priority, primaryIncome: true,
  });
  const withFlag = await snap();
  record(withFlag.horizonEnd !== noFlags.horizonEnd ? '1.11' : '1.11',
    withFlag.horizonEnd !== noFlags.horizonEnd ? 'СОШЛОСЬ' : 'СМОТРЕТЬ',
    withFlag.horizonEnd !== noFlags.horizonEnd
      ? 'флаг сдвинул горизонт'
      : 'горизонт не изменился — либо ближайший доход и так по отмеченной категории',
    { былоФлагов: flagged.map((c) => c.name), безФлагов: noFlags.horizonLabel, сФлагом: withFlag.horizonLabel });

  // ── 1.12 удалить категорию с событиями ───────────────────────────────────
  const withEvents = catsNow.find((c) => c.name === 'Продукты') ?? catsNow.find((c) => c.type === 'EXPENSE');
  const eventsBefore = (await must(`/events?startDate=2026-01-01&endDate=2027-12-31`)).length;
  const delCat = await api(`/categories/${withEvents.id}`, { method: 'DELETE' });
  const eventsAfter = (await must(`/events?startDate=2026-01-01&endDate=2027-12-31`)).length;
  if (!delCat.ok) {
    record('1.12', 'СОШЛОСЬ', `удаление категории с событиями запрещено, ${delCat.status}`);
  } else if (eventsBefore === eventsAfter) {
    record('1.12', 'СОШЛОСЬ', 'категория удалена мягко, события целы', { событий: eventsAfter });
  } else {
    record('1.12', 'РАСХОЖДЕНИЕ', 'вместе с категорией пропали события',
      { было: eventsBefore, стало: eventsAfter, потеряно: eventsBefore - eventsAfter });
  }
  record('1.13', 'РУКАМИ', 'зона ответственности: перевести 5 000 на «Бензин» и проверить трату по «Транспорту»');
  record('1.14', 'РУКАМИ', 'свободное исследование экрана счетов');

  // ── Этап 2 ───────────────────────────────────────────────────────────────
  console.log('');
  const main2 = (await must('/accounts')).find((a) => a.isDefault);
  const etalon = (await must('/accounts')).find((a) => a.name === 'Эталон');

  // 2.1
  await post('/balance-checkpoints', { date: today, amount: 43200, accountId: main2.id });
  s = await snap();
  const m1 = byName(s.accounts, main2.name);
  eq(Number(m1.balance), 43200) && s.anchorDate === today
    ? record('2.1', 'СОШЛОСЬ', 'якорь записан на счёт, дата сегодняшняя')
    : record('2.1', 'РАСХОЖДЕНИЕ', 'остаток или дата якоря не те',
      { остаток: m1.balance, дата: s.anchorDate });

  // 2.2
  if (etalon) {
    const mainBefore = Number(byName(s.accounts, main2.name).balance);
    await post('/balance-checkpoints', { date: today, amount: 5000, accountId: etalon.id });
    const s2 = await snap();
    const e2 = byName(s2.accounts, 'Эталон'), mm = byName(s2.accounts, main2.name);
    eq(Number(e2.balance), 5000) && eq(Number(mm.balance), mainBefore)
      ? record('2.2', 'СОШЛОСЬ', 'якоря разных счетов не путаются')
      : record('2.2', 'РАСХОЖДЕНИЕ', 'якорь второго счёта задел первый',
        { эталон: e2.balance, основной: mm.balance, основнойБыл: mainBefore });
  }

  // 2.3
  await post('/balance-checkpoints', { date: today, amount: 44200, accountId: main2.id });
  s = await snap();
  const m3 = byName(s.accounts, main2.name);
  eq(Number(m3.balance), 44200)
    ? record('2.3', 'СОШЛОСЬ', 'из двух якорей на одну дату победил последний')
    : record('2.3', 'РАСХОЖДЕНИЕ', 'два якоря на одну дату дали не последнее значение',
      { остаток: m3.balance, ожидалось: 44200 });

  // 2.4
  const week = daysAgo(7);
  await post('/balance-checkpoints', { date: week, amount: 60000, accountId: main2.id });
  s = await snap();
  const m4 = byName(s.accounts, main2.name);
  record('2.4', 'СМОТРЕТЬ', 'якорь задним числом: остаток должен быть 60 000 минус факты за неделю',
    { якорь: `${week} = 60 000`, остатокСегодня: m4.balance, датаВРасшифровке: s.anchorDate });

  // 2.6
  const history = await must('/balance-checkpoints');
  record('2.6', 'СОШЛОСЬ', `история якорей отдаётся, записей ${history.length}`,
    { последние: history.slice(-3).map((h) => `${h.date}: ${h.amount}`) });

  // 2.7
  const mainHistory = history.filter((h) => h.accountId === main2.id)
    .sort((a, b) => (a.date < b.date ? 1 : -1));
  if (mainHistory.length >= 2) {
    const newest = mainHistory[0];
    const beforeDel = await snap();
    const d = await api(`/balance-checkpoints/${newest.id}`, { method: 'DELETE' });
    const afterDel = await snap();
    d.ok
      ? record('2.7', 'СМОТРЕТЬ', 'самый свежий якорь удалён, расчёт откатился',
        { удалён: `${newest.date} = ${newest.amount}`, остатокДо: beforeDel.balance,
          остатокПосле: afterDel.balance, датаЯкоря: afterDel.anchorDate })
      : record('2.7', 'РАСХОЖДЕНИЕ', `удаление якоря вернуло ${d.status}`, { тело: d.body });
  }
  record('2.5', 'РУКАМИ', 'искусственный дрейф: предупреждение о возрасте якоря видно только на экране');
  record('2.8', 'РУКАМИ', 'свободное исследование ре-якоря');

  // ── Итог ─────────────────────────────────────────────────────────────────
  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  const look = log.filter((r) => r.verdict === 'СМОТРЕТЬ');
  const hands = log.filter((r) => r.verdict === 'РУКАМИ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Расхождений: ${bad.length}   Требует глаз: ${look.length}   Только руками: ${hands.length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  console.log('');
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });
