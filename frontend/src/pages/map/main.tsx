import '@ui/styles.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

// Phase 0 placeholder. Phase 4 rebuilds the map app (MapProvider + imperative
// layer lifecycle, search, drawer, topbar/trip planner).
function MapPage() {
  return (
    <main>
      <h1>Roadtrip</h1>
      <p>React migration scaffold — Phase 0.</p>
    </main>
  );
}

const el = document.getElementById('root');
if (el) {
  createRoot(el).render(
    <StrictMode>
      <MapPage />
    </StrictMode>,
  );
}