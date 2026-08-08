import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { WatchesPage } from './WatchesPage';

const el = document.getElementById('root');
if (el) {
  createRoot(el).render(
    <StrictMode>
      <WatchesPage />
    </StrictMode>,
  );
}
