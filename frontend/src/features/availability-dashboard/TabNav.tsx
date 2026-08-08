import { DASHBOARD_TABS, type DashboardTab, type TabRoute } from './useTabRoute';

const TAB_LABELS: Record<DashboardTab, string> = {
  pollers: 'Pollers',
  runs: 'Runs',
  changes: 'Changes',
};

export interface TabNavProps {
  route: TabRoute;
}

/**
 * The dashboard's tab bar, as real links.
 *
 * **Not LDS `Tabs`, and not `SegmentedControl`** — this is the one place on the
 * page where the shared component does not fit, so the reason is recorded here.
 *
 *  - `Tabs` renders `<button class="lds-tabs__tab">` with no id, no data
 *    attribute, and no `onChange`. There is no way to learn which tab was
 *    clicked short of matching on label text or child index, and it emits nothing
 *    for a URL — these tabs each have their own address.
 *  - `SegmentedControl` does have an `onChange` carrying the value, but its own
 *    source says it is "a value picker, not navigation … tabs change what you are
 *    looking at; this changes a property of what you are already looking at."
 *    Using it here would contradict the component on purpose.
 *
 * So: anchors, as the legacy page had them. They carry a real `href`, so the tabs
 * are middle-clickable, copyable and crawlable, and `aria-current="page"` marks
 * the active one. `preventDefault` keeps the switch client-side; a modified click
 * (new tab, new window) is left to the browser, which the legacy handler got
 * wrong by swallowing every click.
 */
export function TabNav({ route }: TabNavProps) {
  return (
    <nav className="rt-dash-tabs" aria-label="Dashboard tabs">
      {DASHBOARD_TABS.map((tab) => (
        <a
          key={tab}
          href={route.hrefFor(tab)}
          aria-current={tab === route.tab ? 'page' : undefined}
          onClick={(e) => {
            // Let the browser handle "open in a new tab/window" itself.
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
            e.preventDefault();
            route.goToTab(tab);
          }}
        >
          {TAB_LABELS[tab]}
        </a>
      ))}
    </nav>
  );
}
