// Every type whose page is identity, actions and one spec list.
//
// 4d puts five of these side by side — charger, first-come ground, trailhead, town
// stop, dropped pin — and the only thing that differs between them is the heading on
// that one block and the fields under it. So they are one component and a table of
// descriptors, not five components: three similar code sites are usually one missing
// abstraction, and this was five.
//
// The block after the rule is the one thing the type is *for*. A dropped pin is this
// page with almost everything absent, which is the point — it never looks broken.
import { Button } from '@ui';
import type { PoiLink } from '../model';
import {
  CallButtons,
  DirectionsButton,
  SharePoiButton,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from '../fields';
import {
  PoiActions,
  PoiGlance,
  PoiHero,
  PoiIdentity,
  PoiLinks,
  PoiProvenance,
  PoiSpecs,
  type PoiTag,
} from '../PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from '../PoiPageShell';
import { presentSpecs, type PoiTypeProps } from './common';

/** One labelled row of the type's spec block, and the flat property that fills it. */
export interface PlaceField {
  label: string;
  key: string;
}

export interface PlaceTypeSpec {
  /** The uppercase line above the title — what this pin is. */
  eyebrow: string;
  /** Shown as the title when the record has no name of its own. */
  fallbackName: string;
  /** The trip store's pin kind, which decides the marker colour once it is a stop. */
  kind: string;
  /**
   * The type's one spec block, or nothing. One member rather than two, so a type
   * cannot carry fields with no heading to put them under — that combination
   * renders nothing at all. Omitting it is a real answer: a gym shows every fact
   * it has above the rule.
   */
  specs?: {
    heading: string;
    /** The fields the block shows, in order. Absent properties drop out. */
    fields: readonly PlaceField[];
  };
  /** Fallback label for the record's `website`. `brand` wins when the API sends one. */
  websiteLabel?: string;
  /** Whether to offer `tel:` buttons for the record's phone numbers. */
  call?: boolean;
}

export interface PlacePoiPageProps extends PoiTypeProps {
  spec: PlaceTypeSpec;
}

/**
 * Coords AND label, so the dropped pin lands on the place and Google routes from
 * wherever the user is, on any platform.
 */
function googleMapsUrl(name: string, lng: number | undefined, lat: number | undefined): string {
  if (lng == null || lat == null) return '';
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(name)}%20${lat},${lng}`;
}

export function PlacePoiPage({ feature, variant, onClose, spec }: PlacePoiPageProps) {
  const p = feature.properties;
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);
  const name = text(p.name) || spec.fallbackName;

  // Street, city, then state and zip as one token. Empty pieces drop out, so a
  // missing zip leaves no stray comma.
  const address = [
    text(p.street),
    text(p.city),
    [text(p.state), text(p.postcode)].filter(Boolean).join(' '),
  ]
    .filter(Boolean)
    .join(', ');

  const mapsUrl = googleMapsUrl(name, lng, lat);
  // No fallback search: a button promising a page must not land on a locator.
  const website = text(p.website);
  const brand = text(p.brand);
  const websiteLabel = brand ? `${brand} page` : spec.websiteLabel;
  const photo = text(p.photo_url);

  const specs = presentSpecs(
    (spec.specs?.fields ?? []).map((field) => {
      const value = text(p[field.key]);
      return value ? { label: field.label, value } : null;
    }),
  );

  const tags: PoiTag[] = text(p.opening_hours) ? [{ label: text(p.opening_hours) }] : [];
  const links: PoiLink[] = text(p.official_url)
    ? [{ label: 'Official page', href: text(p.official_url) }]
    : [];

  const blocks: PoiBlockSlots = {
    ...(photo ? { hero: <PoiHero url={photo} alt={name} /> } : null),
    identity: (
      <PoiIdentity
        eyebrow={spec.eyebrow}
        title={name}
        subtitle={subline([address, distance])}
      />
    ),
    actions: (
      <PoiActions>
        <DirectionsButton name={name} lng={lng} lat={lat} kind={spec.kind} onAdded={onClose ?? noop} />
        {mapsUrl ? (
          <Button variant="primary" href={mapsUrl} target="_blank" rel="noopener">
            Open in Google Maps
          </Button>
        ) : null}
        {website && websiteLabel ? (
          <Button variant="secondary" href={website} target="_blank" rel="noreferrer">
            {websiteLabel}
          </Button>
        ) : null}
        {spec.call ? <CallButtons phone={p.phone} /> : null}
        <SharePoiButton id={feature.id} />
      </PoiActions>
    ),
    ...(tags.length > 0 ? { glance: <PoiGlance tags={tags} /> } : null),
    ...(spec.specs && specs.length > 0
      ? { specs: <PoiSpecs list={{ heading: spec.specs.heading, rows: specs }} /> }
      : null),
    ...(links.length > 0 ? { links: <PoiLinks links={links} /> } : null),
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

  return <PoiPageShell variant={variant} blocks={blocks} />;
}

const noop = () => {};
