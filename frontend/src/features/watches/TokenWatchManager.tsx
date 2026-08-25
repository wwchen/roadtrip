// The alert-email "magic link" management view: reachable without a session
// via `/watches?action=modify&id=<id>&token=<token>`, scoped to exactly one
// watch. The backend's `?token=` gate (AvailabilityWatchRoutes) is the real
// authorization — this component just renders whatever it's allowed to do.
import { useEffect, useState } from 'react';
import { Banner, Button, Skeleton, Tag } from '@ui';
import { deleteWatch, getWatch, updateWatch, type Watch, type WatchStatus } from '@/api/watches-api';
import { formatWatchDate, watchFallbackName } from '@/lib/watch-format';

export interface TokenWatchManagerProps {
  id: string;
  token: string;
}

type LoadState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; watch: Watch };

const STATUS_LABELS: Record<WatchStatus, string> = {
  active: 'Active',
  paused: 'Paused',
  done: 'Done',
};

export function TokenWatchManager({ id, token }: TokenWatchManagerProps) {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [busy, setBusy] = useState(false);
  const [deleted, setDeleted] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setState({ status: 'loading' });
    getWatch(id, { token })
      .then((res) => {
        if (!cancelled) setState({ status: 'ready', watch: res.watch });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error' });
      });
    return () => {
      cancelled = true;
    };
  }, [id, token]);

  const setStatus = async (status: WatchStatus) => {
    setBusy(true);
    try {
      const res = await updateWatch(id, { status }, { token });
      setState({ status: 'ready', watch: res.watch });
    } catch {
      setState({ status: 'error' });
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await deleteWatch(id, { token });
      setDeleted(true);
    } catch {
      setState({ status: 'error' });
    } finally {
      setBusy(false);
    }
  };

  if (deleted) {
    return (
      <Banner status="success" role="status">
        Watch #{id} deleted. No more alerts will send for it.
      </Banner>
    );
  }

  if (state.status === 'loading') {
    return <Skeleton aria-label="Loading watch" />;
  }

  if (state.status === 'error') {
    return (
      <Banner status="error" role="alert">
        This link is invalid or has expired. Sign in to manage your alerts instead.
      </Banner>
    );
  }

  const { watch } = state;

  return (
    <div className="rt-watch-token-manager">
      <h1>{watchFallbackName(watch)}</h1>
      <div className="sub">
        Manage this alert — no sign-in needed, this link works for watch #{watch.id} only.
      </div>
      <div className="rt-watch-token-manager-summary">
        <Tag hue={watch.status === 'active' ? 'green' : watch.status === 'paused' ? 'yellow' : 'gray'} size="sm">
          {STATUS_LABELS[watch.status] ?? watch.status}
        </Tag>
        {formatWatchDate(watch.start_date) && (
          <span>{formatWatchDate(watch.start_date)}–{formatWatchDate(watch.end_date)}</span>
        )}
      </div>
      <div className="rt-watch-token-manager-actions">
        {watch.status !== 'done' && (
          <Button
            variant="secondary"
            disabled={busy}
            onClick={() => setStatus(watch.status === 'active' ? 'paused' : 'active')}
          >
            {watch.status === 'active' ? 'Pause alert' : 'Resume alert'}
          </Button>
        )}
        <Button variant="tertiary" hue="red" disabled={busy} onClick={remove}>
          Stop alert
        </Button>
      </div>
    </div>
  );
}
