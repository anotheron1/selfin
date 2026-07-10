# Pocket Answer Phrase (ANO-13) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Кармашек становится «ответом»: адаптивная фраза с ближайшей угрозой и виновником минимума; PocketCard = hero Dashboard, лживые строки старого hero уходят.

**Architecture:** Бэкенд отдаёт один новый структурный факт — `minPoint.drivenBy` (самый крупный плановый расход дня минимума, спека §3.6 дополнение 2026-07-10). Фраза собирается НА ФРОНТЕ чистой функцией `buildPocketPhrase` из полей `/pocket` (vitest-тесты). Dashboard: PocketCard наверх, большое число «Текущий баланс» и SalaryRow-строки удаляются (расчёт DashboardService врёт — разъезд зафиксирован в ANO-12), «на счёте X» переезжает компактной строкой в PocketCard, «Прогноз конца дня» пересаживается на `pocket.currentBalance`.

**Решения брейншторма (2026-07-10, приняты юзером):** кармашек = hero (вариант A); тон фраз — спокойный факт + «что дальше» (черновики A–D). Поправка к черновикам: число уже dip-aware (= min − буфер), поэтому «свободно 36к, просядешь до 5к» невозможно — фраза объясняет число, а не корректирует его.

**Прогоны:** maven из `backend/` (JAVA_HOME jbr-21.0.8, dev-БД: `docker start selfin-db`); фронт: `npm run build`, `npm test` (vitest). Ветка `feature/pocket-answer` от main.

---

## Chunk 1: всё

### Task 1: minPoint.drivenBy (движок, TDD)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java` (MinPoint)
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

- [ ] **Step 1: Падающий тест** (хелпер `plan()` ставит description="plan"; нужен вариант с кастомным описанием):

```java
    private static EventSnapshot planNamed(EventType type, LocalDate date, long amount, String description) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.PLANNED,
                Priority.MEDIUM, dec(amount), null, null, false, description);
    }

    @Test
    @DisplayName("minPoint.drivenBy = самый крупный плановый расход дня минимума; в день 0 — null")
    void minPointDrivenBy() {
        // Провал 12.03: страховка 9 000 + кафе 2 000; зп 15.03 возвращает вверх
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(planNamed(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 9_000, "Страховка"),
                        planNamed(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 2_000, "Кафе"),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 15), 100_000, Priority.HIGH))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(r.minPoint().drivenBy()).isEqualTo("Страховка");

        // Минимум в день 0 (трат в будущем нет) → drivenBy null
        PocketResultDto flat = PocketEngine.calculate(base().build());
        assertThat(flat.minPoint().drivenBy()).isNull();
    }
```

- [ ] **Step 2: Прогнать — падает** (нет `drivenBy()`), `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketEngineTest`

- [ ] **Step 3: Реализовать**

`PocketResultDto`: `public record MinPoint(LocalDate date, BigDecimal balance, String drivenBy) {}`

`PocketEngine`: в цикле дня отслеживать крупнейший расход дня:
```java
            BigDecimal dayTopExpenseAmount = BigDecimal.ZERO;
            String dayTopExpense = null;
            // в ветке else (расход): if (amount.compareTo(dayTopExpenseAmount) > 0) { dayTopExpenseAmount = amount; dayTopExpense = e.description(); }
```
При обновлении минимума запоминать `minDrivenBy = dayTopExpense` (объявить рядом с minDate, инициализировать null). Конструирование результата: `new PocketResultDto.MinPoint(minDate, minBalance, minDrivenBy)`.

- [ ] **Step 4: Полный прогон зелёный** (`./mvnw test`, 165 тестов), **Step 5: Commit** `feat(pocket): minPoint.drivenBy — culprit expense of the dip day (ANO-13)` (пути: три файла выше).

### Task 2: buildPocketPhrase + vitest

**Files:**
- Create: `frontend/src/lib/pocketPhrase.ts`
- Test: `frontend/src/lib/pocketPhrase.test.ts`
- Modify: `frontend/src/types/api.ts` (minPoint: `{ date: string; balance: number; drivenBy: string | null }`)

