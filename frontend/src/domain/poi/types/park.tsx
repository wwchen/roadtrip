// The park page — a campground page whose availability block would count children
// instead of sites.
//
// That rollup is the one thing this type is for and the one thing no endpoint
// returns yet, so the block is absent rather than empty. Everything else here is
// the same page the campground renders, which is the point of a fixed block order:
// a park is not a different page, it is this page with different blocks present.
import { Button } from '@ui';
import {
  DirectionsButton,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from '../fields';
import {
  PoiActions,
  PoiIdentity,
  PoiProvenance,
  PoiSpecs,
} from '../PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from '../PoiPageShell';
import { presentSpecs, type PoiTypeProps } from './common';

/** The heading the park's one spec block carries. */
const ABOUT_HEADING = 'About the park';

const NPS_MANAGER = 'National Park Service';

/**
 * The group page each kind of park is listed on, which is the step above it.
 *
 * A national park's parent is the park system, not the state it happens to sit in:
 * 4f groups them by country and agency because that is what changes the booking
 * site, and a park spanning a state line has one system and two states. A state
 * park's parent IS its state — that is the 4e page, and the one thing that lists it.
 */
const PARK_SYSTEM_CRUMB = 'National parks';

const NPS_HOST = 'nps.gov';

export function ParkPoiPage({ feature, variant, onClose }: PoiTypeProps) {
  const p = feature.properties;
  const national = p.category === 'national-park';
  const name = text(p.Unit_Nm) || text(p.Loc_Nm) || 'Park';
  const stateName = text(p.State_Nm);
  const manager = text(p.Mang_Name);
  const acres = Number(p.GIS_Acres);
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);

  const [url, host] = externalParkLink(national, name, stateName);

  const specs = presentSpecs([
    // Redundant on an NPS park — every one of them is managed by the NPS.
    manager && manager !== NPS_MANAGER ? { label: 'Managed by', value: manager } : null,
    Number.isFinite(acres) && acres > 0
      ? { label: 'Area', value: `${Math.round(acres).toLocaleString()} acres` }
      : null,
    stateName ? { label: 'State', value: stateName } : null,
  ]);

  const blocks: PoiBlockSlots = {
    identity: (
      <PoiIdentity
        eyebrow={national ? 'National park' : 'State park'}
        title={name}
        subtitle={subline([stateName, distance])}
      />
    ),
    actions: (
      <PoiActions>
        <DirectionsButton
          name={name}
          lng={lng}
          lat={lat}
          kind={national ? 'NP' : 'SP'}
          onAdded={onClose ?? noop}
        />
        {/* `href` makes LDS render the button as an anchor — its own documented
            "a link that must look like a button IS this button" path. */}
        <Button variant="primary" href={url} target="_blank" rel="noreferrer">
          {host === NPS_HOST ? `Open on ${NPS_HOST}` : 'Search the web'}
        </Button>
      </PoiActions>
    ),
    ...(specs.length > 0 ? { specs: <PoiSpecs list={{ heading: ABOUT_HEADING, rows: specs }} /> } : null),
    ...(p.upstream
      ? {
          provenance: (
            <PoiProvenance>
              <UpstreamTable upstream={p.upstream} />
            </PoiProvenance>
          ),
        }
      : null),
  };

  // Neither ancestor has a page to link to yet — 4e and 4f are the two this
  // milestone leaves without data — so both render as text. `PoiBreadcrumbs` styles
  // an unlinked step as text rather than as a link nothing happens to.
  const parentCrumb = national ? PARK_SYSTEM_CRUMB : stateName;
  const crumbs = [parentCrumb, name].filter(Boolean).map((label) => ({ label }));

  return <PoiPageShell variant={variant} crumbs={crumbs} blocks={blocks} />;
}

/**
 * A usable external link for a park.
 *
 * Neither source has deterministic per-park URLs, so both are searches: NPS exposes
 * no stable slug, and state parks have no unified site at all.
 */
function externalParkLink(national: boolean, name: string, stateName: string): [string, string] {
  if (national) {
    return [
      `https://www.nps.gov/findapark/advanced-search.htm?q=${encodeURIComponent(name)}`,
      NPS_HOST,
    ];
  }
  const query = encodeURIComponent(`${name} ${stateName} state park`);
  return [`https://www.google.com/search?q=${query}`, 'search'];
}

const noop = () => {};
