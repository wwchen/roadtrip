import { Button } from '@ui';
import {
  DirectionsButton,
  DrawerHeader,
  Pills,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from './parts';
import type { DrawerContentProps } from './registry';

/**
 * National / state park drawer.
 *
 * The two kinds differ only in labels and in the external link, which is why the
 * one component takes a `kind` discriminator rather than existing twice.
 *
 * **Unreachable from the map today**, and honestly so: the React map does not
 * request park categories (see `map/viewport.ts`). It is here because the drawer is dispatched by category and a
 * park can still arrive by deep link (`?poi=<park id>`) or from a backend search
 * hit — the id path does not care which layer, if any, is painted.
 */
export function ParkDrawer({ feature, onClose }: DrawerContentProps) {
  const p = feature.properties;
  const kind = p.category === 'national-park' ? 'np' : 'sp';
  const name = text(p.Unit_Nm) || text(p.Loc_Nm) || 'Park';
  const stateName = text(p.State_Nm);
  const manager = text(p.Mang_Name);
  const acres = Number(p.GIS_Acres);
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);

  const [url, label] = externalParkLink(kind, name, stateName);

  return (
    <>
      <DrawerHeader
        name={name}
        sub={subline([kind === 'np' ? 'National Park' : 'State Park', stateName, distance])}
      />
      <div className="rt-drawer-section rt-drawer-actions">
        <DirectionsButton
          name={name}
          lng={lng}
          lat={lat}
          kind={kind === 'np' ? 'NP' : 'SP'}
          onAdded={onClose}
        />
        {/* `href` makes LDS render the button as an anchor — its own documented
            "a link that must look like a button IS this button" path, rather than a
            button with a click handler that navigates. */}
        <Button variant="primary" href={url} target="_blank" rel="noreferrer">
          {label === 'nps.gov' ? 'Open on nps.gov' : `Search ${label}`}
        </Button>
      </div>
      <Pills
        items={[
          // The manager pill is redundant on an NPS park — every one of them is
          // managed by the NPS.
          manager && manager !== 'National Park Service' ? manager : '',
          Number.isFinite(acres) && acres > 0 ? `${acres.toLocaleString()} acres` : '',
        ]}
      />
      <UpstreamTable upstream={p.upstream} />
    </>
  );
}

/**
 * A usable external link for a park.
 *
 * Neither source has deterministic per-park URLs, so both are searches — the
 * legacy comment is worth keeping: NPS exposes no stable slug, and state parks have
 * no unified site at all.
 */
function externalParkLink(kind: 'np' | 'sp', name: string, stateName: string): [string, string] {
  if (kind === 'np') {
    return [
      `https://www.nps.gov/findapark/advanced-search.htm?q=${encodeURIComponent(name)}`,
      'nps.gov',
    ];
  }
  const query = encodeURIComponent(`${name} ${stateName} state park`);
  return [`https://www.google.com/search?q=${query}`, 'search'];
}