- [ ] **Step 1: Тип** в types/api.ts.

- [ ] **Step 2: Падающие тесты** — хелпер `make(overrides)` собирает минимальный PocketResponse (horizon NEXT_INCOME 15.07, trajectory из 3 точек, буфер 0). Кейсы:

1. провал в середине: `Свободно 36 000 ₽ до дохода 15.07. Самый узкий день — 12.07: на счёте останется 36 000 ₽ («Страховка»). После дохода → 129 000 ₽.` (буфер 0 → min = pocket)
2. буфер > 0: min 41 000, буфер 5 000, pocket 36 000 → `…на счёте останется 41 000 ₽ («Страховка»). Буфер 5 000 ₽ уже отложен. После дохода → …`
3. трат до горизонта нет (min в день 0): `Свободно X ₽ до дохода 15.07 — трат по плану до конца горизонта нет.…`
4. дефицит с выходом в плюс: min −49 094 → `Свободных денег нет: к 13.07 по плану не хватит 49 094 ₽. Доход 15.07 выведет в 25 906 ₽.`
5. дефицит без выхода: последняя точка ≤ 0 → `…Даже доход 15.07 не выведет в плюс — план требует правки.`
6. буфер прогрызен (min > 0, pocket < 0): `Впритык: в узкий день 12.07 на счёте останется 3 000 ₽ — меньше буфера 5 000 ₽.…`
7. фолбэк-горизонт: `Свободно X ₽ на 30 дней вперёд (плановых доходов нет)…`, без хвоста «после дохода»
8. скоуп MONTHS: горизонт из `horizon.label`, без хвоста «после дохода»

Run: `cd frontend && npx vitest run src/lib/pocketPhrase.test.ts` → FAIL (модуля нет).

- [ ] **Step 3: Реализовать** `pocketPhrase.ts`:

```typescript
import type { PocketResponse } from '../types/api';

const fmtC = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);
const fmtD = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${d}.${m}`;
};

/**
 * Адаптивная фраза-ответ кармашка (ANO-13): объясняет число (оно уже dip-aware),
 * называет узкий день и виновника, говорит «что дальше». Собирается из структурных
 * полей /pocket — бэкенд текст не генерирует (спека §3.6, дополнение 2026-07-10).
 */
export function buildPocketPhrase(p: PocketResponse): string {
    const { pocket, buffer, minPoint, horizon, trajectory } = p;
    const last = trajectory[trajectory.length - 1];
    const isNextIncome = horizon.type === 'NEXT_INCOME' && !horizon.fallback;
    const afterIncome = isNextIncome ? last.balance : null;
    const minDate = fmtD(minPoint.date);
    const horizonPart = horizon.fallback
        ? 'на 30 дней вперёд (плановых доходов нет)'
        : isNextIncome ? `до дохода ${fmtD(horizon.endDate)}` : horizon.label;
    const cause = minPoint.drivenBy ? ` («${minPoint.drivenBy}»)` : '';
    const afterTail = afterIncome != null && afterIncome > 0 ? ` После дохода → ${fmtC(afterIncome)}.` : '';

    // Дефицит: провал ниже нуля
    if (minPoint.balance < 0) {
        const tail = afterIncome == null ? ''
            : afterIncome > 0
                ? ` Доход ${fmtD(horizon.endDate)} выведет в ${fmtC(afterIncome)}.`
                : ` Даже доход ${fmtD(horizon.endDate)} не выведет в плюс — план требует правки.`;
        return `Свободных денег нет: к ${minDate} по плану не хватит ${fmtC(Math.abs(minPoint.balance))}${cause}.${tail}`;
    }

    // Буфер прогрызен: минимум положительный, но ниже буфера
    if (pocket < 0) {
        return `Впритык: в узкий день ${minDate} на счёте останется ${fmtC(minPoint.balance)} — меньше буфера ${fmtC(buffer)}${cause}.${afterTail}`;
    }

    const bufferPart = buffer > 0 ? ` Буфер ${fmtC(buffer)} уже отложен.` : '';

    // Трат до горизонта нет: минимум в день 0
    if (minPoint.date === trajectory[0].date) {
        return `Свободно ${fmtC(pocket)} ${horizonPart} — трат по плану до конца горизонта нет.${bufferPart}${afterTail}`;
    }

    return `Свободно ${fmtC(pocket)} ${horizonPart}. Самый узкий день — ${minDate}: на счёте останется ${fmtC(minPoint.balance)}${cause}.${bufferPart}${afterTail}`;
}
```

Примечание для тестов: `Intl.NumberFormat('ru-RU')` использует NBSP/NNBSP в числах — в ожидаемых строках сравнивать через тот же fmtC (собрать ожидание конкатенацией), не хардкодить пробелы.

- [ ] **Step 4: Тесты зелёные** (`npx vitest run`), **Step 5: Commit** `feat(pocket): adaptive answer phrase builder with threat and culprit (ANO-13)`.

### Task 3: PocketCard — фраза + «на счёте»

**Files:** Modify `frontend/src/components/PocketCard.tsx`

- [ ] **Step 1:** Импорт `buildPocketPhrase`. Заменить строку-подпись (`{data.horizon.label}{…минимум…}`) на:

```tsx
                            <p className="text-xs text-white/60 mt-0.5">на счёте {fmtC(data.currentBalance)}</p>
                            <p className="text-sm text-white/85 mt-2 leading-snug">{buildPocketPhrase(data)}</p>
