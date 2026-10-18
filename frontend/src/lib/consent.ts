// Согласие на обработку персональных данных (ANO-196). Спека — 2026-10-18-consent-design.md.

/**
 * Версия текста согласия — дата редакции. Экран отправляет её с галочкой, сервер сверяет со своей: согласие на другой
 * текст не записывается. Та же — в `Consent.java` и `tools/keycloak-token.mjs`; сверяет `consent.test.ts`.
 */
export const CONSENT_VERSION = '2026-10-18';

/** Код ответа сервера: вошедшему, которого он без согласия не заводит, — 403 с этим кодом. */
export const CONSENT_REQUIRED = 'CONSENT_REQUIRED';

/** Нужна ли галочка: так сервер отвечает вошедшему, которого ещё не завёл. */
export const needsConsent = (status: number, details: string[]) => status === 403 && details.includes(CONSENT_REQUIRED);

/** Оператор персональных данных — владелец сервиса; так он назван в политике и в согласии (решение владельца 18.10). */
export const OPERATOR = {
    name: 'Вохмянин Кирилл Ильич',
    contact: '@AnotherOn1 в Telegram',
};
