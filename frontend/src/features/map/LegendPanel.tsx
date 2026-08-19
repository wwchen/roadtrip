import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Checkbox, Icon } from '@ui';
import { sortedAgencies } from '@/map/agencies';
import { overlaySpec, type PointOverlaySpec } from '@/map/overlays';
import { useMapStore } from '@/stores/mapStore';
import { BasemapPicker } from './BasemapPicker';
import { useMapContext } from './MapProvider';
import type { ViewportPois } from './useViewportPois';
import './legend.css';

/** Below this width the panel is a top sheet behind a hamburger. Matches legend.css. */
const MOBILE_QUERY = '(max-width: 640px)';

const isMobile = (): boolean => window.matchMedia?.(MOBILE_QUERY).matches ?? false;

export interface LegendPanelProps {
  pois: ViewportPois;
}

/**
 * The layers panel: what is on the map, how much of it is in view, and what to
 * filter out.
 *
 * Port of `#panel` from index.html plus the collapse/drawer behaviour from
 * web/app.js's two IIFEs. Everything it filters is applied by `useMapOverlays`;
 * this component only reads counts and writes the hidden sets, so a legend click
 * never refetches.
 *
 * **The search box is deliberately not here.** `#search` and `web/search.js`
 * filtered a client-side index that nothing has populated since the slim
 * `/api/pois` response stopped shipping names — `registerSearchItems` has no
 * callers, so the box could never return a result. Cross-viewport search is a
 * backend call the topbar owns (Phase 4e). See docs/react-migration-plan.md.
 */
export function LegendPanel({ pois }: LegendPanelProps) {
  const { map } = useMapContext();
  const [collapsed, setCollapsed] = useState(false);
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const [panelHeight, setPanelHeight] = useState<number | null>(null);

  // Tapping the map closes the sheet, as it did on the vanilla page. Bound only
  // while it is open so an ordinary map click does no work.
  useEffect(() => {
    if (!map || !open) return;
    const close = () => setOpen(false);
    map.on('click', close);
    return () => {
      map.off('click', close);
    };
  }, [map, open]);

  // Mobile only: .rt-legend-morph has no intrinsic height (its children are
  // absolutely positioned so they can cross-fade in place), so the open
  // height is measured off the panel's own natural content height and fed
  // back in as an inline style. Re-measures on every content change (More
  // options, agency list growing/shrinking) via ResizeObserver.
  useLayoutEffect(() => {
    if (!open || !panelRef.current) return
    const el = panelRef.current
    const measure = () => setPanelHeight(el.offsetHeight)
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [open])

  // One button, two meanings, exactly as the original: on a phone the panel is a
  // sheet and this closes it; on a desktop it collapses to the pop-out button.
  const hide = () => {
    if (isMobile()) {
      setOpen(false);
      return;
    }
    setCollapsed(true);
  };

  const agencies = sortedAgencies(pois.agencies);

  return (
    <>
      {/* Mobile: the toggle button IS the closed panel — `data-open` morphs this
          box's size/radius from the 40px button into the full sheet, while the
          button and the panel cross-fade inside it (see .rt-legend-morph in
          legend.css). Desktop takes `display: contents` here and is untouched:
          the panel is always up there, gated only by `collapsed`. */}
      <div
        className="rt-legend-morph"
        data-open={open}
        style={open && panelHeight ? { height: panelHeight } : undefined}
      >
        <button
          type="button"
          className="rt-legend-toggle"
          aria-label="Toggle layers panel"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <Icon name={open ? 'close' : 'menu'} aria-hidden="true" />
        </button>

        <div
          ref={panelRef}
          className={`rt-legend${collapsed ? ' rt-legend--collapsed' : ''}${open ? ' rt-legend--open' : ''}`}
        >
          <button
            type="button"
            className="rt-legend__hide"
            aria-label="Hide layers panel"
            title="Hide"
            onClick={hide}
          >
            <Icon name="close" aria-hidden="true" />
          </button>
          <h1 className="rt-legend__title">Roadtrip Map</h1>

          <div className="rt-legend__section">Superchargers</div>
          <OverlayRow spec={overlaySpec('sc')} count={pois.counts.sc} />

          <div className="rt-legend__section">
            Campgrounds{' '}
            {!pois.campgroundsRequested && (
              <span className="rt-legend__hint">(zoom in to load)</span>
            )}
          </div>
          {/* Viewport-scoped: rows come from the campgrounds currently in view, so
              panning away from a region drops its agencies rather than accumulating
              every agency ever seen. */}
          <div className="rt-legend__agencies">
            {agencies.map((agency) => (
              <AgencyRow key={agency} agency={agency} count={pois.agencies.get(agency) ?? 0} />
            ))}
          </div>

          <div className="rt-legend__section">Other</div>
          <OverlayRow spec={overlaySpec('pf')} count={pois.counts.pf} />

          <div className="rt-legend__section">Basemap</div>
          <BasemapPicker />
        </div>
      </div>

      {collapsed && (
        <button
          type="button"
          className="rt-legend-show"
          aria-label="Show layers panel"
          title="Show layers"
          onClick={() => setCollapsed(false)}
        >
          <Icon name="list" aria-hidden="true" />
        </button>
      )}
    </>
  );
}

/** An overlay's on/off row. */
function OverlayRow({ spec, count }: { spec: PointOverlaySpec; count: number }) {
  const visible = useMapStore((s) => !s.hiddenOverlays.includes(spec.key));
  const toggleOverlay = useMapStore((s) => s.toggleOverlay);

  return (
    <Checkbox
      id={`rt-layer-${spec.key}`}
      checked={visible}
      onChange={() => toggleOverlay(spec.key)}
      label={
        <span className="rt-legend__row-label">
          <LegendDot colorToken={spec.legendColorToken} />
          {spec.label} <Count value={count} />
        </span>
      }
    />
  );
}

/**
 * One managing agency's row.
 *
 * Subscribes per row rather than taking `hidden` as a prop so that ticking one
 * agency re-renders one row, not the whole (potentially 50-row) list.
 */
function AgencyRow({ agency, count }: { agency: string; count: number }) {
  const visible = useMapStore((s) => !s.hiddenAgencies.includes(agency));
  const setAgencyHidden = useMapStore((s) => s.setAgencyHidden);

  return (
    <Checkbox
      // No `id`: LDS wraps the input in its own <label>, so the association is
      // implicit — and an agency name is not a safe source for an element id.
      checked={visible}
      onChange={(e) => setAgencyHidden(agency, !(e.target as HTMLInputElement).checked)}
      label={
        <span className="rt-legend__row-label">
          <LegendDot colorToken={overlaySpec('cg').legendColorToken} />
          {agency} <Count value={count} />
        </span>
      }
    />
  );
}

/**
 * The colored swatch beside a row.
 *
 * An inline `var()` reference rather than a class per overlay: the color is
 * registry data, and CSS resolves the custom property here — unlike MapLibre
 * paint, which cannot and goes through the token bridge instead.
 */
function LegendDot({ colorToken }: { colorToken: string }) {
  return <span className="rt-legend__dot" style={{ background: `var(${colorToken})` }} />;
}

/** A viewport count, in the panel's parenthesised style. */
function Count({ value }: { value: number }) {
  return <span className="rt-legend__count">({value.toLocaleString()})</span>;
}
