// The pieces every drawer type shares.
import type { ReactNode } from 'react';
import { Button } from '@ui';
import { distanceKm, formatDistance } from '@/lib/geo';
import type { PoiFeature } from '@/lib/poi';
import { formatPhone, phoneNumbers, telHref } from '@/lib/phone';
import { useMapStore } from '@/stores/mapStore';
import { addPoiToTrip } from '@/domain/trip/add-poi-to-trip';
import { useTripStore } from '@/stores/tripStore';

/**
 * A feature's lng/lat, or undefineds.
 *
 * Every drawer type needs it and every one of them got it differently in the
 * vanilla tree — park read it off the click's `lngLat`, the others destructured
 * `geometry.coordinates` without checking. Hydrated geometry comes back from
 * `/api/pois/{id}` as whatever the provider stored, so the check is not paranoia.
 */
export function coordinatesOf(feature: PoiFeature): [number | undefined, number | undefined] {
  const coords = (feature.geometry as { coordinates?: unknown } | undefined)?.coordinates;
  if (!Array.isArray(coords) || typeof coords[0] !== 'number' || typeof coords[1] !== 'number') {
    return [undefined, undefined];
  }
  return [coords[0], coords[1]];
}

/** A flattened property as a string, since `properties` is deliberately open. */
export const text = (value: unknown): string => (typeof value === 'string' ? value : '');

/** Compose a subline from parts, dropping the empty ones: "Loop · BC · 2.4 km away". */
export const subline = (parts: (string | null | undefined | false)[]): string =>
  parts.filter(Boolean).join(' · ');

/**
 * Distance from the user to a point, or '' when location is off.
 *
 * `mapStore.userLocation` is written by the map's locate-me control
 * (`useUserLocation`), by the topbar's locate button, and by the
 * current-location route helper. Until one of them has a fix this is
 * empty, which is what the vanilla drawer shows without a location too.
 */
export function useDistanceTo(lng: number | undefined, lat: number | undefined): string {
  const userLocation = useMapStore((s) => s.userLocation);
  if (!userLocation || !Number.isFinite(lng) || !Number.isFinite(lat)) return '';
  return formatDistance(distanceKm(userLocation.lat, userLocation.lng, lat as number, lng as number));
}

export interface DrawerHeaderProps {
  name: string;
  sub?: string;
  /**
   * Rows between the title and the subline — the campground's containing park and
   * managing agency. They sit above `sub` because they identify the place, where the
   * subline only locates it.
   */
  above?: ReactNode;
  /** The campground drawer's season verdict. */
  verdict?: ReactNode;
}

export function DrawerHeader({ name, sub, above, verdict }: DrawerHeaderProps) {
  return (
    <header className="rt-drawer-head">
      <h2>{name}</h2>
      {above}
      {sub ? <div className="rt-drawer-sub">{sub}</div> : null}
      {verdict ? <div className="rt-drawer-verdict">{verdict}</div> : null}
    </header>
  );
}

/**
 * Sanitised provider markup.
 *
 * The only `dangerouslySetInnerHTML` in the drawer, and it is only safe because of
 * what produces the string: `lib/upstream-html.ts` parses provider HTML with
 * DOMParser and walks it against a tag/attribute whitelist. Never pass anything else
 * to this — if a value did not come out of that module, it is not sanitised.
 */
export function ProviderHtml({ html, inline = false }: { html: string; inline?: boolean }) {
  const className = inline ? 'rt-drawer-html rt-drawer-html--inline' : 'rt-drawer-html';
  return <div className={className} dangerouslySetInnerHTML={{ __html: html }} />;
}

export interface DirectionsButtonProps {
  name: string;
  lng: number | undefined;
  lat: number | undefined;
  /** Search-result kind, which drives the pin colour once it is a trip stop. */
  kind?: string;
  onAdded: () => void;
}

/**
 * "Directions" / "Add stop", depending on whether a trip is being built.
 *
 * The vanilla version was an HTML string with `data-*` attributes, picked up by a
 * delegated listener in the vanilla chrome — indirection
 * that existed so the drawer did not import the topbar. `tripStore` is that seam
 * now, so the button calls it directly and the data attributes go away.
 *
 * Icon-only, with the label in `aria-label` and `title`, exactly as before: the
 * tooltip is what discloses that the action changes meaning mid-trip.
 */
