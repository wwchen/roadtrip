import { Link } from '@ui';
import { AtlasTree } from '@/features/atlas/AtlasTree';
import '@/features/atlas/atlas.css';

// The index page: a lede, the page nav every shell carries, and the tree. The
// tree owns its own loading/empty/error states, so the page is just the frame —
// mirroring how `AvailabilityPage` composes its tabs.
export function AtlasPage() {
  return (
    <main className="shell">
      <header className="top">
        <div>
          <h1>The Index — every park &amp; campground</h1>
          <div className="sub">
            Browse the whole catalog, one level at a time: regions, then parks, then
            campgrounds, down to individual sites.
          </div>
        </div>
        <nav className="nav" aria-label="Page links">
          <Link href="/">Map</Link>
          <Link href="/api/docs">API docs</Link>
        </nav>
      </header>

      <AtlasTree />
    </main>
  );
}
