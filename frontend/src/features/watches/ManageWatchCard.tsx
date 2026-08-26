import { useCallback, useEffect, useRef, useState } from 'react';
import { Banner, Button, ConfirmButton, EmptyState, Link, Skeleton, Tag } from '@ui';
import type { Watch } from '@/api/watches-api';
import { useWatchPoiNames } from '@/domain/watch/queries';
import { formatWatchDate, watchFallbackName } from '@/lib/watch-format';
import type { MagicLink } from './magicLink';
import {
  useManagedWatch,
  useSetManagedWatchStatus,
  useStopManagedWatch,
} from './useManagedWatch';

const STATUS_LABELS: Record<Watch['status'], string> = {
  active: 'Active',
  paused: 'Paused',
  done: 'Done',
};

const STATUS_HUE: Record<Watch['status'], 'green' | 'yellow' | 'gray'> = {
  active: 'green',
  paused: 'yellow',
  done: 'gray',
};

const DEAD_LINK_TITLE = 'This alert has been stopped';
const DEAD_LINK_BODY =
  'The link in your email manages a single alert, and that one is no longer running. Sign in to see the rest of your alerts.';

export interface ManageWatchCardProps {
  link: MagicLink;
}

/**
 * The screen a magic link lands on.
 *
 * The email's "Stop watch" link arrives with `action=stop` and is carried out
 * here on load, as a POST — not by the GET that opened the page. Mail clients
 * and scanners prefetch links, so a URL that stopped a watch by being fetched
 * would fire for anyone whose provider does that; doing it in the page means a
 * prefetch that runs no scripts changes nothing.
 *
 * Stopping leaves the card on screen under a banner rather than replacing it,
 * so the reader can see which watch they just stopped.
 */
export function ManageWatchCard({ link }: ManageWatchCardProps) {
  const { watch, isPending, isLinkDead, error, refetch } = useManagedWatch(link);
  const setStatus = useSetManagedWatchStatus(link);
  const stopWatch = useStopManagedWatch(link);
  const [stopped, setStopped] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const poiNames = useWatchPoiNames(watch ? [watch] : []);

  const stopNow = useCallback(async () => {
    setActionError(null);
    try {
      await stopWatch.mutateAsync();
      setStopped(true);
    } catch {
      setActionError('Could not stop this watch. Try again.');
    }
  }, [stopWatch]);

  // The email's "Stop watch" link. Fires once — the ref guards React's
  // development double-invoke — and only after the watch has loaded, so the card
  // has something to show beneath the banner.
  const autoStopFired = useRef(false);
  useEffect(() => {
    if (!link.stopOnArrival || !watch || autoStopFired.current) return;
    autoStopFired.current = true;
    void stopNow();
  }, [link.stopOnArrival, watch, stopNow]);

  if (isLinkDead && !stopped) {
    return (
      <EmptyState
        icon="bell-off"
        title={DEAD_LINK_TITLE}
        body={DEAD_LINK_BODY}
        actions={<Link href="/watches">Go to your alerts</Link>}
      />
    );
  }

  if (error) {
    return (
      <Banner
        status="error"
        actions={
          <button type="button" onClick={refetch}>
            Retry
          </button>
        }
      >
        Could not load this alert.
      </Banner>
    );
  }

  if (isPending || !watch) return <Skeleton aria-label="Loading alert" />;

  // Mirrors WatchTable: anything not active resumes, so a `done` watch
  // reactivates rather than being pushed sideways into `paused`.
  const isActive = watch.status === 'active';
  const poiName = watch.poi_id != null ? poiNames.get(watch.poi_id) : null;
  const busy = setStatus.isPending || stopWatch.isPending;

  const handleSetStatus = async () => {
    setActionError(null);
    try {
      await setStatus.mutateAsync(isActive ? 'paused' : 'active');
    } catch {
      setActionError('Could not update this alert. Try again.');
    }
  };

  return (
    <section className="rt-manage-watch" aria-label="Manage this alert">
      {stopped && (
        <Banner status="success" role="status">
          Watch stopped. We won&apos;t email you about it again.
        </Banner>
      )}

      <header className="rt-manage-watch-head">
        <h2>{poiName ?? watchFallbackName(watch)}</h2>
        <Tag hue={STATUS_HUE[watch.status] ?? 'gray'} size="sm">
          {STATUS_LABELS[watch.status] ?? watch.status}
        </Tag>
      </header>

      <dl className="rt-manage-watch-facts">
        <dt>Nights</dt>
        <dd>
          {formatWatchDate(watch.start_date)} – {formatWatchDate(watch.end_date)}
        </dd>
      </dl>

      {actionError && (
        <Banner status="error" dismissible onDismiss={() => setActionError(null)} role="alert">
          {actionError}
        </Banner>
      )}

      {!stopped && (
        <div className="rt-manage-watch-actions">
          <Button variant="secondary" disabled={busy} onClick={handleSetStatus}>
            {isActive ? 'Pause alerts' : 'Resume alerts'}
          </Button>
          <ConfirmButton
            variant="secondary"
            hue="red"
            label="Stop watch"
            confirmAriaLabel="Confirm stop watch"
            disabled={busy}
            onConfirm={() => void stopNow()}
          />
        </div>
      )}

      <p className="rt-manage-watch-footnote">
        {stopped ? (
          <Link href="/watches">See your other alerts</Link>
        ) : (
          <>
            Pausing keeps the alert so you can turn it back on. Stopping deletes it. To
            change the dates or the campground, open your{' '}
            <Link href="/watches">watches page</Link>.
          </>
        )}
      </p>
    </section>
  );
}
