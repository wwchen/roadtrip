// The selected day's status line and its one action.
//
// Port of web/availability/day-detail.js. It appears only for a day with *no*
// openings — a day with availability gets the site list instead, which is a more
// useful thing to look at than a sentence saying there is availability.
//
// The four-way message below is the whole point of the component, and each branch
// exists because the other three would be a lie:
//   - a watch toggle, when there is inventory to monitor and someone to notify;
//   - "sign in", when the campground *could* alert this user but does not know them;
//   - "not available for this campground", when the provider cannot alert anyone;
//   - "no online openings to watch", when the day itself has nothing to wait for.
import { availabilityStatusMeta, normalizeAvailabilityStatus } from '@/lib/availability-status';
import { availableCount, campsiteCount } from '@/lib/day-fields';
import type { FusedDay } from './fuse';
import { longDayLabel } from './week-labels';

export interface DayDetailProps {
  day: FusedDay;
  /** Whether the user already has a watch on this day. */
  watching: boolean;
  /** Whether a watch could be created: provider supports it AND the user is known. */
  canWatch: boolean;
  /**
   * The provider supports alerts but the user is anonymous.
   *
   * Distinct from `!canWatch` so the copy can say "sign in" rather than "this
   * campground cannot do that" — only one of those is worth acting on.
   */
  signedOut: boolean;
  busy: boolean;
  onToggleWatch: () => void;
}

export function DayDetail({
  day,
  watching,
  canWatch,
  signedOut,
  busy,
  onToggleWatch,
}: DayDetailProps) {
  return (
    <div className="cg-day-detail">
      <div className="cg-day-detail-head">
        <div className="cg-day-detail-date">{longDayLabel(day.date)}</div>
        <div className="cg-day-detail-meta">
          <StatusLine day={day} />
        </div>
      </div>
      <div className="cg-day-detail-actions">
        <DayAction
          day={day}
          watching={watching}
          canWatch={canWatch}
          signedOut={signedOut}
          busy={busy}
          onToggleWatch={onToggleWatch}
        />
      </div>
    </div>
  );
}

function StatusLine({ day }: { day: FusedDay }) {
  const meta = availabilityStatusMeta(day.status);
  if (meta.value !== 'available') return <span className={meta.detailClass}>{meta.text}</span>;
  return (
    <>
      <span className={meta.detailClass}>{meta.text}</span> · {availableCount(day)} of{' '}
      {campsiteCount(day)} sites
    </>
  );
}

function DayAction({ day, watching, canWatch, signedOut, busy, onToggleWatch }: DayDetailProps) {
  const status = normalizeAvailabilityStatus(day.status);
  // Closed and unknown days are excluded: there is no inventory state to monitor
  // on a closed day, and on an unknown one we would be promising to notice a
  // change we cannot currently see. An existing watch always keeps its control,
  // so a watch set on a day that has since gone closed can still be removed.
  const canAlert = status !== 'closed' && status !== 'unknown' && (canWatch || watching);

  if (canAlert) {
    return (
      <button
        type="button"
        className={`cg-btn ${watching ? 'cg-btn-secondary' : 'cg-btn-primary'} cg-day-alert`}
        data-state={watching ? 'watching' : 'set'}
        disabled={busy}
        onClick={onToggleWatch}
      >
        {busy ? 'Working…' : watching ? 'Watching - tap to remove' : 'Set watch'}
      </button>
    );
  }
  if (signedOut) {
    return <span className="cg-day-detail-meta">Sign in to set availability alerts.</span>;
  }
  if (!canWatch) {
    return <span className="cg-day-detail-meta">Watches are not available for this campground.</span>;
  }
  return <span className="cg-day-detail-meta">No online openings to watch for this day.</span>;
}
