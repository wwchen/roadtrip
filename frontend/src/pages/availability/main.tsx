import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

// Phase 0 placeholder. Phase 2 rebuilds the pollers/runs/snapshots dashboard.
function AvailabilityPage() {
  return (
    <main>
      <h1>Availability Dashboard</h1>
      <p>React migration scaffold — Phase 0.</p>
    </main>
  );
}

const el = document.getElementById('root');
if (el) {
  createRoot(el).render(
    <StrictMode>
      <AvailabilityPage />
    </StrictMode>,
  );
}
