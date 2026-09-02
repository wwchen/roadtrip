// The availability grid, mounted inside the campground drawer.
import { useCallback, useMemo, useState } from 'react';
import { Button, EmptyState, Icon, LinkButton, useToast } from '@ui';
import type { PoiFeature } from '@/lib/poi';
import { availableCount } from '@/lib/day-fields';
import { addLocalDays, localToday, localYmd, parseLocalYmd, sameLocalDay } from '@/lib/local-date';
import { copyShareUrl } from '@/lib/share-links';
import { settingsErrorMessage } from '@/lib/settings-errors';
import { addToCart } from '@/api/booking-api';
import { isCartActionPending } from './cart-action';
import { signIn } from '@/api/auth-api';
import { useMe } from '@/queries/auth';
import { useSettingsStore } from '@/stores/settingsStore';
import { bookingCopy, upstreamCopy } from '@/lib/strings';
import { DayDetail, type WatchUnavailableReason } from './DayDetail';
import { CalendarPopover } from './CalendarPopover';
import { SiteList } from './SiteList';
import { SiteMatrix, SiteMatrixSkeleton, type WatchGate } from './SiteMatrix';
import { WatchPopover } from './WatchPopover';
import { WeekNav } from './WeekNav';
import { GENERIC_AVAILABILITY_ERROR, classifyAvailabilityErrorCode } from './availability-errors';
import { reservationUrlFromTemplate } from './booking-links';
import type { FusedDay } from './fuse';
import { DEFAULT_SITE_COLUMN_WIDTH } from './site-column';
import { useAvailabilityController } from './availability-controller';
import { useCampsites } from './useCampsites';
import { useDelayedFlag } from './useDelayedFlag';
import {
  AvailabilityRequestError,
  SKELETON_RENDER_DELAY_MS,
  STALE_THRESHOLD_MIN,
  WEEK_DAYS,
  cacheAgeMinutes,
  useWeekAvailability,
} from './useWeekAvailability';
import { WatchAuthError, usePoiWatches, useWatchMutations, watchForDate } from './useWatches';
import {
  NO_WATCH_CAPABILITIES,
  stayEndDate,
  cartGate,
  supportsAddToCart,
  supportsWatchAlerts,
  watchedDates as watchedDatesOf,
} from '@/lib/watch-windows';
import { TRIGGER_KIND_SLACK_NOTIFY, buildTriggerPayload, triggerStateOf } from '@/lib/watch-triggers';
import './availability.css';

/** How far ahead the calendar lets someone jump — every provider's horizon or less. */
const CALENDAR_MAX_DAYS_OUT = 365;

export interface AvailabilityWeekProps {
  /** The hydrated campground feature: supplies the POI id, name and earliest date. */
  feature: PoiFeature;
}

export function AvailabilityWeek({ feature }: AvailabilityWeekProps) {
  const poiId = feature.id;

  // No id, no availability — and specifically not a skeleton. Every request below is
  // keyed on the POI id, so without one there is nothing to fetch and no week to
  // show: the grid would render a loading skeleton that can never resolve. The
  // drawer's own gate is `properties.availability_supported`, which says nothing
  // about the id, so the two can disagree — a body with the flag set and no
  // top-level `id` is exactly the case this catches. Rendering nothing is the honest
  // answer, and matches what the section looks like for an unsupported provider.
  //
  // The gate lives out here, ahead of every hook, because a component that returns
  // early *after* calling some of its hooks changes its own hook count the moment
  // the id arrives — which React rejects outright.
  if (poiId == null) return null;

  // Keyed on the POI id, which is what makes all of the state below POI-scoped.
  // Clicking a second campground pin does not unmount this: the drawer stays
  // mounted and re-renders with the new feature, immediately, because React Query
  // usually has it cached. Without the key the new campground would inherit the
  // previous one's visible week, selected day, expanded site, armed booking cell and
  // filter text — `weekStart` in particular is seeded from `earliestDate` once and
  // never again, so a campground that opens in October would be shown a week its
  // provider will not quote for. A remount resets all of it at once and cannot be
  // forgotten the way a per-field reset effect can. The one piece that deliberately
  // survives is the Site column width, and it survives because it is persisted: it
  // is a preference about the grid, not a fact about one campground, so re-reading
  // it from storage on remount is the same value the user dragged it to.
  return <AvailabilityWeekView key={String(poiId)} poiId={poiId} feature={feature} />;
}

