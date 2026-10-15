import { SIGN_IN_WITH_TELEGRAM } from '../api/client';
import { Button } from '../components/ui/button';

/**
 * Свой экран входа (ANO-152): на него уводит 401. «Войти через Telegram» ведёт на наш сервер с подсказкой
 * провайдера — Keycloak сразу уводит к подтверждению в Telegram, своей страницы не показывает. Яндекс ID и
 * VK ID — позже, кнопки неактивны.
 */
export default function SignIn() {
    return (
        <div className="min-h-dvh flex flex-col items-center justify-center gap-6 px-6">
            <h1 className="text-2xl font-bold">Selfin</h1>
            <div className="w-full max-w-xs space-y-3">
                <Button asChild className="w-full">
                    <a href={SIGN_IN_WITH_TELEGRAM}>Войти через Telegram</a>
                </Button>
                <Button variant="outline" className="w-full" disabled>Яндекс ID — скоро</Button>
                <Button variant="outline" className="w-full" disabled>VK ID — скоро</Button>
            </div>
            <p className="text-xs text-center max-w-xs" style={{ color: 'var(--color-text-muted)' }}>
                Selfin получит из Telegram только имя и ник — без телефона.
            </p>
        </div>
    );
}
