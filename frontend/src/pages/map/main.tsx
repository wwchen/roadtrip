import '@ui/styles.css';
import { mountPage } from '@/app/mount';

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

mountPage(<MapPage />);