function AvailabilityWeekView({
  poiId,
  feature,
}: {
  poiId: string | number;
  feature: PoiFeature;
}) {
  const poiName = (feature.properties?.name as string | undefined) || 'this campground';

  // The first date this provider will quote. Everything paginates forward from here,
  // and "Earliest" returns to it — which is not the same as "today" for a campground
  // that only opens a booking window months out.
  const earliestDate = useMemo(() => featureEarliestDate(feature), [feature]);
  const openSettings = useSettingsStore((state) => state.openSettings);
  const { state, actions } = useAvailabilityController(earliestDate);
  const {
    weekStart,
    selectedDate,
    selectedSiteId,
    sitesExpanded,
    armedBook,
    cartAction,
    filters,
    siteColumnWidth,
    calendarOpen,
    watchTarget,
  } = state;

  const { toast } = useToast();
  const week = useWeekAvailability(poiId, weekStart);
  const catalog = useCampsites(poiId);
  const watches = usePoiWatches(poiId);
  const mutations = useWatchMutations(poiId);

  const showSkeleton = useDelayedFlag(week.isPending, SKELETON_RENDER_DELAY_MS);

  const goToWeek = useCallback(
    (next: Date) => {
      actions.changeWeek(next < earliestDate ? earliestDate : next);
    },
    [actions, earliestDate],
  );

  const days: FusedDay[] = week.data?.state === 'success' ? week.data.days : [];
  const selectedDay = days.find((day) => day.date === selectedDate) ?? null;
  // The shared empty value, not a fresh object per render: it is the identity every
  // capability gate below compares against, and a new `Set` each time would make the
  // memoised callbacks that read it churn for no reason.
  const capabilities = week.data?.watchCapabilities ?? NO_WATCH_CAPABILITIES;

  // Provider first, user second: "this campground cannot do alerts" and "sign in to
  // set one" are different sentences, and only the second is actionable — as are
  // "we are still asking" and "asking failed", which is why this is a reason rather
  // than a boolean. `watches.canManage` alone reported all three of loading,
  // anonymous and failed as anonymous.
  const watchUnavailable: WatchUnavailableReason | null = !supportsWatchAlerts(capabilities)
    ? 'unsupported'
    : watches.access === 'unauthorized'
      ? 'signed-out'
      : watches.access === 'loading'
        ? 'loading'
        : watches.access === 'error'
          ? 'failed'
          : null;
  const watchGate: WatchGate =
    watchUnavailable === null ? 'ready' : watchUnavailable === 'signed-out' ? 'signed-out' : 'blocked';

  const onSelectDate = useCallback(
    (date: string) => {
      // The reducer owns the coupled site-list and booking reset rules.
      actions.selectDate(date);
    },
    [actions],
  );

  // The same condition that enables the watch editor's ATC toggle: the scope
  // supports a cart AND this caller has credentials. One source of truth, so a
  // user can never be offered a hold the write path would refuse.
  // The scope's cart and this caller's ability to drive it are separate facts, and
  // the gate is what lets the grid say which of the two is missing.
  const signedIn = Boolean(useMe().data?.user);
  const cart = cartGate(capabilities, signedIn);

  const holdSite = useCallback(
    (campsiteId: string, date: string) => {
      // The reducer refuses a second in-flight hold, but the fetch below is not
      // the reducer's to stop: without this the request really went out, with
      // no cell to show for it and a toast about a cell the user never armed.
      if (isCartActionPending(cartAction)) {
        toast({
          status: 'warning',
          title: bookingCopy.holdBusyTitle,
          children: bookingCopy.holdBusyBody,
        });
        return;
      }
      const cell = { campsiteId, date };
      actions.cartActionChanged({ type: 'requested', cell });
      void addToCart({ campsite_id: Number(campsiteId), start_date: date, end_date: stayEndDate(date) })
        .then((answer) => {
          actions.cartActionChanged({ type: 'held', cell, cartUrl: answer.cart_url });
          toast({
            status: 'success',
            title: bookingCopy.heldTitle,
            children: (
              <>
                {bookingCopy.checkOutSoon}{' '}
                <a href={answer.cart_url} target="_blank" rel="noreferrer noopener">
                  {bookingCopy.openCart}
                </a>
              </>
            ),
          });
        })
        .catch((err: unknown) => {
          const code = (err as { code?: string } | null)?.code;
          actions.cartActionChanged({ type: 'failed', cell, code: code ?? '' });
          toast({
            status: 'warning',
            title: 'Could not hold the site',
            children: settingsErrorMessage(code),
          });
        });
    },
    [actions, cartAction, toast],
  );

  const openBooking = useCallback(
    (campsiteId: string, date: string) => {
      const site = catalog.data?.campsites.find((row) => String(row.id) === campsiteId);
      const url = site
        ? reservationUrlFromTemplate(site, {
            startDate: date,
            endDate: stayEndDate(date),
            reservationUrlTemplates: catalog.data?.reservation_url_templates,
          })
        : '';
      // No URL means the template could not be filled. Disarm rather than open a
      // blank tab: the cell returns to "A" and the user can try again.
      if (!url) {
        actions.armBooking(null);
        return;
      }
      window.open(url, '_blank', 'noreferrer');
    },
    [actions, catalog.data],
  );

  /**
   * Report a watch failure where the user will still be looking.
   *
   * An expired session is the case that needs this: it withdraws every watch
   * affordance, which unmounts the control that was clicked — a day-panel button, or
   * the cell a popover is anchored to — so an inline message would be raised into a
   * component that is about to disappear. A toast outlives both, and
   * `<ToastProvider>` is already mounted app-wide.
   */
  const reportWatchFailure = useCallback(
    (caught: unknown) => {
      if (caught instanceof WatchAuthError) {
        toast({ status: 'warning', title: 'Sign in to set watches', children: 'Your session expired.' });
        return;
      }
      // Every other failure is reported by whichever control is still on screen —
      // the editor's own error line — so it is not duplicated here.
      console.warn('watch change failed', caught);
    },
    [toast],
  );

  const toggleDayWatch = useCallback(
    async (anchor: HTMLElement) => {
      if (!selectedDate) return;
      const existing = watchForDate(watches, selectedDate);
      // No existing watch and no Slack: there is no one-tap default to create, so the
      // button opens the editor anchored to itself instead of doing nothing. The
      // toggle is only rendered when the provider supports *some* channel, so this
      // branch is the email-only provider, and the editor is where an email watch
      // gets its address. Returning silently here — which is what this did — left
      // those providers with a button that could not be made to work.
      if (!existing && !capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY)) {
        actions.openWatch(anchor, selectedDate);
        return;
      }
      try {
        if (existing) {
          await mutations.remove(existing);
          return;
        }
        // Slack is the grid's one-tap default, and the same one the editor would open
        // with — so for a Slack provider the extra step buys nothing.
        await mutations.save(selectedDate, buildTriggerPayload(triggerStateOf(null)));
      } catch (caught) {
        // The day panel has no error line of its own, and the vanilla toggle only
        // restored its label and logged — so this is the one report the user gets.
        reportWatchFailure(caught);
      }
    },
    [actions, capabilities, mutations, reportWatchFailure, selectedDate, watches],
  );

  const weekNav = (
    <WeekNav
      startIso={localYmd(weekStart)}
      endIso={localYmd(addLocalDays(weekStart, WEEK_DAYS - 1))}
      showEarliest={!sameLocalDay(weekStart, earliestDate)}
      canGoBack={!sameLocalDay(weekStart, earliestDate)}
      onPrev={() => goToWeek(addLocalDays(weekStart, -WEEK_DAYS))}
      onNext={() => goToWeek(addLocalDays(weekStart, WEEK_DAYS))}
      onEarliest={() => goToWeek(earliestDate)}
      onPickDate={() => actions.toggleCalendar(true)}
      calendar={
        calendarOpen ? (
          <CalendarPopover
            viewMonth={weekStart}
            today={earliestDate}
            selectedDate={weekStart}
            maxDate={addLocalDays(earliestDate, CALENDAR_MAX_DAYS_OUT)}
            onPick={goToWeek}
            onClose={() => actions.toggleCalendar(false)}
          />
        ) : null
      }
    />
  );

  return (
    <section className="cg-availability">
      <WeekSurface
        week={week}
        showSkeleton={showSkeleton}
        siteColumnWidth={siteColumnWidth}
        weekNav={weekNav}
        onOpenWatch={(anchor) => actions.openWatch(anchor, selectedDate ?? localYmd(weekStart))}
        onReport={() => {
          const detail = upstreamCopy.reportDetail(poiName, poiId, week.error?.message ?? 'unknown');
          // `copyShareUrl` rather than `navigator.clipboard` directly: the async
          // clipboard is absent in a non-secure context and rejects when the document
          // is not focused, and the helper's textarea fallback covers both. A bare
          // `?.writeText` reported no failure at all in the first case — the optional
          // chain made the whole chain `undefined` and neither toast fired.
          void copyShareUrl(detail).then((ok) =>
            ok
              ? toast({ status: 'success', title: 'Copied the details', children: 'Send them to the team when you report it.' })
              : toast({ status: 'warning', title: "Couldn't copy the details", children: detail }),
          );
        }}
        matrix={
          <SiteMatrix
            days={days}
            catalog={{
              campsites: catalog.data?.campsites ?? [],
              reservationUrlTemplates: catalog.data?.reservation_url_templates,
              state: catalog.isPending ? 'loading' : catalog.error ? 'error' : 'success',
              error: catalog.error?.message ?? null,
              retry: () => void catalog.refetch(),
            }}
            view={{
              filters,
              siteColumnWidth,
              selectedSiteId,
              armedBook,
              watchedDates: watchedDatesOf(watches.byWindow),
              watchGate,
              cartGate: cart,
              cartAction,
            }}
            events={{
              filtersChanged: actions.changeFilters,
              siteColumnResized: actions.resizeSiteColumn,
              siteSelected: actions.selectSite,
              bookingArmed: actions.armBooking,
              bookingOpened: openBooking,
              cartRequested: holdSite,
              signInRequested: () => signIn(),
              settingsRequested: () => openSettings('booking'),
              dateSelected: onSelectDate,
              watchOpened: actions.openWatch,
            }}
            weekActions={weekNav}
          />
        }
      />

      {/* Bottom of the panel, where the toasts speak from — not floating over
          the rows. A chip pinned mid-grid covers the very cells the user is
          watching for the answer. */}
      {isCartActionPending(cartAction) ? (
        <div className="cg-availability-cart-chip" role="status">
          <CartChipSpinner />
          {bookingCopy.holdRunning}
        </div>
      ) : null}

      <div className="cg-freshness">
        <Freshness week={week} onRefresh={() => void week.refetch()} />
      </div>

      {/* The day panel is for a day with nothing open; a day with openings gets the
          site list below, which is more useful than a sentence. */}
      {selectedDay && availableCount(selectedDay) === 0 ? (
        <DayDetail
          day={selectedDay}
          watching={watchForDate(watches, selectedDate) != null}
          unavailable={watchUnavailable}
          busy={mutations.saving}
          onToggleWatch={(anchor) => void toggleDayWatch(anchor)}
          onRetryWatches={watches.retry}
          onSignIn={() => signIn()}
        />
      ) : null}

      <SiteList
        state={catalog.isPending ? 'loading' : catalog.error ? 'error' : 'success'}
        campsites={catalog.data?.campsites ?? []}
        reservationUrlTemplates={catalog.data?.reservation_url_templates}
        error={catalog.error?.message ?? null}
        expanded={sitesExpanded}
        onToggle={actions.toggleSites}
        onRetry={() => void catalog.refetch()}
        selectedDay={selectedDay && availableCount(selectedDay) > 0 ? selectedDay : null}
        selectedEndDate={selectedDay ? stayEndDate(selectedDay.date) : null}
      />

      {watchTarget ? (
        <WatchPopover
          anchor={watchTarget.anchor}
          poiName={poiName}
          date={watchTarget.date}
          watch={watchForDate(watches, watchTarget.date)}
          capabilities={capabilities}
          supportsAddToCart={supportsAddToCart(capabilities)}
          gate={watchGate === 'signed-out' ? 'signed-out' : undefined}
          onSignIn={() => signIn()}
          onOpenSettings={() => openSettings('booking')}
          onSave={async (payload) => {
            try {
              await mutations.save(watchTarget.date, payload, watchForDate(watches, watchTarget.date));
            } catch (caught) {
              // Rethrown so the editor can still show its own inline message for the
              // failures it can explain; the toast covers the one it cannot, because
              // that one closes the editor.
              reportWatchFailure(caught);
              throw caught;
            }
          }}
          onRemove={async () => {
            const existing = watchForDate(watches, watchTarget.date);
            if (!existing) return;
            try {
              await mutations.remove(existing);
            } catch (caught) {
              reportWatchFailure(caught);
              throw caught;
            }
          }}
          onClose={actions.closeWatch}
        />
      ) : null}
    </section>
  );
}

