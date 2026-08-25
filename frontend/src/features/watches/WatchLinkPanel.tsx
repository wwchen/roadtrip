// The watches page as an alert-email magic link opens it.
//
// One watch, no list, no sign-in. Everything here works off the token the link
// carried (see `@/api/watch-link`), which the watch API attaches to its own
// requests — so this component asks for a watch exactly the way the signed-in
// page does and the difference lives one layer down.
import { useQuery } from '@tanstack/react-query';
import { Banner, Button, ConfirmButton, EmptyState, Skeleton } from '@ui';
import { signIn } from '@/api/auth-api';
import { getWatch, type Watch } from '@/api/watches-api';
import { isWatchUnauthorized } from '@/domain/watch/queries';
import { queryKeys } from '@/queries/keys';
import { WatchForm, type WatchFormSubmit } from './WatchForm';

export interface WatchLinkPanelProps {
  /** The watch the link names. */
  watchId: string;
  /** True while a save or stop is in flight. */
  busy?: boolean;
  /** Save error to show above the form, if the last save failed. */
  error?: string | null;
  onSubmit: (submission: WatchFormSubmit) => void;
  onStop: (id: number) => void;
}

/**
 * A dead link is the expected end state, not an error.
 *
 * Links expire, and a watch that was already stopped is gone. Both answer 401 or
 * 404 to a token, and both mean the same thing to the reader: this alert is no
 * longer yours to change from here. Saying so — with a way in — beats "Could not
 * load watch", which reads like something is broken.
 */
function isDeadLink(error: unknown): boolean {
  return isWatchUnauthorized(error) || (error as { status?: number } | null)?.status === 404;
}

export function WatchLinkPanel({
  watchId,
  busy = false,
  error = null,
  onSubmit,
  onStop,
}: WatchLinkPanelProps) {
  const {
    data,
    isPending,
    error: loadError,
    refetch,
  } = useQuery({
    queryKey: queryKeys.watches.detail(watchId),
    queryFn: ({ signal }) => getWatch(watchId, { signal }),
    retry: false,
  });

  if (isPending) return <Skeleton aria-label="Loading alert" />;

  if (loadError) {
    if (isDeadLink(loadError)) {
      return (
        <EmptyState
          title="This link has expired"
          body="Alert links stop working after a while, and an alert that was already stopped is gone for good. Sign in to see the alerts you still have."
        />
      );
    }
    return (
      <Banner
        status="error"
        actions={
          <button type="button" onClick={() => void refetch()}>
            Retry
          </button>
        }
      >
        Could not load this alert.
      </Banner>
    );
  }

  const watch: Watch = data.watch;
  const stopped = watch.status === 'done';

  return (
    <>
      <Banner status="info" role="status">
        You are managing one alert from an email link. Everything else needs a sign-in.
      </Banner>

      {stopped ? (
        <EmptyState
          title="This alert is stopped"
          body="It will not email you again. Saving it below starts it running once more."
        />
      ) : null}

      <div className="rt-watches-form-host" id="rt-watches-form-host">
        <WatchForm
          key={`link:${watch.id}:${watch.status}`}
          mode="edit"
          watch={watch}
          loading={busy}
          error={error}
          onSubmit={onSubmit}
        />
      </div>

      <div className="rt-watch-link-actions">
        {/* Stop is irreversible from a link — there is no list to undo it from —
            so it takes the two-click path rather than a bare button. */}
        <ConfirmButton
          variant="secondary"
          label="Stop this alert"
          confirmLabel="Stop it?"
          disabled={busy}
          onConfirm={() => onStop(watch.id)}
        />
        <Button variant="secondary" disabled={busy} onClick={() => signIn()}>
          Sign in to manage all your alerts
        </Button>
      </div>
    </>
  );
}
