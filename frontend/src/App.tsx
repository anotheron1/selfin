import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { returnedAfterPause } from './lib/screenSync';

import AppFrame, { SectionTrack } from './components/frame/AppFrame';
import QuickAddModal from './components/Fab';
import Analytics from './pages/Analytics';
import Capital from './pages/Capital';
import Budget from './pages/Budget';
import Funds from './pages/Funds';
import { ConsentText, Privacy } from './pages/Legal';
import Settings from './pages/Settings';
import SignIn from './pages/SignIn';
import Start from './pages/Start';
import Strategy from './pages/Strategy';
import UpdateBanner from './components/UpdateBanner';
import Today from './pages/Today';
import Wishlist from './pages/Wishlist';

/**
 * Журнал на широком экране — две колонки, основной список и ожидания (ANO-176), и в прежние
 * 672 px они не помещаются; «Деньги» (срез 4) — тоже две колонки. «Сегодня» (срез 2) раскладывает ширину сам: небо и
 * пейзаж — во всю ширину окна. Остальные экраны раскладываются под ширину в своих срезах.
 */
function Main({ children }: { children: React.ReactNode }) {
  const path = useLocation().pathname;
  const width = ['/', '/budget', '/capital'].includes(path) ? undefined : 'max-w-2xl mx-auto';
  return <main className={width}><SectionTrack />{children}</main>;
}

/**
 * Экран входа (ANO-152) и тексты политики и согласия (ANO-196) — сами по себе: без навигации и «+», им без входа
 * всё равно некуда вести. Тексты API не зовут — их читают до входа. Первый вход (срез 2) — тоже без навигации:
 * четыре вопроса, потом «Сегодня».
 */
const STANDALONE: Record<string, () => React.ReactElement> = {
  '/login': () => <SignIn />,
  '/start': () => <Start />,
  '/privacy': () => <Privacy />,
  '/consent': () => <ConsentText />,
};

function Screens() {
  const [refreshKey, setRefreshKey] = useState(0);
  const [adding, setAdding] = useState(false);
  const handleSuccess = () => setRefreshKey(k => k + 1);

  // Вернулся на вкладку или в приложение после минуты и дольше — экраны перечитываются (срез 3, п. 8, ANO-256):
  // так видна запись с телефона. Сервер о переменах сам не сообщает.
  useEffect(() => {
    let hiddenAt: number | null = null;
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') { hiddenAt = Date.now(); return; }
      if (returnedAfterPause(hiddenAt, Date.now())) setRefreshKey(k => k + 1);
      hiddenAt = null;
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  const standalone = STANDALONE[useLocation().pathname];
  if (standalone) return standalone();

  return (
    <>
      <AppFrame onAdd={() => setAdding(true)} onChanged={handleSuccess} refreshSignal={refreshKey}>
        <Main>
          <Routes>
            <Route path="/" element={<Today refreshSignal={refreshKey} />} />
            <Route path="/budget" element={<Budget refreshSignal={refreshKey} />} />
            <Route path="/funds" element={<Funds refreshSignal={refreshKey} />} />
            <Route path="/analytics" element={<Analytics />} />
            <Route path="/capital" element={<Capital refreshSignal={refreshKey} />} />
            <Route path="/strategy" element={<Strategy />} />
            <Route path="/wishlist" element={<Wishlist />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </Main>
      </AppFrame>
      {adding && <QuickAddModal onClose={() => setAdding(false)} onSuccess={handleSuccess} />}
      <UpdateBanner />
    </>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Screens />
    </BrowserRouter>
  );
}
