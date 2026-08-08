import { Link } from '@ui';

// Phase 0 placeholder. Phase 1 rebuilds this on LDS components + a form library
// + TanStack Query, replacing web/watches/watches-page.js.
//
// The nav links already come from LDS through the `@ui` seam, so the vendored
// package, the theme cascade, and the typed React bindings are exercised by the
// build and the smoke test rather than only at Phase 1.
export function WatchesPage() {
  return (
    <main className="shell">
      <header className="top">
        <div>
          <h1>Watches</h1>
          <div className="sub">
            Manage availability watches — get notified when campsites open up.
          </div>
        </div>
        <nav className="nav">
          <Link href="/">Map</Link>
          <Link href="/availability.html">Dashboard</Link>
        </nav>
      </header>
      <p>React migration scaffold — Phase 0.</p>
    </main>
  );
}
