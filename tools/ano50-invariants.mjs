#!/usr/bin/env node
/**
 * ANO-50 — автомат инвариантов.
 *
 * Гоняется после каждого блока ручного тестирования (протокол, шаг 4): проверяет то,
 * чего не видно с экрана — сходится ли разбивка, не разъехались ли числа между экранами.
 *
 *   node tools/ano50-invariants.mjs                     # тестовый стенд, 8081
 *   node tools/ano50-invariants.mjs --api http://...    # другой стенд
 *   node tools/ano50-invariants.mjs --json out.json     # машинный отчёт для сравнения прогонов
 *   node tools/ano50-invariants.mjs --baseline old.json # что изменилось со времён old.json
 *
 * Три исхода у проверки, как и у находки (Приложение А плана):
 *   FAIL — инвариант нарушен, это дефект либо неверно понятая задумка;
 *   WARN — подозрительно, но может быть законно; смотреть глазами;
 *   INFO — снятое число, ничего не утверждает. Сюда же известные отклонения.
 *
 * Автомат НИЧЕГО НЕ МЕНЯЕТ: только GET.
 */

const args = process.argv.slice(2);
const argVal = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};

const API = argVal('--api', 'http://localhost:8081/api/v1').replace(/\/$/, '');
const JSON_OUT = argVal('--json', null);
const BASELINE = argVal('--baseline', null);
const EPS = 0.005; // полкопейки

const today = new Date();
const iso = (d) => d.toISOString().slice(0, 10);
const plusDays = (d, n) => new Date(d.getTime() + n * 86400000);
const TODAY = iso(today);

const SCOPES = [
  'NEXT_INCOME',
  'SECOND_INCOME',
  'MONTHS:1',
  'MONTHS:3',
  'MONTHS:12',
  'MONTHS:36',
  `DATE:${iso(plusDays(today, 45))}`,
];

const results = [];
const add = (level, id, scope, message, data) =>
  results.push({ level, id, scope: scope ?? null, message, data: data ?? null });

const fail = (...a) => add('FAIL', ...a);
const warn = (...a) => add('WARN', ...a);
const info = (...a) => add('INFO', ...a);

const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && Math.abs(a - b) < EPS;
const money = (v) =>
  v === null || v === undefined
    ? '—'
    : Number(v).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

async function get(path) {
  const url = `${API}${path}`;
  const res = await fetch(url);
  const text = await res.text();
  if (!res.ok) {
    return { ok: false, status: res.status, body: text.slice(0, 400), url };
  }
  try {
    return { ok: true, status: res.status, data: JSON.parse(text), url };
  } catch {
    return { ok: false, status: res.status, body: `не JSON: ${text.slice(0, 200)}`, url };
  }
}

// ── Кармашек: разбор одного скоупа ───────────────────────────────────────────

/** Строки ДО TRAJECTORY_MIN объясняют минимум; всё после POCKET — оговорки (Javadoc BreakdownType). */
const EXPLAINING = [
  'STARTING_BALANCE',
  'OVERDUE_RESERVE',
  'PLANNED_EXPENSES',
  'SAVINGS_CONTRIBUTIONS',
  'PLANNED_INCOME',
  'UNPLANNED_FORECAST',
];