/**
 * The grid, or the banner that replaces it.
 *
 * `empty` and `closed_for_season` are separate branches with separate copy, which is
 * the distinction `fuse.ts` preserves: one is permanent, one is a date.
 */
function WeekSurface({
  week,
  showSkeleton,
  siteColumnWidth,
  weekNav,
  matrix,
  onOpenWatch,
  onReport,
}: {
  week: ReturnType<typeof useWeekAvailability>;
  showSkeleton: boolean;
  siteColumnWidth: number;
  weekNav: React.ReactNode;
  matrix: React.ReactNode;
  onOpenWatch: (anchor: HTMLElement) => void;
  onReport: () => void;
}) {
  // The card a booking-site failure gets stays mounted, not the matrix behind it —
  // except when this campground's own last successful fetch is still in cache
  // (React Query keeps `data` around across a same-key refetch failure), in which
  // case "Show what we last saw" reveals it without a second request.
  const [showStale, setShowStale] = useState(false);

  if (week.isPending) {
    // Before the delay elapses: the nav only, so a cache hit does not flash a
    // skeleton table on its way to real data.
    return showSkeleton ? (
      <SiteMatrixSkeleton siteColumnWidth={siteColumnWidth} actions={weekNav} />
    ) : (
      <div className="cg-site-matrix-head">
        <div className="cg-site-matrix-actions">{weekNav}</div>
      </div>
    );
  }

  if (week.error && !(showStale && week.data)) {
    const code = week.error instanceof AvailabilityRequestError ? week.error.code : null;
    const kind =
      week.error instanceof AvailabilityRequestError
        ? classifyAvailabilityErrorCode(code)
        : 'unreachable';
    const hasStaleData = week.data != null;

    if (kind === 'throttled') {
      const ageMin =
        week.data?.state === 'success' ? cacheAgeMinutes(week.data.cacheBlock?.age_seconds) : null;
      return (
        <EmptyState
          icon="lock"
          title={upstreamCopy.rateLimitedTitle}
          body={
            hasStaleData
              ? `They've throttled us, so we're holding off. The availability below is from ${ageMin} minute${ageMin === 1 ? '' : 's'} ago and won't update until they let us back in.`
              : "They've throttled us, so we're holding off."
          }
          actions={
            <>
              {hasStaleData ? (
                <Button variant="secondary" size="sm" onClick={() => setShowStale(true)}>
                  Show what we last saw
                </Button>
              ) : null}
              <Button
                variant="primary"
                size="sm"
                iconStart="bell"
                onClick={(event) => onOpenWatch(event.currentTarget as HTMLElement)}
              >
                Watch these dates instead
              </Button>
            </>
          }
        />
      );
    }

    if (kind === 'server_error') {
      return (
        <EmptyState
          icon="warning-fill"
          title={upstreamCopy.erroredTitle}
          body="Their end failed on this request. Your dates are fine — this one is theirs."
          actions={
            <>
              <Button
                variant="secondary"
                size="sm"
                iconStart="refresh"
                onClick={() => void week.refetch()}
              >
                Try again
              </Button>
              <Button variant="tertiary" size="sm" onClick={onReport}>
                Report it
              </Button>
            </>
          }
        />
      );
    }

    if (kind === 'unreachable') {
      return (
        <EmptyState
          icon="warning"
          title={upstreamCopy.unreachableTitle}
          body="The request timed out. Outages like this usually clear within a few minutes."
          actions={
            <>
              <Button
                variant="secondary"
                size="sm"
                iconStart="refresh"
                onClick={() => void week.refetch()}
              >
                Try again
              </Button>
              <Button
                variant="primary"
                size="sm"
                iconStart="bell"
                onClick={(event) => onOpenWatch(event.currentTarget as HTMLElement)}
              >
                Tell me when it's back
              </Button>
            </>
          }
        />
      );
    }

    return (
      <div className="cg-summary">
        <span className="cg-error">{week.error.message || GENERIC_AVAILABILITY_ERROR}</span> ·{' '}
        <LinkButton className="cg-retry" onClick={() => void week.refetch()}>
          Retry
        </LinkButton>
      </div>
    );
  }

  if (week.data?.state === 'empty') {
    return <div className="cg-closed-banner">No availability data for this campground.</div>;
  }

  if (week.data?.state === 'closed_for_season') {
    const reopens = week.data.season?.reopens_on;
    return (
      <div className="cg-closed-banner">
        {/* A calendar rather than the ⛰️ this shipped with: both messages this
            banner can carry are about dates, and Open Icons has no mountain. */}
        <Icon name="calendar" className="cg-closed-banner-icon" aria-hidden="true" />{' '}
        {reopens ? `Reopens ${reopens}` : 'Closed for season'}
      </div>
    );
  }

  return <>{matrix}</>;
}