export function DirectionsButton({ name, lng, lat, kind = 'PLACE', onAdded }: DirectionsButtonProps) {
  const mode = useTripStore((s) => s.mode);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;

  const label = mode === 'directions' ? 'Add stop' : 'Directions';
  return (
    <Button
      variant="secondary"
      iconOnly
      aria-label={label}
      title={label}
      onClick={() => {
        // `addPoiToTrip`, not `tripStore.addStop`: the POI is the DESTINATION and
        // the mode becomes directions, which a bare append does not do. Pressing
        // this in browse mode with `addStop` left the POI as the search row and the
        // planner in browse mode — a parity break against the vanilla's
        // `addTripStopFromExternal`, invisible until 4e mounted the topbar.
        addPoiToTrip({ name: name || 'Selected place', lng: lng as number, lat: lat as number, kind });
        // The vanilla handler closed the drawer after adding, so the map and the
        // trip row are both visible immediately.
        onAdded();
      }}
    >
      <DirectionsIcon />
    </Button>
  );
}

function DirectionsIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M21 10l-7 7-3-3-9 9" />
      <path d="M14 10h7v7" />
    </svg>
  );
}

/** A row of small labels. Empty entries drop out, so sparse data renders sparse. */
export function Pills({ items }: { items: (string | null | undefined | false)[] }) {
  const present = items.filter((item): item is string => Boolean(item));
  if (present.length === 0) return null;
  return (
    <div className="rt-drawer-pills">
      {present.map((item) => (
        <span className="rt-drawer-pill" key={item}>
          {item}
        </span>
      ))}
    </div>
  );
}

/**
 * Whatever the ETL did not promote, as a collapsed key/value table.
 *
 * A "what's available" surface rather than a primary read, so it stays closed by
 * default. Nested objects and arrays render as collapsed JSON; anything that looks
 * like a URL becomes a link. Values are dropped when empty — an empty string, an
 * empty array and an empty object all count.
 */
export function UpstreamTable({ upstream }: { upstream: unknown }) {
  if (!upstream || typeof upstream !== 'object' || Array.isArray(upstream)) return null;

  const entries = Object.entries(upstream as Record<string, unknown>).filter(([, value]) => {
    if (value === null || value === undefined) return false;
    if (typeof value === 'string') return value.trim() !== '';
    if (Array.isArray(value)) return value.length > 0;
    if (typeof value === 'object') return Object.keys(value).length > 0;
    return true;
  });
  if (entries.length === 0) return null;

  return (
    <details className="rt-drawer-upstream">
      <summary>Upstream data ({entries.length})</summary>
      <table className="rt-drawer-upstream-table">
        <tbody>
          {entries.map(([key, value]) => (
            <tr key={key}>
              <th>{key}</th>
              <td>
                <UpstreamValue value={value} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}

function UpstreamValue({ value }: { value: unknown }) {
  if (value !== null && typeof value === 'object') {
    return (
      <details>
        <summary>{Array.isArray(value) ? `[${value.length}]` : '{…}'}</summary>
        <pre>{JSON.stringify(value, null, 2)}</pre>
      </details>
    );
  }
  // Not the `text` helper above — this one stringifies anything, including
  // numbers and booleans, because an upstream table shows whatever is there.
  const asText = String(value);
  if (/^https?:\/\//.test(asText)) {
    return (
      <a href={asText} target="_blank" rel="noreferrer">
        {asText}
      </a>
    );
  }
  return <>{asText}</>;
}

/**
 * One `Call …` button per number in a provider's phone field.
 *
 * The splitting and `tel:` rules come from `lib/phone.ts` — one source of truth for
 * "what counts as a number". It had two renderers while `web/` existed; this is the
 * one that outlived it, and `phone.test.ts` now pins those rules directly rather
 * than through the string builder.
 */
export function CallButtons({ phone }: { phone: unknown }) {
  return (
    <>
      {phoneNumbers(text(phone)).map((number) => (
        <Button key={number} variant="tertiary" href={telHref(number)}>
          Call {formatPhone(number)}
        </Button>
      ))}
    </>
  );
}

/**
 * MapLibre stringifies nested-object properties on the way out of a GeoJSON
 * source, so `upstream` can arrive as JSON text.
 *
 * Kept as a function rather than folded into the flattener: it is a MapLibre
 * artifact, and `lib/poi.ts` is shared with paths that never touch the map.
 */
export function reviveJson(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}