function checkPocket(scope, p) {
  const lines = p.breakdown ?? [];
  const byType = {};
  for (const l of lines) {
    if (byType[l.type] !== undefined) {
      fail('P00-дубль-строки', scope, `строка breakdown ${l.type} встречается дважды`, {
        type: l.type,
        amounts: [byType[l.type], num(l.amount)],
      });
    }
    byType[l.type] = num(l.amount);
  }

  const pocket = num(p.pocket);
  const minLine = byType.TRAJECTORY_MIN;
  const pocketLine = byType.POCKET;
  const bufferLine = byType.BUFFER ?? 0;

  // I1. Объяснимость: сумма объясняющих строк = минимум траектории.
  const explained = EXPLAINING.reduce((s, t) => s + (byType[t] ?? 0), 0);
  if (minLine === undefined) {
    fail('I1-объяснимость', scope, 'в breakdown нет строки TRAJECTORY_MIN');
  } else if (!eq(explained, minLine)) {
    fail('I1-объяснимость', scope, 'сумма объясняющих строк не равна минимуму траектории', {
      сумма: explained,
      минимум: minLine,
      разница: explained - minLine,
      слагаемые: Object.fromEntries(EXPLAINING.filter((t) => byType[t] !== undefined).map((t) => [t, byType[t]])),
    });
  }

  // I2. Кармашек = минимум − буфер (строка буфера уже со знаком).
  if (minLine !== undefined && pocketLine !== undefined && !eq(minLine + bufferLine, pocketLine)) {
    fail('I2-кармашек-из-минимума', scope, 'минимум + буфер ≠ строка кармашка', {
      минимум: minLine,
      буфер: bufferLine,
      кармашек: pocketLine,
    });
  }

  // I3. Строка POCKET = поле pocket.
  if (pocketLine !== undefined && !eq(pocketLine, pocket)) {
    fail('I3-строка-против-поля', scope, 'строка POCKET не равна полю pocket', {
      строка: pocketLine,
      поле: pocket,
    });
  }

  // I4. STARTING_BALANCE = currentBalance.
  if (byType.STARTING_BALANCE !== undefined && !eq(byType.STARTING_BALANCE, num(p.currentBalance))) {
    fail('I4-старт-против-остатка', scope, 'STARTING_BALANCE ≠ currentBalance', {
      строка: byType.STARTING_BALANCE,
      currentBalance: num(p.currentBalance),
    });
  }

  // I5. minPoint.balance = строка минимума.
  const mp = p.minPoint;
  if (mp && minLine !== undefined && !eq(num(mp.balance), minLine)) {
    fail('I5-минимум-против-точки', scope, 'minPoint.balance ≠ строка TRAJECTORY_MIN', {
      minPoint: num(mp.balance),
      строка: minLine,
    });
  }

  // I6. minPoint — действительно минимум траектории, и дата совпадает.
  const traj = p.trajectory ?? [];
  if (traj.length && mp) {
    let lo = traj[0];
    for (const t of traj) if (num(t.balance) < num(lo.balance) - EPS) lo = t;
    if (!eq(num(lo.balance), num(mp.balance))) {
      fail('I6-минимум-настоящий', scope, 'minPoint не самая низкая точка траектории', {
        minPoint: { дата: mp.date, баланс: num(mp.balance) },
        реальныйМинимум: { дата: lo.date, баланс: num(lo.balance) },
      });
    } else if (lo.date !== mp.date) {
      warn('I6-дата-минимума', scope, 'баланс минимума совпал, а дата — нет (плато либо выбор из равных)', {
        minPoint: mp.date,
        перваяТакаяТочка: lo.date,
      });
    }
  }

  // I7. Траектория: даты строго растут, шаг ровно сутки, конец = горизонт.
  if (traj.length) {
    for (let i = 1; i < traj.length; i++) {
      const prev = new Date(traj[i - 1].date + 'T00:00:00Z');
      const cur = new Date(traj[i].date + 'T00:00:00Z');
      const step = (cur - prev) / 86400000;
      if (step !== 1) {
        fail('I7-шаг-траектории', scope, 'между точками траектории не сутки', {
          от: traj[i - 1].date,
          до: traj[i].date,
          дней: step,
        });
        break;
      }
    }
    const end = p.horizon?.endDate;
    if (end && traj[traj.length - 1].date !== end) {
      warn('I7-конец-траектории', scope, 'последняя точка траектории ≠ конец горизонта', {
        последняя: traj[traj.length - 1].date,
        горизонт: end,
      });
    }
  } else {
    warn('I7-траектория-пуста', scope, 'траектория пустая');
  }

  // I8. Рекуррентность: баланс дня = баланс вчера + доход − расход.
  for (let i = 1; i < traj.length; i++) {
    const expect = num(traj[i - 1].balance) + num(traj[i].income) - num(traj[i].expense);
    if (!eq(expect, num(traj[i].balance))) {
      fail('I8-рекуррентность', scope, 'баланс дня не выводится из вчерашнего и движений дня', {
        дата: traj[i].date,
        вчера: num(traj[i - 1].balance),
        доход: num(traj[i].income),
        расход: num(traj[i].expense),
        ожидалось: expect,
        получено: num(traj[i].balance),
      });
      break; // одного примера довольно, дальше поедет каскадом
    }
  }

  // I9. Второе число = кармашек + строка возврата карт (строка уже отрицательная).
  const after = num(p.pocketAfterCreditRestore);
  const restore = byType.CREDIT_RESTORE;
  if (restore !== undefined) {
    if (after === null) {
      warn('I9-второе-число', scope, 'строка CREDIT_RESTORE есть, а pocketAfterCreditRestore = null');
    } else if (!eq(pocket + restore, after)) {
      fail('I9-второе-число', scope, 'pocketAfterCreditRestore ≠ кармашек + строка возврата', {
        кармашек: pocket,
        строкаВозврата: restore,
        ожидалось: pocket + restore,
        получено: after,
      });
    }
  } else if (after !== null) {
    warn('I9-второе-число', scope, 'pocketAfterCreditRestore есть, а строки CREDIT_RESTORE в breakdown нет', {
      значение: after,
    });
  }

  // I11. Поле buffer против строки буфера.
  const bufField = num(p.buffer) ?? 0;
  if (!eq(-bufferLine, bufField)) {
    warn('I11-буфер', scope, 'поле buffer не равно строке BUFFER со знаком минус', {
      поле: bufField,
      строка: bufferLine,
    });
  }

  return {
    scope,
    pocket,
    currentBalance: num(p.currentBalance),
    buffer: bufField,
    checkpointDate: p.checkpointDate ?? null,
    horizon: p.horizon ?? null,
    minPoint: mp ? { date: mp.date, balance: num(mp.balance), drivenBy: mp.drivenBy ?? null } : null,
    pocketAfterCreditRestore: after,
    pocketWithDeposits: num(p.pocketWithDeposits),
    trajectoryPoints: traj.length,
    breakdown: lines.map((l) => ({ type: l.type, amount: num(l.amount), label: l.label, details: (l.details ?? []).length })),
    startingBalance: byType.STARTING_BALANCE ?? null,
  };
}

