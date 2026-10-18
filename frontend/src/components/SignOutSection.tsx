import { useEffect, useState } from 'react';
import { fetchMe } from '../api';
import { signOut } from '../api/client';
import { Button } from './ui/button';

/**
 * Кто вошёл и выход — внизу «Настроек» (ANO-152; решение владельца 15.10 — навигацию не трогаем).
 * Выход гасит и наш сеанс, и сеанс Keycloak — иначе «Войти» впустит снова без подтверждения в Telegram.
 */
export default function SignOutSection() {
    const [name, setName] = useState<string | null>(null);
    const [failed, setFailed] = useState(false);

    // Имя — подпись, не данные: не пришло — строки нет, а «Выйти» работает и без неё.
    useEffect(() => { fetchMe().then(me => setName(me.name)).catch(() => setName(null)); }, []);

    const leave = () => {
        setFailed(false);
        signOut().catch(() => setFailed(true));
    };

    return (
        <div className="rounded-2xl p-5 space-y-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h2 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>ВХОД</h2>
            {name && <p className="text-sm">Вы вошли как {name}</p>}
            {/* Политика — и после входа (ANO-196): в ней — что хранится и как удалить учётную запись. */}
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                <a href="/privacy" target="_blank" rel="noopener" className="underline">Политика обработки данных</a>
                {' '}— что хранится и как удалить учётную запись
            </p>
            <Button variant="outline" onClick={leave}>Выйти</Button>
            {failed && (
                <p className="text-xs" style={{ color: 'var(--color-danger)' }}>
                    Не получилось выйти. Попробуйте ещё раз.
                </p>
            )}
        </div>
    );
}
