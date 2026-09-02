// The selected day's status line and its one action.
//
// It appears only for a day with no
// openings — a day with availability gets the site list instead, which is a more
// useful thing to look at than a sentence saying there is availability.
//
// The message below is the whole point of the component, and each branch exists
// because every other one would be a lie:
//   - a watch toggle, when there is inventory to monitor and someone to notify;
//   - "sign in", when the campground *could* alert this user but does not know them —
//     an offer, not a notice, so it carries the button that starts the flow;
//   - "checking", while we do not yet know which of those two it is;
//   - "couldn't check", when asking failed — with the retry that implies;
//   - "not available for this campground", when the provider cannot alert anyone;
//   - "no online openings to watch", when the day itself has nothing to wait for.
import { Button, LinkButton } from '@ui';
import { availabilityStatusMeta, normalizeAvailabilityStatus } from '@/lib/availability-status';
import { availableCount, campsiteCount } from '@/lib/day-fields';
import { dayCopy, gateCopy } from '@/lib/strings';
import type { FusedDay } from './fuse';
import { longDayLabel } from './week-labels';

/**
 * Why no watch can be set right now, when none can.
 *
 * One value per cause, because the copy differs per cause and only two of them are
 * actionable. Collapsing them onto a boolean is what made a slow request and a
 * failed one both read as "sign in" — to users who were already signed in.
 */
export type WatchUnavailableReason =
  /** This provider cannot notify anyone, by any channel. */
  | 'unsupported'
  /** The provider can, but we do not know who this visitor is. */
  | 'signed-out'
  /** The watch list is still in flight. */
  | 'loading'
  /** Asking for the watch list failed for some other reason. */
  | 'failed';

export interface DayDetailProps {
  day: FusedDay;
  /** Whether the user already has a watch on this day. */
  watching: boolean;
  /**
   * Why a watch cannot be set, or null when one can.
   *
   * Null *is* "can watch" — the two cannot disagree, which is why there is no
   * separate boolean beside it.
   */
  unavailable: WatchUnavailableReason | null;
  busy: boolean;
  /** Takes the clicked button so the watch editor can anchor to it. */
  onToggleWatch: (anchor: HTMLElement) => void;
  /** Re-ask for the watch list, offered with the `failed` message. */
  onRetryWatches: () => void;
  /** Starts the hosted sign-in flow from the `signed-out` message. */
  onSignIn: () => void;
}

export function DayDetail({
  day,
  watching,
  unavailable,
  busy,
  onToggleWatch,
  onRetryWatches,
  onSignIn,
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
          unavailable={unavailable}
          busy={busy}
          onToggleWatch={onToggleWatch}
          onRetryWatches={onRetryWatches}
          onSignIn={onSignIn}
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

function DayAction({
  day,
  watching,
  unavailable,
  busy,
  onToggleWatch,
  onRetryWatches,
  onSignIn,
}: DayDetailProps) {
  const status = normalizeAvailabilityStatus(day.status);
  // Closed and unknown days are excluded: there is no inventory state to monitor
  // on a closed day, and on an unknown one we would be promising to notice a
  // change we cannot currently see. An existing watch always keeps its control,
  // so a watch set on a day that has since gone closed can still be removed.
  const canAlert = status !== 'closed' && status !== 'unknown' && (unavailable === null || watching);

  if (canAlert) {
    return (
      <Button
        variant={watching ? 'secondary' : 'primary'}
        className="cg-day-alert"
        data-state={watching ? 'watching' : 'set'}
        disabled={busy}
        onClick={(event) => onToggleWatch(event.currentTarget as HTMLElement)}
      >
        {busy ? dayCopy.working : watching ? dayCopy.watching : dayCopy.setWatch}
      </Button>
    );
  }

  switch (unavailable) {
    case 'signed-out':
      return (
        <span className="cg-day-detail-meta">
          <LinkButton onClick={onSignIn}>{gateCopy.signIn}</LinkButton>
          {gateCopy.daySignedOutSuffix}
        </span>
      );
    case 'loading':
      return <span className="cg-day-detail-meta">{dayCopy.checking}</span>;
    case 'failed':
      return (
        <span className="cg-day-detail-meta">
          {dayCopy.checkFailed}{' '}
          <LinkButton className="cg-retry" onClick={onRetryWatches}>
            {dayCopy.retry}
          </LinkButton>
        </span>
      );
    case 'unsupported':
      return (
        <span className="cg-day-detail-meta">{dayCopy.unsupported}</span>
      );
    // Nothing is in the way: the day itself has nothing to wait for.
    case null:
      return <span className="cg-day-detail-meta">{dayCopy.nothingToWatch}</span>;
  }
}
