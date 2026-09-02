import { Toggle } from '@ui';
import { BASEMAPS, type Basemap } from './basemaps';
import { useMapContext } from './MapProvider';

const checkedOf = (e: Event): boolean => (e.target as HTMLInputElement).checked;

/**
 * Basemap choice and the satellite underlay.
 *
 * The DOM half of web/basemap.js, which Phase 4a deliberately left out of
 * `basemaps.ts`: `initBasemapPicker` filled a `<select>` by element id and
 * `bindSatelliteToggle` read a checkbox the same way. Both are props now, and the
 * map lifecycle they drive lives in `MapProvider` — this component only renders
 * the choice.
 *
 * A swatch grid rather than a `<select>`: the picker's job is "what will this
 * look like", which a dropdown of names can't answer.
 *
 * Cartography only. Light and Dark used to sit in this grid as if they were peers
 * of Streets and Terrain, so one control chose two unrelated things and picking a
 * map could un-darken the UI. Brightness is the app's theme now; every cartography
 * here has tiles for both modes, and the swatch previews the pair this mode loads.
 */
export function BasemapPicker() {
  const { basemapKey, setBasemap, satellite, setSatellite } = useMapContext();

  return (
    <>
      <div className="rt-legend__basemap-grid" role="radiogroup" aria-label="Basemap">
        {Object.entries(BASEMAPS).map(([key, basemap]) => (
          <BasemapTile
            key={key}
            basemap={basemap}
            selected={key === basemapKey}
            onSelect={() => setBasemap(key)}
          />
        ))}
      </div>
      <div className="rt-legend__satellite">
        <div className="rt-legend__satellite-copy">
          <span className="rt-legend__row-title">Satellite overlay</span>
          <span className="rt-legend__row-subtitle">Imagery drawn over the basemap above.</span>
        </div>
        <Toggle
          id="rt-satellite"
          aria-label="Satellite overlay"
          checked={satellite}
          onChange={(e) => setSatellite(checkedOf(e))}
        />
      </div>
    </>
  );
}

function BasemapTile({
  basemap,
  selected,
  onSelect,
}: {
  basemap: Basemap;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      className="rt-legend__basemap-tile"
      onClick={onSelect}
    >
      <span
        className={`rt-legend__basemap-swatch${selected ? ' rt-legend__basemap-swatch--selected' : ''}`}
        style={{ background: basemap.swatch }}
      />
      <span
        className={`rt-legend__basemap-label${selected ? ' rt-legend__basemap-label--selected' : ''}`}
      >
        {basemap.name}
      </span>
    </button>
  );
}
