import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { useState } from 'react';

import BottomNav from './components/BottomNav';
import Fab from './components/Fab';
import Analytics from './pages/Analytics';
import Dashboard from './pages/Dashboard';
import Budget from './pages/Budget';
import Funds from './pages/Funds';
import Settings from './pages/Settings';

export default function App() {
  const [refreshKey, setRefreshKey] = useState(0);
  const handleSuccess = () => setRefreshKey(k => k + 1);

  return (
    <BrowserRouter>
      <div className="min-h-dvh overflow-x-hidden" style={{ paddingBottom: 'var(--nav-height)' }}>
        <main className="max-w-2xl mx-auto">
          <Routes>
            <Route path="/" element={<Dashboard refreshSignal={refreshKey} />} />
            <Route path="/budget" element={<Budget refreshSignal={refreshKey} />} />
            <Route path="/funds" element={<Funds refreshSignal={refreshKey} />} />
            <Route path="/analytics" element={<Analytics />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </main>
      </div>
      <Fab onSuccess={handleSuccess} />
      <BottomNav />
    </BrowserRouter>
  );
}
