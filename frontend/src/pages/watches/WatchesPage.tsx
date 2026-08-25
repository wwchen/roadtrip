import { useCallback, useEffect, useState } from 'react';
import { Banner, EmptyState, Link, Skeleton } from '@ui';
import type { Watch, WatchStatus } from '@/api/watches-api';
import { HttpError } from '@/api/http';
import { watchLink } from '@/api/watch-link';
import {
  isUnauthorized,
  useDeleteWatch,
  useLoadWatchForEdit,
  usePoiNames,
  useSaveWatch,
  useSetWatchStatus,
  useWatches,
} from '@/features/watches/useWatches';
import { WatchForm, type WatchFormPrefill, type WatchFormSubmit } from '@/features/watches/WatchForm';
import { WatchLinkPanel } from '@/features/watches/WatchLinkPanel';
import { WatchTable } from '@/features/watches/WatchTable';
import {
  ACTION_CREATE,
  ACTION_DELETE,
  ACTION_MODIFY,
  useUrlAction,
} from '@/features/watches/useUrlAction';
import '@/features/watches/watches.css';

type Notice = { status: 'success' | 'error'; message: string };

/** Editor state: creating (optionally prefilled), or editing a loaded watch. */
type Editor =
  | { mode: 'create'; prefill: WatchFormPrefill | null }
  | { mode: 'edit'; watch: Watch };

const CREATE_EDITOR: Editor = { mode: 'create', prefill: null };

const SAVE_FALLBACK_MESSAGE = 'Could not save. Try again.';

/**
 * The identity of what the form is currently seeded from.
 *
 * Used as the form's React `key`: LDS controls are uncontrolled, so a reseed has
 * to be a remount. Everything the seed depends on must appear here — including
 * the deep-link prefill, which arrives after the first render.
 */
function formKey(editor: Editor, seq: number): string {
  if (editor.mode === 'edit') return `edit:${editor.watch.id}:${seq}`;
  const { poi_id = '', start_date = '' } = editor.prefill ?? {};
  return `create:${poi_id}:${start_date}:${seq}`;
}

