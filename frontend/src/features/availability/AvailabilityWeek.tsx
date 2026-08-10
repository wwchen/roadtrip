// The availability grid, mounted inside the campground drawer.
//
// Port of `mountAvailabilityWeek` in web/availability/availability-week.js — the 1,226
// line controller that owned a mutable `ctx` object, six delegated event listeners on
// a host element, and `innerHTML` re-renders that had to capture and restore the
// matrix's scroll position around every state change.
//
// Most of that machinery is gone rather than translated, and it is worth saying which
// parts and why, because the absences are the point:
//
//   - **The scroll capture/restore dance is gone.** It existed because re-rendering
//     replaced the scrolling element, losing `scrollLeft` — twice over for a booking
//     tap, which needed a `requestAnimationFrame` double-restore. React keeps the
//     node, so the scroll position simply persists.
//   - **The request sequence counters are gone.** `weekRequestSeq` / `sitesRequestSeq`
//     were compared at every await point so a stale response could not paint. Query
//     keys do that structurally.
//   - **The filter focus restoration is gone.** `restoreMatrixFilterFocus` re-focused
//     the search box and restored its caret after each re-render, because the input
//     was destroyed on every keystroke. A controlled input keeps both.
//
// What is NOT simplified is the arming behaviour, the capability gates, and the state
// resets on week change — those are product decisions, and they are carried over
// exactly. See the notes at each.
import { useCallback, useMemo } from 'react';
import { useToast } from '@ui';
import type { PoiFeature } from '@/lib/poi';
import { availableCount } from '@/lib/day-fields';
import { addLocalDays, localToday, localYmd, parseLocalYmd, sameLocalDay } from '@/lib/local-date';
import { DayDetail, type WatchUnavailableReason } from './DayDetail';
import { CalendarPopover } from './CalendarPopover';
import { SiteList } from './SiteList';
import { SiteMatrix, SiteMatrixSkeleton } from './SiteMatrix';
import { WatchPopover } from './WatchPopover';
import { WeekNav } from './WeekNav';
import { GENERIC_AVAILABILITY_ERROR } from './availability-errors';
import { reservationUrlFromTemplate } from './booking-links';
import type { FusedDay } from './fuse';
import { DEFAULT_SITE_COLUMN_WIDTH } from './site-column';
import { useAvailabilityController } from './availability-controller';
import { useCampsites } from './useCampsites';
import { useDelayedFlag } from './useDelayedFlag';
import {
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
  const { state, actions } = useAvailabilityController(earliestDate);
  const {
    weekStart,
    selectedDate,
    selectedSiteId,
    sitesExpanded,
    armedBook,
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
  const canWatch = watchUnavailable === null;

  const onSelectDate = useCallback(
    (date: string) => {
      // The reducer owns the coupled site-list and booking reset rules.
      actions.selectDate(date);
    },
    [actions],
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
              canWatch,
            }}
            events={{
              filtersChanged: actions.changeFilters,
              siteColumnResized: actions.resizeSiteColumn,
              siteSelected: actions.selectSite,
              bookingArmed: actions.armBooking,
              bookingOpened: openBooking,
              dateSelected: onSelectDate,
              watchOpened: actions.openWatch,
            }}
            weekActions={weekNav}
          />
        }
      />

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
}: {
  week: ReturnType<typeof useWeekAvailability>;
  showSkeleton: boolean;
  siteColumnWidth: number;
  weekNav: React.ReactNode;
  matrix: React.ReactNode;
}) {
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

  if (week.error) {
    return (
      <div className="cg-summary">
        <span className="cg-error">{week.error.message || GENERIC_AVAILABILITY_ERROR}</span> ·{' '}
        <button type="button" className="cg-retry cg-link-button" onClick={() => void week.refetch()}>
          Retry
        </button>
      </div>
    );
  }

  if (week.data?.state === 'empty') {
    return <div className="cg-closed-banner">No availability data for this campground.</div>;
  }

  if (week.data?.state === 'closed_for_season') {
    const reopens = week.data.season?.reopens_on;
    return (
      <div className="cg-closed-banner">⛰️ {reopens ? `Reopens ${reopens}` : 'Closed for season'}</div>
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
      <button type="button" className="cg-refresh cg-link-button" onClick={onRefresh}>
        refresh
      </button>
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
