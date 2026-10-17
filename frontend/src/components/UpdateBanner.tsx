import { useEffect, useState } from 'react';
import { Button } from './ui/button';
import { serverCommit, versionWatch } from '../lib/versionWatch';

/**
 * Строка «вышло обновление» (ANO-222): сервер обновили, пока вкладка была открыта. Проверка — при открытии и когда
 * вкладка снова становится видимой. Перезагружает кнопка, а не сама вкладка: человек может быть посреди ввода.
 */
export default function UpdateBanner() {
    const [updated, setUpdated] = useState(false);

    useEffect(() => {
        const changed = versionWatch(serverCommit);
        void changed(); // запомнить коммит сервера при открытии
        // ponytail: только по возврату на вкладку; таймер — если выкладка застанет видимую вкладку посреди ввода
        const onVisible = () => {
            if (document.visibilityState === 'visible') void changed().then((yes) => yes && setUpdated(true));
        };
        document.addEventListener('visibilitychange', onVisible);
        return () => document.removeEventListener('visibilitychange', onVisible);
    }, []);

    if (!updated) return null;
    return (
        <div role="status" className="fixed top-0 inset-x-0 z-50 flex items-center justify-center gap-3 px-4 py-2 text-sm"
             style={{ background: 'var(--color-surface)', borderBottom: '1px solid var(--color-border)', color: 'var(--color-text)' }}>
            Вышло обновление Selfin
            <Button size="sm" onClick={() => window.location.reload()}>Обновить</Button>
        </div>
    );
}
