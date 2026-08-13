// Planning Mode (RFC 0011, M1): the shelf of authored trip templates, and one
// template's timeline once picked. Read-only — trips are not persisted yet, so
// the selected template lives in page state rather than a URL or the server.
import { useState } from 'react';
import { Banner, EmptyState, Link, Skeleton } from '@ui';
import type { TripTemplate } from '@/api/planning-api';
import { TemplateShelf } from '@/features/plan/TemplateShelf';
import { TimelineView } from '@/features/plan/TimelineView';
import { useTripTemplates } from '@/features/plan/usePlanning';
import './plan.css';

export function PlanPage() {
  const [selected, setSelected] = useState<TripTemplate | null>(null);
  const { templates, isPending, error } = useTripTemplates();

  return (
    <main className="shell">
      <header className="top">
        <div>
          <h1>Plan</h1>
          <div className="sub">
            Pick a trip, pick a date — see the driving, the charging, and what needs booking.
          </div>
        </div>
        <nav className="nav">
          <Link href="/">Map</Link>
          <Link href="/availability.html">Dashboard</Link>
          <Link href="/watches.html">Watches</Link>
        </nav>
      </header>

      {isPending && <Skeleton />}

      {error ? (
        <Banner status="error" role="alert">
          Could not load trip templates.
        </Banner>
      ) : null}

      {!isPending && !error && templates.length === 0 && (
        <EmptyState title="No trips yet" description="No trip templates are configured." />
      )}

      {!isPending &&
        !error &&
        templates.length > 0 &&
        (selected ? (
          <TimelineView template={selected} onBack={() => setSelected(null)} />
        ) : (
          <TemplateShelf templates={templates} onSelect={setSelected} />
        ))}
    </main>
  );
}
