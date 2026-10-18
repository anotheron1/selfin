/**
 * Согласие (ANO-196): код ответа «нужна галочка», одна версия текста на экране, сервере и в инструментах, и сторожа
 * по исходнику — экран входа, 403 в клиенте, страницы текстов. Компонентов в тестах нет (окружение node), поэтому
 * проводку экрана сторож читает в исходнике.
 *
 * Спека: docs/superpowers/specs/2026-10-18-consent-design.md
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { CONSENT_VERSION, needsConsent } from './consent';

const source = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('согласие: код ответа', () => {
    it('галочка нужна только на 403 с CONSENT_REQUIRED', () => {
        expect(needsConsent(403, ['CONSENT_REQUIRED'])).toBe(true);
        expect(needsConsent(403, [])).toBe(false);
        expect(needsConsent(401, ['CONSENT_REQUIRED'])).toBe(false);
    });
});

describe('согласие: версия текста одна на экране, сервере и в инструментах', () => {
    it('Consent.CURRENT на сервере — та же', () => {
        const java = source('../../../backend/src/main/java/ru/selfin/backend/service/Consent.java');
        expect(java.match(/CURRENT = "([^"]+)"/)?.[1]).toBe(CONSENT_VERSION);
    });

    it('CONSENT_VERSION в tools/keycloak-token.mjs — та же', () => {
        const tools = source('../../../tools/keycloak-token.mjs');
        expect(tools.match(/CONSENT_VERSION = '([^']+)'/)?.[1]).toBe(CONSENT_VERSION);
    });
});

describe('согласие: проводка экрана', () => {
    it('без галочки кнопка Telegram неактивна; галочка уходит на сервер до перехода на вход', () => {
        const signIn = source('../pages/SignIn.tsx');
        expect(signIn).toMatch(/disabled=\{!agreed/);
        expect(signIn).toMatch(/await giveConsent\(\);\s*window\.location\.assign\(SIGN_IN_WITH_TELEGRAM\)/);
    });

    it('на 403 «нужно согласие» клиент уводит на экран входа', () => {
        expect(source('../api/client.ts'))
            .toMatch(/if \(needsConsent\(res\.status, details\)\) \{[^}]*window\.location\.assign\(SIGN_IN_SCREEN\)/);
    });

    it('политика и согласие открываются без входа и без навигации приложения', () => {
        const app = source('../App.tsx');
        expect(app).toMatch(/'\/privacy': \(\) => <Privacy \/>/);
        expect(app).toMatch(/'\/consent': \(\) => <ConsentText \/>/);
    });
});

describe('согласие: тексты', () => {
    const legal = source('../pages/Legal.tsx');

    it('политика — шесть разделов рекомендаций Роскомнадзора', () => {
        for (const section of ['1. Общие положения', '2. Цели сбора', '3. Правовые основания', '4. Какие данные',
            '5. Как обрабатываются', '6. Уточнение, удаление и ответы на запросы']) {
            expect(legal).toContain(section);
        }
    });

    it('согласие говорит честно: где данные, кто имеет доступ, как отозвать и за сколько удаляем', () => {
        for (const point of ['Цели:', 'Какие данные:', 'Что с ними делают:', 'Где хранятся:', 'Срок:', 'Как отозвать:',
            'за пределами Российской Федерации', 'технический доступ', 'в течение 30 дней']) {
            expect(legal).toContain(point);
        }
    });
});