export function WatchesPage() {
  const { watches, isPending, isSignedOut, error: listError, refetch } = useWatches();
  const poiNames = usePoiNames(watches);

  // Magic-link mode: the page was opened from an alert email by someone with no
  // session. It replaces the list, not the page, because the token authorizes
  // one watch and there is no list to show. A signed-in reader following the
  // same link gets the ordinary page — the session is the stronger credential
  // and the deep link below already lands them on the right watch.
  //
  // Which mode applies is not known until the list settles, so a link holds the
  // page on a skeleton until then rather than flashing the signed-in layout at
  // someone who is about to get the single-alert one.
  const link = watchLink();
  const resolvingLink = link != null && isPending;
  const linkWatchId = link != null && !isPending && isSignedOut ? link.watchId : null;
  const [linkStopped, setLinkStopped] = useState(false);

  const [editor, setEditor] = useState<Editor>(CREATE_EDITOR);
  // LDS form controls are uncontrolled (see WatchForm), so the form is reseeded
  // by remounting it. `formSeq` forces that for a reseed the editor identity
  // alone does not describe — clearing the fields after a successful create.
  const [formSeq, setFormSeq] = useState(0);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const saveWatch = useSaveWatch();
  const setWatchStatus = useSetWatchStatus();
  const deleteWatch = useDeleteWatch();
  const loadWatchForEdit = useLoadWatchForEdit();

  const resetForm = useCallback(() => {
    setEditor(CREATE_EDITOR);
    setFormError(null);
    setFormSeq((n) => n + 1);
  }, []);

  /** Empty-state "New watch": the create form is always mounted above the table, so
   * this just brings it into view and focuses its first field rather than opening
   * a second one. */
  const focusNewWatchForm = useCallback(() => {
    const host = document.getElementById('rt-watches-form-host');
    host?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    host?.querySelector<HTMLElement>('input, textarea, select')?.focus();
  }, []);

  const startEdit = useCallback(
    async (id: number | string) => {
      try {
        setEditor({ mode: 'edit', watch: await loadWatchForEdit(id) });
        setFormError(null);
      } catch {
        setNotice({ status: 'error', message: 'Could not load watch for editing.' });
      }
    },
    [loadWatchForEdit],
  );

  const removeWatch = useCallback(
    async (id: number | string, { fromUrl = false } = {}) => {
      try {
        await deleteWatch.mutateAsync(id);
        setNotice({ status: 'success', message: `Watch #${id} deleted.` });
        // Editing the watch that just went away would leave a form pointing at
        // nothing, so fall back to create.
        setEditor((e) => {
          if (e.mode !== 'edit' || String(e.watch.id) !== String(id)) return e;
          setFormSeq((n) => n + 1);
          return CREATE_EDITOR;
        });
        return true;
      } catch (err) {
        if (isUnauthorized(err) && !fromUrl) return false;
        setNotice({ status: 'error', message: 'Could not delete watch.' });
        return false;
      }
    },
    [deleteWatch],
  );

  /**
   * Stop, from a magic link. The confirmation replaces the panel only when the
   * delete actually landed — a failed stop must leave the alert on screen, still
   * running and still stoppable, rather than telling the reader it is gone.
   */
  const stopLinkedWatch = useCallback(
    async (id: number) => {
      if (await removeWatch(id, { fromUrl: true })) setLinkStopped(true);
    },
    [removeWatch],
  );

  // Deep links run only once the list has SUCCESSFULLY loaded and the caller is
  // signed in. The legacy page reached `applyUrlAction()` only after
  // `await loadWatches()` returned, so any list failure — 401 or otherwise —
  // skipped the action entirely; both conditions here reproduce that.
  //
  // `listError` belongs in `allowed`, not in `ready`. Putting it in `ready` would
  // leave the action armed instead of dropping it, so a user who pressed Retry
  // after a 500 would find `?action=delete&id=7` firing off the back of a click
  // that only asked to reload the list. Failing `allowed` consumes the action and
  // discards it for good, which is exactly how the signed-out case behaves.
  // (`listError` is the first NON-401 error — see useWatches — so a 401 still
  // settles `ready` and gets dropped rather than staying armed until a later
  // sign-in refetches the lists.)
  const urlAction = useUrlAction(!isPending, !isSignedOut && !listError);
  useEffect(() => {
    if (!urlAction) return;
    if (urlAction.kind === ACTION_CREATE) {
      setEditor({
        mode: 'create',
        prefill: {
          ...(urlAction.poiId ? { poi_id: urlAction.poiId } : {}),
          ...(urlAction.startDate ? { start_date: urlAction.startDate } : {}),
        },
      });
    } else if (urlAction.kind === ACTION_MODIFY) {
      void startEdit(urlAction.id);
    } else if (urlAction.kind === ACTION_DELETE) {
      void removeWatch(urlAction.id, { fromUrl: true });
    }
  }, [urlAction, startEdit, removeWatch]);

  const handleSubmit = async ({ id, body }: WatchFormSubmit) => {
    setFormError(null);
    try {
      await saveWatch.mutateAsync({ id, body });
      if (id != null) {
        const verb = (body as { status?: string }).status === 'active' ? 'reactivated' : 'updated';
        setNotice({ status: 'success', message: `Watch #${id} ${verb}.` });
      } else {
        const poiId = (body as { poi_id?: number | null }).poi_id;
        setNotice({ status: 'success', message: `Watch created for POI ${poiId}.` });
      }
      resetForm();
    } catch (err) {
      if (isUnauthorized(err)) return;
      // createWatch/updateWatch attach the response text as `.body`; it carries
      // the backend's validation detail, which is more useful than the status
      // line the message has.
      const detail = err instanceof HttpError ? err.body : undefined;
      setFormError(detail?.trim() || (err as Error)?.message || SAVE_FALLBACK_MESSAGE);
    }
  };

  const handleSetStatus = async (id: number, status: WatchStatus) => {
    try {
      await setWatchStatus.mutateAsync({ id, status });
    } catch (err) {
      if (isUnauthorized(err)) return;
      setNotice({ status: 'error', message: 'Could not update watch status.' });
    }
  };

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

      {notice && (
        <Banner status={notice.status} dismissible onDismiss={() => setNotice(null)} role="status">
          {notice.message}
        </Banner>
      )}

      {resolvingLink ? (
        <Skeleton aria-label="Opening alert" />
      ) : linkStopped && linkWatchId ? (
        <EmptyState
          title="Alert stopped"
          body="You will not get any more emails about it. Sign in any time to set up a new one."
        />
      ) : linkWatchId ? (
        <WatchLinkPanel
          watchId={linkWatchId}
          busy={saveWatch.isPending || deleteWatch.isPending}
          error={formError}
          onSubmit={handleSubmit}
          onStop={stopLinkedWatch}
        />
      ) : isSignedOut ? (
        <EmptyState
          title="Sign in to manage your alerts"
          body="Sign in to create and manage your availability alerts."
        />
      ) : (
        <>
          <div className="rt-watches-form-host" id="rt-watches-form-host">
            <WatchForm
              key={formKey(editor, formSeq)}
              mode={editor.mode}
              watch={editor.mode === 'edit' ? editor.watch : null}
              prefill={editor.mode === 'create' ? editor.prefill : null}
              loading={saveWatch.isPending}
              error={formError}
              onSubmit={handleSubmit}
              onCancel={resetForm}
            />
          </div>

          {listError ? (
            <Banner
              status="error"
              actions={
                <button type="button" onClick={refetch}>
                  Retry
                </button>
              }
            >
              Could not load watches.
            </Banner>
          ) : isPending ? (
            <Skeleton aria-label="Loading watches" />
          ) : (
            <WatchTable
              watches={watches}
              poiNames={poiNames}
              onEdit={startEdit}
              onSetStatus={handleSetStatus}
              onDelete={removeWatch}
              onNewWatch={focusNewWatchForm}
              busy={setWatchStatus.isPending || deleteWatch.isPending}
            />
          )}
        </>
      )}
    </main>
  );
}
