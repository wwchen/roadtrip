// The dashboard's URL contract.
//
// `/availability?tab=pollers|runs|changes`, plus per-tab params that the legacy
// page passed through verbatim (`poller_id`, `status`, `poi_id`, `campsite_id`,
// `target_date`). Ported from web/availability.js's readUrlState/setTab.
//
// These URLs are shared and linked to — a poller row links to the runs tab, and
// the changes tab rewrites its own query as you filter — so the shapes are a
// published contract, not an implementation detail.
import { useCallback, useState } from 'react';

export const TAB_POLLERS = 'pollers';
export const TAB_RUNS = 'runs';
export const TAB_CHANGES = 'changes';

export const DASHBOARD_TABS = [TAB_POLLERS, TAB_RUNS, TAB_CHANGES] as const;

export type DashboardTab = (typeof DASHBOARD_TABS)[number];

/** The tab shown when the URL names none, or names one that does not exist. */
const DEFAULT_TAB: DashboardTab = TAB_POLLERS;

const TAB_PARAM = 'tab';
const DASHBOARD_PATH = '/availability';

/** Every param except `tab`, passed through to the active tab as its seed. */
export type TabParams = Readonly<Record<string, string>>;

export interface TabRoute {
  tab: DashboardTab;
  /** The URL's params for the CURRENT tab, minus `tab` itself. */
  params: TabParams;
  /** Switch tabs, replacing the params wholesale. */
  goToTab: (tab: DashboardTab, params?: TabParams) => void;
  /** Rewrite the current tab's params without switching tab. */
  setParams: (params: TabParams) => void;
  /** `?tab=…` for a tab link's href, so the nav is made of real links. */
  hrefFor: (tab: DashboardTab) => string;
}

const isTab = (value: string | null): value is DashboardTab =>
  value != null && (DASHBOARD_TABS as readonly string[]).includes(value);

/** Read `{tab, params}` out of a query string. Exported for tests. */
export function readTabRoute(search: string): { tab: DashboardTab; params: TabParams } {
  const qs = new URLSearchParams(search);
  const tab = qs.get(TAB_PARAM);
  const params: Record<string, string> = {};
  for (const [key, value] of qs) {
    if (key !== TAB_PARAM) params[key] = value;
  }
  return { tab: isTab(tab) ? tab : DEFAULT_TAB, params };
}

/** `?tab=runs&poller_id=7`, dropping empty values as the original did. */
export function tabSearch(tab: DashboardTab, params: TabParams = {}): string {
  const qs = new URLSearchParams({ [TAB_PARAM]: tab });
  for (const [key, value] of Object.entries(params)) {
    if (value != null && value !== '') qs.set(key, value);
  }
  return `?${qs}`;
}

/**
 * Tab + params, mirrored into the URL.
 *
 * `replaceState`, not `pushState`, matching the original: switching tabs on a
 * dashboard is not a navigation you want to walk back through one entry at a
 * time, and Back should leave the page.
 *
 * There is no `popstate` listener, also matching the original. Since every
 * transition is a `replaceState` there are no dashboard entries in the history
 * to go back to, so a listener would have nothing to react to.
 */
export function useTabRoute(): TabRoute {
  const [route, setRoute] = useState(() => readTabRoute(window.location.search));

  const navigate = useCallback((tab: DashboardTab, params: TabParams) => {
    window.history.replaceState(null, '', `${DASHBOARD_PATH}${tabSearch(tab, params)}`);
    setRoute({ tab, params });
  }, []);

  const goToTab = useCallback(
    (tab: DashboardTab, params: TabParams = {}) => navigate(tab, params),
    [navigate],
  );

  // Reads the tab from state rather than taking it as an argument, so a tab
  // cannot rewrite another tab's params by accident.
  const setParams = useCallback(
    (params: TabParams) => navigate(route.tab, params),
    [navigate, route.tab],
  );

  const hrefFor = useCallback((tab: DashboardTab) => tabSearch(tab), []);

  return { tab: route.tab, params: route.params, goToTab, setParams, hrefFor };
}
