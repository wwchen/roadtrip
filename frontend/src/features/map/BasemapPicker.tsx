import { Checkbox, Select } from '@ui';
import { AUTO_BASEMAP_VALUE, BASEMAPS } from './basemaps';
import { useMapContext } from './MapProvider';

/** The picker's "follow the theme" option, always first. */
const AUTO_OPTION = { value: AUTO_BASEMAP_VALUE, label: 'Auto (match theme)' };

const BASEMAP_OPTIONS = [
  AUTO_OPTION,
  ...Object.entries(BASEMAPS).map(([value, basemap]) => ({ value, label: basemap.name })),
];

/**
 * Basemap choice and the satellite underlay.
 *
 * The DOM half of web/basemap.js, which Phase 4a deliberately left out of
 * `basemaps.ts`: `initBasemapPicker` filled a `<select>` by element id and
 * `bindSatelliteToggle` read a checkbox the same way. Both are props now, and the
 * map lifecycle they drive lives in `MapProvider` — this component only renders
 * the choice.
 *
 * `value` rather than `defaultValue` on the select: the provider is the single
 * source of truth (it also seeds from `localStorage`), and LDS re-renders the
 * control from props, so passing the live value keeps the two from drifting. That
 * is safe here in a way it is not for a text field — there is no caret to lose,
 * which is the trap `docs/frontend-components.md` warns about.
 */
export function BasemapPicker() {
  const { basemapKey, setBasemap, isAutoBasemap, resetBasemap, satellite, setSatellite } =
    useMapContext();

  return (
    <>
      <div className="rt-legend__basemap">
        <Select
          id="rt-basemap"
          aria-label="Basemap"
          options={BASEMAP_OPTIONS}
          value={isAutoBasemap ? AUTO_BASEMAP_VALUE : basemapKey}
          onChange={(e) => {
            const next = (e.target as HTMLSelectElement).value;
            if (next === AUTO_BASEMAP_VALUE) resetBasemap();
            else setBasemap(next);
          }}
        />
      </div>
      <Checkbox
        id="rt-satellite"
        label="Satellite overlay"
        checked={satellite}
        onChange={(e) => setSatellite((e.target as HTMLInputElement).checked)}
      />
    </>
  );
}