```

(большое число `{fmtC(data.pocket)}` остаётся над ними; переключатель скоупов и breakdown не трогаем).

- [ ] **Step 2:** `npm run build` чистый. **Step 3: Commit** `feat(pocket): PocketCard speaks — phrase + on-account line (ANO-13)`.

### Task 4: Dashboard — кармашек = hero

**Files:** Modify `frontend/src/pages/Dashboard.tsx`

- [ ] **Step 1:**
1. Переместить `<PocketCard …/>` первым блоком страницы (перед карточкой hero).
2. Из старой hero-карточки удалить: заголовок «Текущий баланс» + большое число + весь блок зарплатных горизонтов (SalaryRow-строки и fallback «Прогноз конец месяца») — расчёт DashboardService расходится с кармашком (см. флаг в ANO-12). Компонент `SalaryRow` и переменная `balancePositive` становятся мёртвыми — удалить.
3. Карточка остаётся только с блоком «События сегодня» (рендерить карточку только при `todayEvents.length > 0`).
4. `endOfDayForecast` пересадить с `data.currentBalance` на `pocket?.currentBalance ?? 0`; строку «Прогноз конца дня» рендерить только при загруженном pocket.
5. `data` (fetchDashboard) остаётся для cashGapAlert и progressBars — их не трогаем (спека §8.3).
6. Мёртвые импорты убрать (TrendingUp/TrendingDown, если больше не используются — проверить: они используются в SalaryRow и fallback-строке; после их удаления, вероятно, мертвы).

- [ ] **Step 2:** `npm run build` + `npx vitest run` чистые. **Step 3:** Превью: Dashboard открывается кармашком-hero с фразой (на реальных данных — дефицит-фраза «Свободных денег нет: к 13.07…»); календарь ниже; «Сегодня» без большого числа. **Step 4: Commit** `feat(dashboard): pocket answer becomes the hero, lying salary rows removed (ANO-13)`.

### Task 5: Финализация

- [ ] Linear: ANO-13 → In Progress → комментарий с итогом (решения: hero, тон, поправка «число уже dip-aware»; drivenBy; что удалено с Dashboard) → Done после merge.
- [ ] PR в main (finishing-a-development-branch), в теле — скриншот-факты из превью.

**Вне скоупа:** «до второй зп» (вернётся в ANO-14 из растянутого скоупа), визуал календаря (ANO-14), cashGapAlert/progressBars (живут от DashboardService до ANO-14+).
