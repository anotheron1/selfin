import { useState } from 'react';
import { giveConsent, SIGN_IN_WITH_TELEGRAM } from '../api/client';
import { Button } from '../components/ui/button';

const muted = { color: 'var(--color-text-muted)' };

/**
 * Свой экран входа (ANO-152): на него уводит 401. «Войти через Telegram» ведёт на наш сервер с подсказкой
 * провайдера — Keycloak сразу уводит к подтверждению в Telegram, своей страницы не показывает. Яндекс ID и
 * VK ID — позже, кнопки неактивны.
 *
 * Согласие (ANO-196): без галочки кнопка неактивна — без красных надписей (правило 12). Галочка уходит на сервер до
 * Keycloak, и только с ней сервер пускает дальше. Ставится при каждом входе: до входа сервер не знает, кто пришёл.
 * Сюда же уводит 403 «нужно согласие» — вошедшему, которого сервер ещё не завёл.
 */
export default function SignIn() {
    const [agreed, setAgreed] = useState(false);
    const [busy, setBusy] = useState(false);
    const [failed, setFailed] = useState(false);

    const signIn = async () => {
        setBusy(true);
        setFailed(false);
        try {
            await giveConsent();
            window.location.assign(SIGN_IN_WITH_TELEGRAM);
        } catch {
            setFailed(true);
            setBusy(false);
        }
    };

    return (
        <div className="min-h-dvh flex flex-col items-center justify-center gap-6 px-6">
            <h1 className="text-2xl font-bold">Selfin</h1>
            <div className="w-full max-w-xs space-y-3">
                <label className="flex items-start gap-2 text-sm">
                    <input type="checkbox" className="mt-0.5 h-4 w-4 shrink-0" checked={agreed}
                           onChange={e => setAgreed(e.target.checked)} />
                    <span>
                        Согласен на{' '}
                        <a href="/consent" target="_blank" rel="noopener" className="underline">обработку персональных данных</a>
                    </span>
                </label>
                <Button className="w-full" disabled={!agreed || busy} onClick={signIn}>Войти через Telegram</Button>
                <Button variant="outline" className="w-full" disabled>Яндекс ID — скоро</Button>
                <Button variant="outline" className="w-full" disabled>VK ID — скоро</Button>
                {failed && <p className="text-xs text-center" style={muted}>Не получилось — обновите страницу и попробуйте ещё раз.</p>}
            </div>
            <p className="text-xs text-center max-w-xs" style={muted}>
                Из Telegram Selfin сохранит только имя и ник. Телефон не получит.{' '}
                <a href="/privacy" target="_blank" rel="noopener" className="underline">Политика обработки данных</a>
            </p>
        </div>
    );
}
