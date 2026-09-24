import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { useState } from 'react';

import BottomNav from './components/BottomNav';
import Fab from './components/Fab';
import Analytics from './pages/Analytics';
import Capital from './pages/Capital';
import Dashboard from './pages/Dashboard';
import Budget from './pages/Budget';
import Funds from './pages/Funds';
import Settings from './pages/Settings';
import Strategy from './pages/Strategy';
import Wishlist from './pages/Wishlist';

/**
 * Журнал на широком экране — две колонки, основной список и ожидания (ANO-176), и в прежние
 * 672 px они не помещаются. Остальным страницам хватает прежней ширины.
 */
function Main({ children }: { children: React.ReactNode }) {
  const wide = useLocation().pathname === '/budget';
  return <main className={wide ? 'max-w-2xl lg:max-w-6xl mx-auto' : 'max-w-2xl mx-auto'}>{children}</main>;
}

export default function App() {
  const [refreshKey, setRefreshKey] = useState(0);
  const handleSuccess = () => setRefreshKey(k => k + 1);

  return (
    <BrowserRouter>
      <div className="min-h-dvh overflow-x-hidden" style={{ paddingBottom: 'var(--nav-height)' }}>
        <Main>
          <Routes>
            <Route path="/" element={<Dashboard refreshSignal={refreshKey} />} />
            <Route path="/budget" element={<Budget refreshSignal={refreshKey} />} />
            <Route path="/funds" element={<Funds refreshSignal={refreshKey} />} />
            <Route path="/analytics" element={<Analytics />} />
            <Route path="/capital" element={<Capital refreshSignal={refreshKey} />} />
            <Route path="/strategy" element={<Strategy />} />
            <Route path="/wishlist" element={<Wishlist />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </Main>
      </div>
      <Fab onSuccess={handleSuccess} />
      <BottomNav />
    </BrowserRouter>
  );
}
