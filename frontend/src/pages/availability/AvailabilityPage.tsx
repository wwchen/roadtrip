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

// Switching tabs remounts tab-local filters; parameter changes within the active
// tab do not reseed them because that tab owns those URL updates.
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
          <Link href="/plan.html">Plan</Link>
          <Link href="/api/docs">API docs</Link>
        </nav>
      </header>

      <TabNav route={route} />

      {route.tab === TAB_POLLERS && <PollersTab route={route} />}
      {route.tab === TAB_RUNS && <RunsTab route={route} />}
      {route.tab === TAB_CHANGES && <ChangesTab route={route} />}
    </main>
  );
}
