// Phase 0 placeholder. Phase 1 rebuilds this on LDS components + React Hook
// Form + TanStack Query, replacing web/watches/watches-page.js.
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
          <a className="outside-link" href="/">Map</a>
          <a className="outside-link" href="/availability.html">Dashboard</a>
        </nav>
      </header>
      <p>React migration scaffold — Phase 0.</p>
    </main>
  );
}
