/**
 * ANO-155: можно ли записать факт этой датой.
 *
 * Факт значит «деньги ушли». В будущем они уйти не могли, поэтому дата факта — не позже
 * сегодня; граница включительно, сегодня деньги уйти уже могли.
 *
 * Запрет стоит на вводе, а не в расчёте: окно остатка право, отсекая факты позже сегодня —
 * банковское число их содержать не может. Факт с будущей датой не попадал в остаток и при
 * этом снимал родительский план с траектории: расход исчезал с обеих сторон, и кармашек
 * рос ровно на сумму плана.
 *
 * Граница та же, что у серверной проверки: форма не должна предлагать того, что сервер
 * отвергнет, — ошибку человек всё равно не увидит, пока не починен ANO-141.
 *
 * Даты сравниваются как строки: ISO-формат yyyy-mm-dd лексикографически совпадает
 * с календарным порядком.
 */
export function canRecordFact(dateIso: string, todayIso: string): boolean {
    return dateIso !== '' && dateIso <= todayIso;
}

/** Сегодня в ISO-формате, местной датой (не UTC — иначе вечером уезжает на день назад). */
export function todayIso(now: Date): string {
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

/** Пояс сервера: по нему он судит «не позже сегодня» (`@PastOrPresent`, `TZ` в `docker-compose.yml`). */
export const SERVER_TIME_ZONE = 'Europe/Moscow';

/**
 * Предел для даты, которую сервер проверяет «не позже сегодня»: сегодня по местным часам, но не позже, чем
 * по часам сервера. В Москве это просто сегодня. Восточнее после местной полуночи местная дата для сервера
 * ещё завтрашняя — он отверг бы её, а форма промолчала бы (ревью Codex на #177). Пояс человека — эпик ANO-135.
 */
export function latestAllowedIso(now: Date): string {
    const parts = new Intl.DateTimeFormat('en-US', {
        timeZone: SERVER_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(now);
    const part = (type: string) => parts.find((p) => p.type === type)?.value;
    const server = `${part('year')}-${part('month')}-${part('day')}`;
    const local = todayIso(now);
    return local < server ? local : server;
}
