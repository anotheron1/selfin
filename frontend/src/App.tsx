import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { useState } from 'react';

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
 * 672 px они не помещаются. «Сегодня» (срез 2) раскладывает ширину сам: небо и пейзаж — во всю ширину окна.
 * Остальные экраны раскладываются под ширину в своих срезах.
 */
function Main({ children }: { children: React.ReactNode }) {
  const path = useLocation().pathname;
  const width = path === '/' ? undefined : path === '/budget' ? 'max-w-2xl lg:max-w-6xl mx-auto' : 'max-w-2xl mx-auto';
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
