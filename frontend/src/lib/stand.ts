/**
 * Своя машина — стенд и разработка: экран открыт с адреса этой машины. Только там экран входа показывает «Войти без
 * Telegram». Сервер открыт с публичного имени, и ни сборкой, ни настройкой это не изменить.
 *
 * Тот же признак и тот же список — у инструментов стенда: `ownIssuer` в `tools/keycloak-token.mjs`, по нему заводят
 * тестового человека и включают регистрацию. Сравнение — целиком: `localhost.example.com` и `127.0.0.1.nip.io` — чужие.
 *
 * Спека: docs/superpowers/specs/2026-11-10-stand-sign-in-design.md
 */
export const isOwnMachine = (hostname: string): boolean => ['127.0.0.1', 'localhost', '[::1]'].includes(hostname);
