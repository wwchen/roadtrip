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
import { useCallback, useMemo, useState } from 'react';
import { useToast } from '@ui';
import type { PoiFeature } from '@/lib/poi';
import { availableCount } from '@/lib/day-fields';
import { addLocalDays, localToday, localYmd, parseLocalYmd, sameLocalDay } from '@/lib/local-date';
import { DayDetail } from './DayDetail';
import { CalendarPopover } from './CalendarPopover';
import { SiteList } from './SiteList';
import { SiteMatrix, SiteMatrixSkeleton, type ArmedBook } from './SiteMatrix';
import { WatchPopover } from './WatchPopover';
import { WeekNav } from './WeekNav';
import { GENERIC_AVAILABILITY_ERROR } from './availability-errors';
import { reservationUrlFromTemplate } from './booking-links';
import type { FusedDay } from './fuse';
import { DEFAULT_MATRIX_FILTERS, type MatrixFilters } from './matrix-rows';
import { DEFAULT_SITE_COLUMN_WIDTH, loadSiteColumnWidth } from './site-column';
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
} from './watch-windows';
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
  const poiName = (feature.properties?.name as string | undefined) || 'this campground';

  // No id, no availability — and specifically not a skeleton. Every request here is
  // keyed on the POI id, so without one the queries stay `enabled: false`, which reads
  // as permanently pending: the grid would show a loading skeleton that can never
  // resolve. The drawer's own gate is `properties.availability_supported`, which says
  // nothing about the id, so the two can disagree — a body with the flag set and no
  // top-level `id` is exactly the case this catches. Rendering nothing is the honest
  // answer, and matches what the section looks like for an unsupported provider.
  if (poiId == null) return null;

  // The first date this provider will quote. Everything paginates forward from here,
  // and "Earliest" returns to it — which is not the same as "today" for a campground
  // that only opens a booking window months out.
  const earliestDate = useMemo(() => featureEarliestDate(feature), [feature]);
  const [weekStart, setWeekStart] = useState(earliestDate);

  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [selectedSiteId, setSelectedSiteId] = useState<string | null>(null);
  const [sitesExpanded, setSitesExpanded] = useState(false);
  const [armedBook, setArmedBook] = useState<ArmedBook | null>(null);
  const [filters, setFilters] = useState<MatrixFilters>(DEFAULT_MATRIX_FILTERS);
  const [siteColumnWidth, setSiteColumnWidth] = useState(() => loadSiteColumnWidth());
  const [calendarOpen, setCalendarOpen] = useState(false);
  const [watchTarget, setWatchTarget] = useState<{ anchor: HTMLElement; date: string } | null>(null);

  const { toast } = useToast();
  const week = useWeekAvailability(poiId, weekStart);
  const catalog = useCampsites(poiId);
  const watches = usePoiWatches(poiId);
  const mutations = useWatchMutations(poiId);

  const showSkeleton = useDelayedFlag(week.isPending, SKELETON_RENDER_DELAY_MS);

  /**
   * Changing week clears every selection made against the old one.
   *
   * Carried over from `resetWeekViewState`, and each part earns its place: a selected
   * date that is no longer on screen would keep a day-detail panel open for an
   * invisible column, and — the one that matters — an armed booking cell surviving a
   * week change would mean the second tap opens a booking page for a different night
   * than the one the user is looking at.
   */
  const resetWeekView = useCallback(() => {
    setSelectedDate(null);
    setSelectedSiteId(null);
    setSitesExpanded(false);
    setArmedBook(null);
    setWatchTarget(null);
  }, []);

  const goToWeek = useCallback(
    (next: Date) => {
      resetWeekView();
      setCalendarOpen(false);
      setWeekStart(next < earliestDate ? earliestDate : next);
    },
    [earliestDate, resetWeekView],
  );

  const days: FusedDay[] = week.data?.state === 'success' ? week.data.days : [];
  const selectedDay = days.find((day) => day.date === selectedDate) ?? null;
  // The shared empty value, not a fresh object per render: it is the identity every
  // capability gate below compares against, and a new `Set` each time would make the
  // memoised callbacks that read it churn for no reason.
  const capabilities = week.data?.watchCapabilities ?? NO_WATCH_CAPABILITIES;

  // Provider first, user second: "this campground cannot do alerts" and "sign in to
  // set one" are different sentences, and only the second is actionable.
  const providerSupportsWatch = poiId != null && supportsWatchAlerts(capabilities);
  const canWatch = providerSupportsWatch && watches.canManage;
  const signedOutOfWatches = providerSupportsWatch && !watches.canManage;

  const onSelectDate = useCallback(
    (date: string) => {
      // Derived from the current value rather than from inside a `setSelectedDate`
      // updater. An updater must be pure — React double-invokes it under StrictMode
      // and may replay it — so calling another setter in there is not a supported
      // pattern even though it happens to work today. Both setters are in one event
      // handler, so React batches them into a single render either way.
      const selecting = selectedDate !== date;
      setArmedBook(null);
      setSelectedDate(selecting ? date : null);
      // Selecting a date is a request to see what is open on it; clearing it closes
      // the list again.
      setSitesExpanded(selecting);
    },
    [selectedDate],
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
        setArmedBook(null);
        return;
      }
      window.open(url, '_blank', 'noreferrer');
    },
    [catalog.data],
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

  const toggleDayWatch = useCallback(async () => {
    if (!selectedDate) return;
    try {
      const existing = watchForDate(watches, selectedDate);
      if (existing) {
        await mutations.remove(existing);
        return;
      }
      // The grid's own toggle creates a Slack watch, which is the default the popover
      // would also open with. A provider that cannot Slack has no toggle at all — see
      // `DayDetail` — so reaching here without the capability is not possible.
      if (!capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY)) return;
      await mutations.save(selectedDate, buildTriggerPayload(triggerStateOf(null)));
    } catch (caught) {
      // The day panel has no error line of its own, and the vanilla toggle only
      // restored its label and logged — so this is the one report the user gets.
      reportWatchFailure(caught);
    }
  }, [capabilities, mutations, reportWatchFailure, selectedDate, watches]);

  const weekNav = (
    <WeekNav
      startIso={localYmd(weekStart)}
      endIso={localYmd(addLocalDays(weekStart, WEEK_DAYS - 1))}
      showEarliest={!sameLocalDay(weekStart, earliestDate)}
      canGoBack={!sameLocalDay(weekStart, earliestDate)}
      onPrev={() => goToWeek(addLocalDays(weekStart, -WEEK_DAYS))}
      onNext={() => goToWeek(addLocalDays(weekStart, WEEK_DAYS))}
      onEarliest={() => goToWeek(earliestDate)}
      onPickDate={() => setCalendarOpen(true)}
      calendar={
        calendarOpen ? (
          <CalendarPopover
            viewMonth={weekStart}
            today={earliestDate}
            selectedDate={weekStart}
            maxDate={addLocalDays(earliestDate, CALENDAR_MAX_DAYS_OUT)}
            onPick={goToWeek}
            onClose={() => setCalendarOpen(false)}
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
            campsites={catalog.data?.campsites ?? []}
            reservationUrlTemplates={catalog.data?.reservation_url_templates}
            sitesState={catalog.isPending ? 'loading' : catalog.error ? 'error' : 'success'}
            sitesError={catalog.error?.message ?? null}
            onRetrySites={() => void catalog.refetch()}
            filters={filters}
            onFiltersChange={(next) => {
              // Any filter change moves the rows, so an armed cell would end up
              // under a different site. Disarm rather than let the second tap land
              // somewhere the user did not aim.
              setArmedBook(null);
              setFilters(next);
            }}
            siteColumnWidth={siteColumnWidth}
            onSiteColumnWidthChange={setSiteColumnWidth}
            selectedSiteId={selectedSiteId}
            onSelectSite={(id) => {
              // The vanilla controller disarmed on *any* click in the grid that was
              // not a booking cell. Expanding a site row is the only other in-grid
              // control, and it pushes rows down — so an armed cell would be left
              // showing "Book" somewhere the user is no longer looking.
              setArmedBook(null);
              setSelectedSiteId(id);
            }}
            armedBook={armedBook}
            onArmBook={setArmedBook}
            onOpenBooking={openBooking}
            onSelectDate={onSelectDate}
            watchedDates={watchedDatesOf(watches.byWindow)}
            canWatch={canWatch}
            onOpenWatch={(anchor, date) => setWatchTarget({ anchor, date })}
            actions={weekNav}
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
          canWatch={canWatch}
          signedOut={signedOutOfWatches}
          busy={mutations.saving}
          onToggleWatch={() => void toggleDayWatch()}
        />
      ) : null}

      <SiteList
        state={catalog.isPending ? 'loading' : catalog.error ? 'error' : 'success'}
        campsites={catalog.data?.campsites ?? []}
        reservationUrlTemplates={catalog.data?.reservation_url_templates}
        error={catalog.error?.message ?? null}
        expanded={sitesExpanded}
        onToggle={() => setSitesExpanded((current) => !current)}
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
          onClose={() => setWatchTarget(null)}
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
