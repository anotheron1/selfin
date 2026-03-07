import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { useState } from 'react';

import BottomNav from './components/BottomNav';
import Fab from './components/Fab';
import Dashboard from './pages/Dashboard';
import Budget from './pages/Budget';
import Funds from './pages/Funds';
import Settings from './pages/Settings';

export default function App() {
  const [refreshKey, setRefreshKey] = useState(0);
  const handleSuccess = () => setRefreshKey(k => k + 1);

  return (
    <BrowserRouter>
      <div className="min-h-dvh" style={{ paddingBottom: 'var(--nav-height)' }}>
        <main className="max-w-2xl mx-auto">
          <Routes>
            <Route path="/" element={<Dashboard key={refreshKey} />} />
            <Route path="/budget" element={<Budget key={refreshKey} />} />
            <Route path="/funds" element={<Funds key={refreshKey} />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </main>
      </div>
      <Fab onSuccess={handleSuccess} />
      <BottomNav />
    </BrowserRouter>
  );
}