// ── Прогон ───────────────────────────────────────────────────────────────────

async function main() {
  const snapshot = { takenAt: new Date().toISOString(), api: API, today: TODAY, scopes: {}, accounts: null, capital: null };

  // Кармашек по всем скоупам.
  for (const scope of SCOPES) {
    const r = await get(`/pocket?scope=${encodeURIComponent(scope)}`);
    if (!r.ok) {
      fail('I0-скоуп-отвечает', scope, `GET /pocket вернул ${r.status}`, { тело: r.body });
      continue;
    }
    snapshot.scopes[scope] = checkPocket(scope, r.data);
  }

  const scopeList = Object.values(snapshot.scopes);

  // I12–I14. Через скоупы: остаток, якорь и стартовая строка обязаны совпадать.
  const distinct = (key) => [...new Set(scopeList.map((s) => JSON.stringify(s[key])))];
  for (const [key, id, human] of [
    ['currentBalance', 'I12-остаток-един', 'currentBalance'],
    ['checkpointDate', 'I13-якорь-един', 'дата якоря'],
    ['startingBalance', 'I14-старт-един', 'строка STARTING_BALANCE'],
  ]) {
    const vals = distinct(key);
    if (vals.length > 1) {
      fail(id, null, `${human} различается между скоупами — одно и то же число не может зависеть от окна`, {
        значения: Object.fromEntries(scopeList.map((s) => [s.scope, s[key]])),
      });
    }
  }

  // I15. Известное отклонение (блок 5.4): кармашек разный на разных скоупах.
  const pockets = Object.fromEntries(scopeList.map((s) => [s.scope, s.pocket]));
  if (new Set(Object.values(pockets)).size > 1) {
    info('I15-известное-отклонение', null,
      'кармашек различается между скоупами — ожидаемо (спека §3.5, материал ANO-22), НЕ дефект',
      pockets);
  }

  // Счета.
  const acc = await get('/accounts');
  if (!acc.ok) {
    fail('A0-счета-отвечают', null, `GET /accounts вернул ${acc.status}`, { тело: acc.body });
  } else {
    const accounts = acc.data;
    snapshot.accounts = accounts.map((a) => ({
      name: a.name, kind: a.kind, trackBalance: a.trackBalance, balance: num(a.balance),
      balanceDate: a.balanceDate ?? null, creditLimit: num(a.creditLimit), debt: num(a.debt),
      availableFloor: num(a.availableFloor), isDefault: a.isDefault,
    }));

    // A1. Долг кредитки = лимит − доступный остаток.
    for (const a of accounts) {
      if (a.kind !== 'CREDIT') continue;
      const limit = num(a.creditLimit), bal = num(a.balance), debt = num(a.debt);
      if (limit === null || bal === null || debt === null) continue;
      if (!eq(limit - bal, debt)) {
        fail('A1-долг-кредитки', null, `долг «${a.name}» ≠ лимит − доступно`, {
          лимит: limit, доступно: bal, долг: debt, ожидалось: limit - bal,
        });
      }
    }

    // A2. Ровно один счёт по умолчанию.
    const defaults = accounts.filter((a) => a.isDefault);
    if (defaults.length !== 1) {
      fail('A2-счёт-по-умолчанию', null, `счетов по умолчанию ${defaults.length}, а должен быть один`, {
        счета: defaults.map((a) => a.name),
      });
    }

    // A3. Третье число = кармашек + вклады.
    const deposits = accounts.filter((a) => a.kind === 'DEPOSIT');
    const depositSum = deposits.reduce((s, a) => s + (num(a.balance) ?? 0), 0);
    for (const s of scopeList) {
      if (deposits.length === 0) {
        if (s.pocketWithDeposits !== null) {
          fail('A3-третье-число', s.scope, 'вкладов нет, а pocketWithDeposits не null', {
            значение: s.pocketWithDeposits,
          });
        }
      } else if (s.pocketWithDeposits === null) {
        fail('A3-третье-число', s.scope, 'вклады есть, а pocketWithDeposits = null', {
          вклады: deposits.map((a) => a.name), сумма: depositSum,
        });
      } else if (!eq(s.pocket + depositSum, s.pocketWithDeposits)) {
        fail('A3-третье-число', s.scope, 'pocketWithDeposits ≠ кармашек + сумма вкладов', {
          кармашек: s.pocket, вклады: depositSum,
          ожидалось: s.pocket + depositSum, получено: s.pocketWithDeposits,
        });
      }
    }

    // A4. Свежесть якоря.
    const anchorDates = accounts.map((a) => a.balanceDate).filter(Boolean).sort();
    if (anchorDates.length === 0) {
      warn('A4-свежесть-якоря', null, 'ни на одном счёте нет даты остатка');
    } else {
      const newest = anchorDates[anchorDates.length - 1];
      const age = Math.round((new Date(TODAY) - new Date(newest)) / 86400000);
      const level = age > 30 ? warn : info;
      level('A4-свежесть-якоря', null, `самому свежему остатку ${age} дн.`, {
        свежий: newest, самыйСтарый: anchorDates[0], всегоСДатой: anchorDates.length,
      });
    }
  }

  // Капитал.
  const cap = await get('/capital/summary');
  if (!cap.ok) {
    fail('K0-капитал-отвечает', null, `GET /capital/summary вернул ${cap.status}`, { тело: cap.body });
  } else {
    const c = cap.data;
    const total = num(c.total), liquid = num(c.liquid);
    const assets = num(c.assetsTotal), liabilities = num(c.liabilitiesTotal);
    snapshot.capital = { total, liquid, assets, liabilities, items: (c.items ?? []).length };

    // K1. Итого = ликвид + активы − обязательства.
    if (!eq(liquid + assets - liabilities, total)) {
      fail('K1-формула-капитала', null, 'итого ≠ ликвид + активы − обязательства', {
        ликвид: liquid, активы: assets, обязательства: liabilities,
        ожидалось: liquid + assets - liabilities, получено: total,
      });
    }

    // K2. Суммы строк сходятся с итогами по видам.
    const sum = (kind) => (c.items ?? [])
      .filter((i) => i.kind === kind && !i.isArchived)
      .reduce((s, i) => s + (num(i.currentValue) ?? 0), 0);
    if (!eq(sum('ASSET'), assets)) {
      fail('K2-активы-по-строкам', null, 'сумма строк-активов ≠ assetsTotal', {
        построкам: sum('ASSET'), итого: assets,
      });
    }
    // K2/K4. Обязательства = строки капитала + долги по кредиткам из счетов (§7.3).
    // Разрыв, в точности равный сумме долгов, — не расхождение, а устройство. Любой другой — расхождение.
    const gap = liabilities - sum('LIABILITY');
    const creditDebt = acc.ok
      ? acc.data.filter((a) => a.kind === 'CREDIT').reduce((s, a) => s + (num(a.debt) ?? 0), 0)
      : null;
    if (!eq(gap, 0)) {
      if (creditDebt !== null && eq(gap, creditDebt)) {
        info('K4-кредитки-в-обязательствах', null,
          'обязательства = строки капитала + долги по кредиткам, разрыв сходится в точности', {
          строкиКапитала: sum('LIABILITY'), долгиКредиток: creditDebt, итого: liabilities,
        });
      } else {
        fail('K2-обязательства-по-строкам', null,
          'liabilitiesTotal не раскладывается на строки капитала плюс долги кредиток', {
          построкам: sum('LIABILITY'), долгиКредиток: creditDebt,
          итого: liabilities, необъяснённыйОстаток: gap - (creditDebt ?? 0),
        });
      }
    }

    // K3. Двойной учёт кредитки: карта заведена и как счёт, и как обязательство.
    if (acc.ok) {
      const credits = acc.data.filter((a) => a.kind === 'CREDIT');
      const norm = (s) => (s ?? '').toLowerCase().replace(/[^a-zа-яё0-9]/gi, '');
      for (const a of credits) {
        const hit = (c.items ?? []).filter((i) => i.kind === 'LIABILITY' && !i.isArchived)
          .find((i) => norm(i.name) === norm(a.name) || norm(i.name).includes(norm(a.name)));
        if (hit) {
          warn('K3-двойной-учёт-кредитки', null,
            `кредитка «${a.name}» есть и как счёт, и как обязательство «${hit.name}» — проверить, не вычтена ли дважды`, {
            долгПоСчёту: num(a.debt), строкаКапитала: num(hit.currentValue),
          });
        }
      }
    }
  }

  // Кросс-экранная согласованность: ликвид против остатка кармашка.
  if (snapshot.capital && scopeList.length) {
    const cb = scopeList[0].currentBalance;
    if (!eq(snapshot.capital.liquid, cb)) {
      warn('X1-ликвид-против-остатка', null,
        'ликвид Капитала ≠ currentBalance кармашка — законно при наличии вкладов и копилок, иначе расхождение', {
        ликвидКапитала: snapshot.capital.liquid, остатокКармашка: cb,
        разница: snapshot.capital.liquid - cb,
      });
    }
  }

  // Тревожные кнопки по правилам продукта (не арифметика — сигнал к блоку 13.1).
  const dash = await get('/analytics/dashboard');
  if (dash.ok) {
    const bars = dash.data.progressBars ?? [];
    const withLimit = bars.filter((b) => b.plannedLimit !== null && b.plannedLimit !== undefined);
    if (withLimit.length) {
      warn('R1-лимиты-по-категориям', null,
        `API дашборда отдаёт ${withLimit.length} категорийных полос с plannedLimit/percentage — тронуть запрет 1 продуктовых правил; проверить, что показывает экран (блок 13.1)`, {
        примеры: withLimit.slice(0, 5).map((b) => ({
          категория: b.categoryName, план: num(b.plannedLimit), факт: num(b.currentFact), процент: b.percentage,
        })),
        всего: bars.length,
      });
    }
  } else {
    warn('R0-дашборд-отвечает', null, `GET /analytics/dashboard вернул ${dash.status}`, { тело: dash.body });
  }

  // ── Отчёт ──────────────────────────────────────────────────────────────────

  const fails = results.filter((r) => r.level === 'FAIL');
  const warns = results.filter((r) => r.level === 'WARN');
  const infos = results.filter((r) => r.level === 'INFO');

  const line = (r) => {
    const scope = r.scope ? ` [${r.scope}]` : '';
    let out = `  ${r.id}${scope}: ${r.message}`;
    if (r.data) out += `\n      ${JSON.stringify(r.data, null, 2).replace(/\n/g, '\n      ')}`;
    return out;
  };

  console.log(`\nANO-50 · автомат инвариантов · ${API} · сегодня ${TODAY}`);
  console.log('─'.repeat(78));

  console.log('\nЧисла, которые снимаются всегда:');
  const s0 = scopeList[0];
  if (s0) {
    console.log(`  Кармашек (${s0.scope}):        ${money(s0.pocket)} ₽`);
    console.log(`  Минимум траектории:            ${money(s0.minPoint?.balance)} ₽ на ${s0.minPoint?.date ?? '—'}`);
    console.log(`  Остаток (currentBalance):      ${money(s0.currentBalance)} ₽, якорь ${s0.checkpointDate ?? '—'}`);
    console.log(`  Второе число (возврат карт):   ${money(s0.pocketAfterCreditRestore)} ₽`);
    console.log(`  Третье число (вклады):         ${money(s0.pocketWithDeposits)} ₽`);
  }
  if (snapshot.capital) {
    console.log(`  Капитал итого:                 ${money(snapshot.capital.total)} ₽`);
    console.log(`     активы ${money(snapshot.capital.assets)} · ликвид ${money(snapshot.capital.liquid)} · обязательства ${money(snapshot.capital.liabilities)}`);
  }
  console.log('\n  Кармашек по скоупам:');
  for (const s of scopeList) {
    console.log(`     ${s.scope.padEnd(16)} ${String(money(s.pocket)).padStart(14)} ₽   горизонт ${s.horizon?.endDate ?? '—'}${s.horizon?.fallback ? ' (фолбэк)' : ''}`);
  }

  console.log('\n' + '─'.repeat(78));
  if (fails.length) {
    console.log(`\nFAIL — ${fails.length}:`);
    fails.forEach((r) => console.log(line(r)));
  }
  if (warns.length) {
    console.log(`\nWARN — ${warns.length}:`);
    warns.forEach((r) => console.log(line(r)));
  }
  if (infos.length) {
    console.log(`\nINFO — ${infos.length}:`);
    infos.forEach((r) => console.log(line(r)));
  }

  const checksRun = new Set(results.map((r) => r.id)).size;
  console.log(`\n${'─'.repeat(78)}`);
  console.log(fails.length === 0
    ? `ЗЕЛЁНЫЙ. Нарушенных инвариантов нет. Предупреждений ${warns.length}, снятых чисел ${infos.length}.`
    : `КРАСНЫЙ. Нарушено инвариантов: ${fails.length}. Предупреждений ${warns.length}.`);
  console.log(`Сработавших проверок с сообщением: ${checksRun}. Скоупов проверено: ${scopeList.length}/${SCOPES.length}.\n`);

  // Сравнение с прошлым прогоном.
  if (BASELINE) {
    const fs = await import('node:fs');
    if (fs.existsSync(BASELINE)) {
      const base = JSON.parse(fs.readFileSync(BASELINE, 'utf8'));
      console.log(`Сравнение с ${BASELINE} (снят ${base.snapshot?.takenAt ?? '?'}):`);
      const diffs = [];
      for (const s of scopeList) {
        const b = base.snapshot?.scopes?.[s.scope];
        if (!b) { diffs.push(`  ${s.scope}: скоупа не было в базовом прогоне`); continue; }
        if (!eq(b.pocket, s.pocket)) diffs.push(`  ${s.scope} кармашек: ${money(b.pocket)} → ${money(s.pocket)} (${money(s.pocket - b.pocket)})`);
        if (b.minPoint?.date !== s.minPoint?.date) diffs.push(`  ${s.scope} дата минимума: ${b.minPoint?.date} → ${s.minPoint?.date}`);
      }
      const bc = base.snapshot?.capital;
      if (bc && snapshot.capital && !eq(bc.total, snapshot.capital.total)) {
        diffs.push(`  Капитал итого: ${money(bc.total)} → ${money(snapshot.capital.total)} (${money(snapshot.capital.total - bc.total)})`);
      }
      const baseFails = new Set((base.results ?? []).filter((r) => r.level === 'FAIL').map((r) => r.id + '|' + r.scope));
      const nowFails = new Set(fails.map((r) => r.id + '|' + r.scope));
      for (const f of nowFails) if (!baseFails.has(f)) diffs.push(`  НОВЫЙ FAIL: ${f}`);
      for (const f of baseFails) if (!nowFails.has(f)) diffs.push(`  ушёл FAIL: ${f}`);
      console.log(diffs.length ? diffs.join('\n') : '  без изменений');
      console.log('');
    } else {
      console.log(`Базовый прогон ${BASELINE} не найден — сравнивать не с чем.\n`);
    }
  }

  if (JSON_OUT) {
    const fs = await import('node:fs');
    fs.writeFileSync(JSON_OUT, JSON.stringify({ snapshot, results }, null, 2), 'utf8');
    console.log(`Машинный отчёт: ${JSON_OUT}\n`);
  }

  process.exit(fails.length ? 1 : 0);
}

main().catch((e) => {
  console.error('\nАвтомат упал:', e.message);
  console.error('Стенд поднят? COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml start');
  process.exit(2);
});
