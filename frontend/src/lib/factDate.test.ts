import { afterAll, beforeAll, describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { canRecordFact, latestAllowedIso, SERVER_TIME_ZONE, todayIso } from './factDate';

describe('canRecordFact (ANO-155)', () => {
    const TODAY = '2026-09-15';

    it('вчерашним числом записать можно', () => {
        expect(canRecordFact('2026-09-14', TODAY)).toBe(true);
    });

    it('сегодняшним можно: деньги уйти уже могли', () => {
        expect(canRecordFact(TODAY, TODAY)).toBe(true);
    });

    it('завтрашним нельзя: в будущем деньги уйти не могли', () => {
        expect(canRecordFact('2026-09-16', TODAY)).toBe(false);
    });

    it('пустую дату записать нельзя', () => {
        expect(canRecordFact('', TODAY)).toBe(false);
    });

    it('сравнение календарное, а не строковое по длине', () => {
        // 2026-09-09 < 2026-09-15 — лексикография ISO совпадает с календарём
        expect(canRecordFact('2026-09-09', TODAY)).toBe(true);
        expect(canRecordFact('2026-10-01', TODAY)).toBe(false);
    });
});

describe('todayIso (ANO-155)', () => {
    it('берёт местную дату, а не UTC', () => {
        // 31 декабря 23:00 по местному: UTC-версия дала бы 1 января.
        expect(todayIso(new Date(2026, 11, 31, 23, 0, 0))).toBe('2026-12-31');
    });

    it('дополняет месяц и день нулями', () => {
        expect(todayIso(new Date(2026, 0, 5))).toBe('2026-01-05');
    });
});

describe('todayIso в Москве после полуночи — местная дата (ANO-220)', () => {
    // 23:00 31 декабря не отличает местную дату от UTC ни в Москве, ни в UTC, то есть в CI.
    // В 00:30 по Москве UTC — ещё вчера: так поля дат отставали на день до правки.
    const tz = process.env.TZ;
    beforeAll(() => { process.env.TZ = 'Europe/Moscow'; });
    afterAll(() => { process.env.TZ = tz; });

    it('пояс действительно московский — иначе тест зелёный и с дефектом', () => {
        expect(new Date(2026, 9, 18).getTimezoneOffset()).toBe(-180);
    });

    it('00:30 18 октября — 18-е, а не 17-е по UTC', () => {
        expect(todayIso(new Date('2026-10-17T21:30:00Z'))).toBe('2026-10-18');
    });
});

describe('latestAllowedIso — не позже сегодня ни по местным часам, ни по часам сервера (ревью Codex на #177)', () => {
    // Сервер судит «не позже сегодня» по Москве. Восточнее после местной полуночи местная дата для него ещё
    // завтрашняя: поле с такой датой он отвергнет, а форма промолчит.
    const tz = process.env.TZ;
    afterAll(() => { process.env.TZ = tz; });
    const at = (zone: string, iso: string) => {
        process.env.TZ = zone;
        return { offset: new Date(iso).getTimezoneOffset(), local: todayIso(new Date(iso)), allowed: latestAllowedIso(new Date(iso)) };
    };

    it('в Москве в 00:30 — сегодня', () => {
        expect(at('Europe/Moscow', '2026-10-17T21:30:00Z')).toEqual({ offset: -180, local: '2026-10-18', allowed: '2026-10-18' });
    });

    it('в Екатеринбурге в 01:00 — дата сервера: в Москве ещё 23:00 вчерашнего дня', () => {
        expect(at('Asia/Yekaterinburg', '2026-10-17T20:00:00Z')).toEqual({ offset: -300, local: '2026-10-18', allowed: '2026-10-17' });
    });

    it('в Калининграде в 23:30 — местная дата: в Москве уже завтра, но человеку — нет', () => {
        expect(at('Europe/Kaliningrad', '2026-10-17T21:30:00Z')).toEqual({ offset: -120, local: '2026-10-17', allowed: '2026-10-17' });
    });

    it('пояс сервера — тот же, что TZ в compose, и переменной его не переопределить', () => {
        // Переменная TZ в .env развела бы экран и сервер: экран считал бы предел по SERVER_TIME_ZONE,
        // а сервер — по своему поясу, и запись снова получала бы 400 молча (второй круг Codex на #177).
        for (const file of ['../../../docker-compose.yml', '../../../deploy/docker-compose.yml']) {
            const compose = readFileSync(new URL(file, import.meta.url), 'utf8');
            expect(compose).toContain(`TZ: ${SERVER_TIME_ZONE}`);
            expect(compose).not.toMatch(/TZ: \$\{TZ/);
        }
    });
});

describe('экраны берут «сегодня» одним способом (ревью #45, ANO-220)', () => {
    // В быстром добавлении умолчание даты считалось по UTC, а граница «не позже сегодня» —
    // по местной дате. Западнее Гринвича после полуночи по UTC форма объявляла будущим
    // собственное умолчание и прятала поле факта, пока дату не поправят руками. Восточнее —
    // наоборот: в Москве с полуночи до трёх ночи лист капитала и сверка остатка предлагали
    // вчерашнее число и не давали выбрать сегодняшнее (ANO-220). Сторож читает исходник:
    // поведенческого теста на это нет — компонентных тестов в проекте нет.
    const forms = [
        ['../components/Fab.tsx', 'todayIso('], ['../components/FactCreateSheet.tsx', 'todayIso('],
        ['../components/CapitalSheet.tsx', 'latestAllowedIso('], ['../pages/Settings.tsx', 'latestAllowedIso('],
        ['../components/CapitalTrajectoryChart.tsx', 'todayIso('],
    ];

    it.each(forms)('%s не берёт дату из toISOString()', (file, helper) => {
        const src = readFileSync(new URL(file, import.meta.url), 'utf8');
        expect(src).not.toMatch(/toISOString\(\)\s*\.slice/);
        expect(src).toContain(helper);
    });

    // Даты листа капитала и сверки остатка сервер проверяет @PastOrPresent: местная дата восточнее Москвы
    // ночью для него будущая (ревью Codex на #177).
    it.each(['../components/CapitalSheet.tsx', '../pages/Settings.tsx'])('%s — не позже даты сервера, а не просто местная дата', (file) => {
        expect(readFileSync(new URL(file, import.meta.url), 'utf8')).not.toMatch(/\btodayIso\(/);
    });
});
