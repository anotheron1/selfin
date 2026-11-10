/**
 * Вход на стенде без Telegram: кнопка экрана входа — только с адреса этой машины. Сервер открыт с публичного имени,
 * и кнопки на нём нет при любой сборке и настройке — это и держит тест.
 *
 * Экран собирается в строку (react-dom/server): окружение тестов — node, DOM нет. Нажатие так не проверить — проводку
 * нажатия сторож читает в исходнике, как у согласия (consent.test.ts).
 *
 * Спека: docs/superpowers/specs/2026-11-10-stand-sign-in-design.md
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { SIGN_IN_ON_STAND } from '../api/client';
import SignIn from '../pages/SignIn';
import { isOwnMachine } from './stand';

const BUTTON = 'Войти без Telegram';
const NOTE = 'Только на стенде';
const OWN = ['127.0.0.1', 'localhost', '[::1]'];
/** Сервер и адреса, похожие на «свои»: имя с localhost в начале, адрес этой машины внутри чужого имени, пусто. */
const FOREIGN = ['selfin.duckdns.org', 'localhost.example.com', '127.0.0.1.nip.io', 'notlocalhost', ''];

/** Экран входа, каким его покажет страница с этим адресом. */
function screenAt(hostname: string): string {
    vi.stubGlobal('location', { hostname });
    return renderToStaticMarkup(createElement(SignIn));
}

afterEach(() => vi.unstubAllGlobals());

describe('своя машина', () => {
    it('адрес этой машины — да; сервер и похожие на «свои» имена — нет', () => {
        for (const host of OWN) expect(isOwnMachine(host), host).toBe(true);
        for (const host of FOREIGN) expect(isOwnMachine(host), host).toBe(false);
    });
});

describe('экран входа: кнопка «Войти без Telegram»', () => {
    it('на адресе сервера её нет — ни кнопки, ни подписи', () => {
        for (const host of FOREIGN) {
            const screen = screenAt(host);
            // Экран собрался: иначе «кнопки нет» было бы правдой и у пустой строки.
            expect(screen, host).toContain('Войти через Telegram');
            expect(screen, host).not.toContain(BUTTON);
            expect(screen, host).not.toContain(NOTE);
        }
    });

    it('без адреса страницы — тоже нет', () => {
        const screen = renderToStaticMarkup(createElement(SignIn));
        expect(screen).toContain('Войти через Telegram');
        expect(screen).not.toContain(BUTTON);
    });

    it('на стенде она есть, с подписью, и без галочки неактивна — как кнопка Telegram', () => {
        for (const host of OWN) {
            const screen = screenAt(host);
            expect(screen, host).toContain(NOTE);
            expect(screen, host).toMatch(new RegExp(`<button[^>]*disabled=""[^>]*>${BUTTON}</button>`));
        }
    });
});

describe('экран входа: проводка кнопки «Войти без Telegram»', () => {
    const source = readFileSync(new URL('../pages/SignIn.tsx', import.meta.url), 'utf8');

    it('идёт тем же путём, что кнопка Telegram: галочка на сервер, потом переход — другого перехода на экране нет', () => {
        // Адрес — вместе с подписью кнопки: перепутанные адреса двух кнопок увели бы стенд в Telegram, а сервер — на форму.
        expect(source).toMatch(/onClick=\{\(\) => signIn\(SIGN_IN_ON_STAND\)\}>Войти без Telegram</);
        expect(source).toMatch(/await giveConsent\(\);\s*window\.location\.assign\(to\)/);
        expect(source.match(/location\.assign/g)).toHaveLength(1);
    });

    it('адрес — без provider: Keycloak показывает свою страницу, а не уводит в Telegram', () => {
        expect(SIGN_IN_ON_STAND).toBe('/oauth2/authorization/keycloak');
    });
});