/**
 * "checked 4m ago · refresh".
 *
 * The age is the *backend's* cache age, so it is the age of the data the provider
 * gave us rather than of our own request — which is why it can read "12m ago" on a
 * page you just opened, and why the refresh link exists.
 */
function Freshness({
  week,
  onRefresh,
}: {
  week: ReturnType<typeof useWeekAvailability>;
  onRefresh: () => void;
}) {
  const cache = week.data?.state === 'success' ? week.data.cacheBlock : null;
  // A non-breaking space, so the row keeps its height and the grid does not jump
  // when the pill appears.
  if (!cache) return <>&nbsp;</>;
  const ageMin = cacheAgeMinutes(cache.age_seconds);
  return (
    <span className={ageMin >= STALE_THRESHOLD_MIN ? 'cg-stale' : undefined}>
      checked {ageMin}m ago ·{' '}
      <LinkButton className="cg-refresh" onClick={onRefresh}>
        refresh
      </LinkButton>
    </span>
  );
}

/**
 * The first date this provider will quote for, or today.
 *
 * Not the same as today: a campground whose booking window opens six months out has
 * no availability to show before then, and opening the grid on today's date would
 * show a week of blanks and imply the campground is full.
 */
function featureEarliestDate(feature: PoiFeature): Date {
  const properties = feature.properties ?? {};
  const raw = properties.earliest_date ?? properties.earliestDate;
  const parsed = parseLocalYmd(raw);
  return Number.isFinite(parsed.getTime()) ? parsed : localToday();
}

export { DEFAULT_SITE_COLUMN_WIDTH };

/** The chip's own spinner. Same mark as the cell's, at the chip's size. */
function CartChipSpinner() {
  return (
    <svg className="cg-cell-spinner" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}
