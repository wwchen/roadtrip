import { Link } from '@ui';
import { ChangesTab } from '@/features/availability-dashboard/ChangesTab';
import { PollersTab } from '@/features/availability-dashboard/PollersTab';
import { RunsTab } from '@/features/availability-dashboard/RunsTab';
import { TabNav } from '@/features/availability-dashboard/TabNav';
import {
  TAB_CHANGES,
  TAB_POLLERS,
  TAB_RUNS,
  useTabRoute,
} from '@/features/availability-dashboard/useTabRoute';
import '@/features/availability-dashboard/dashboard.css';

/**
 * Rebuild of availability.html + web/availability.js on React.
 *
 * The legacy entry was a hand-rolled router: it cleared `#tab-root`'s innerHTML
 * and called the incoming tab's `mount(rootEl, …)`, so every switch tore down and
 * rebuilt the DOM and each tab re-fetched from scratch. Here the tabs are
 * components and TanStack Query holds the results, so switching away and back is
 * instant and served from cache.
 *
 * Each tab is keyed on the active tab, so switching genuinely remounts it. That
 * matters because the tabs seed their filter state from the URL once, and because
 * LDS's uncontrolled inputs reseed only on remount.
 */
export function AvailabilityPage() {
  const route = useTabRoute();

  return (
    <main className="shell">
      <header className="top">
        <div>
          <h1>Availability Dashboard</h1>
          <div className="sub">Live view of polling jobs, recent runs, and observed snapshots.</div>
        </div>
        <nav className="nav" aria-label="Page links">
          <Link href="/">Map</Link>
          <Link href="/api/docs">API docs</Link>
        </nav>
      </header>

      <TabNav route={route} />

      {route.tab === TAB_POLLERS && <PollersTab key={TAB_POLLERS} route={route} />}
      {route.tab === TAB_RUNS && <RunsTab key={TAB_RUNS} route={route} />}
      {route.tab === TAB_CHANGES && <ChangesTab key={TAB_CHANGES} route={route} />}
    </main>
  );
}
