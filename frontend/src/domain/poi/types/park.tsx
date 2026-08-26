// The park page — a campground page whose availability block would count children
// instead of sites.
//
// That rollup is the one thing this type is for and the one thing no endpoint
// returns yet, so the block is absent rather than empty. Everything else here is
// the same page the campground renders, which is the point of a fixed block order:
// a park is not a different page, it is this page with different blocks present.
import { Button } from '@ui';
import { descriptionHtml } from '@/lib/upstream-html';
import type { PoiFeature } from '@/lib/poi';
import {
  DirectionsButton,
  ProviderHtml,
  UpstreamTable,
  coordinatesOf,
  eyebrowFor,
  subline,
  text,
  useDistanceTo,
} from '../fields';
import {
  PoiActions,
  PoiHero,
  PoiIdentity,
  PoiProse,
  PoiProvenance,
  PoiSpecs,
} from '../PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from '../PoiPageShell';
import type { PoiSpec } from '../model';
import { presentSpecs, type PoiTypeProps } from './common';

/** The heading the park's one spec block carries. */
const ABOUT_HEADING = 'About the park';

/** The prose block's heading — the same words the campground page uses. */
const GOOD_TO_KNOW_HEADING = 'Good to know';

const AREA_LABEL = 'Area';

/**
 * The row that says whether "inside the park" is answerable at all.
 *
 * The design wants a park page to tell what is *inside* the boundary from what is
 * merely *near* it, and that distinction is only a fact when the record carries a
 * boundary: PAD-US ships polygons and `/api/pois/{id}` hands the whole geometry
 * back rather than a centroid, so a Polygon/MultiPolygon record can be asked
 * "is this point in it" and a Point record can only ever answer "close by".
 *
 * So this states the relationship the page is entitled to make, and nothing more.
 * The counts that belong beside it — how many campgrounds are inside, which
 * neighbours are outside — need a query no endpoint answers yet; when one does,
 * this is the row it lands next to. A record with no boundary prints nothing,
 * because "not mapped" and "we did not look" are the same sentence to a reader.
 */
const BOUNDARY_LABEL = 'Boundary';
const BOUNDARY_MAPPED = 'Mapped';
const BOUNDARY_GEOMETRIES = new Set(['Polygon', 'MultiPolygon']);

/**
 * The group page each kind of park is listed on, which is the step above it.
 *
 * A national park's parent is the park system, not the state it happens to sit in:
 * 4f groups them by country and agency because that is what changes the booking
 * site, and a park spanning a state line has one system and two states. A state
 * park's parent IS its state — that is the 4e page, and the one thing that lists it.
 */
const PARK_SYSTEM_PARENT = 'National parks';

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

  // Both promoted by the ETL onto every category's detail, so this is the same
  // read the campground page makes — no park-specific field invented for it.
  const photo = text(p.photo_url);
  const about = descriptionHtml(p.description);

  const [url, host] = externalParkLink(national, name, stateName);

  // The step above this page, and the only one printed: a chain would restate the
  // title, and for a state park it would restate the subtitle too.
  const parent = national ? PARK_SYSTEM_PARENT : stateName;

  // Whatever the parent step already says comes out of the subtitle, and the agency
  // reads here rather than as a spec row — 4c's subtitle is exactly "California ·
  // National Park Service". Only where the step is on screen, though: the drawer
  // omits it, so there the subtitle stays the one line that names the state.
  const stepShown = variant === 'page' ? parent : '';
  const subtitle = subline([stateName === stepShown ? '' : stateName, manager, distance]);

  const specs = presentSpecs([
    Number.isFinite(acres) && acres > 0
      ? { label: AREA_LABEL, value: `${Math.round(acres).toLocaleString()} acres` }
      : null,
    boundarySpec(feature),
  ]);

  const blocks: PoiBlockSlots = {
    ...(photo ? { hero: <PoiHero url={photo} alt={name} /> } : null),
    identity: (
      <PoiIdentity
        // Almost every park's name ends in its own type, so this is usually empty
        // and the block is omitted — "National park" over "Yosemite National Park"
        // is the page saying the same word twice.
        eyebrow={eyebrowFor(national ? 'National park' : 'State park', name)}
        title={name}
        subtitle={subtitle}
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
    ...(about
      ? {
          goodToKnow: (
            <PoiProse heading={GOOD_TO_KNOW_HEADING}>
              <ProviderHtml html={about} />
            </PoiProse>
          ),
        }
      : null),
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

  // No href yet: 4e and 4f are the two pages this milestone leaves without data, and
  // an unlinked step renders as text rather than as a link nothing happens to.
  return (
    <PoiPageShell
      variant={variant}
      parent={parent ? { label: parent } : undefined}
      blocks={blocks}
    />
  );
}

/**
 * "Boundary · Mapped", when the record actually carries one.
 *
 * See `BOUNDARY_LABEL` above for why this is the honest half of inside-vs-outside:
 * a polygon can be asked whether a point is inside it, a centroid cannot, and a
 * record that arrived as a point says nothing rather than implying an answer.
 */
function boundarySpec(feature: PoiFeature): PoiSpec | null {
  const kind = (feature.geometry as { type?: unknown } | undefined)?.type;
  if (typeof kind !== 'string' || !BOUNDARY_GEOMETRIES.has(kind)) return null;
  return { label: BOUNDARY_LABEL, value: BOUNDARY_MAPPED };
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
