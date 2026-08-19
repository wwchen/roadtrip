// The campground page — the reference type, and the only one that uses all thirteen
// blocks.
//
// A first-come ground is not a separate type: it is this page with the availability
// block absent, because no provider publishes inventory for it. That is the block
// order working as designed rather than a special case.
import { Button } from '@ui';
import { descriptionHtml, upstreamDecorations } from '@/lib/upstream-html';
import {
  CONTACT_GROUP,
  SOURCE_GROUP,
  STAY_DETAILS_GROUP,
  activityList,
  amenityTags,
  availabilitySupported,
  campgroundCtas,
  carrierSignals,
  isNoCta,
  parentParkName,
  rating,
  seasonVerdict,
  structuredDetails,
  verified,
} from '../campground-detail';
import {
  DirectionsButton,
  ProviderHtml,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from '../fields';
import {
  PoiActions,
  PoiContact,
  PoiGlance,
  PoiHero,
  PoiIdentity,
  PoiLinks,
  PoiProse,
  PoiProvenance,
  PoiSpecs,
  PoiVerifiedStamp,
  type PoiTag,
} from '../PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from '../PoiPageShell';
import { presentSpecs, specsFrom, type PoiTypeProps } from './common';
import { CarrierSignals } from './CarrierSignals';

export function CampgroundPoiPage({ feature, variant, onClose, availability }: PoiTypeProps) {
  const p = feature.properties;
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);

  const name = text(p.name) || 'Campground';
  const decorations = upstreamDecorations(p.upstream);
  // Parent context precedence, unchanged: the upstream record's own parent, then a
  // promoted field, then an inference from official-link titles.
  const knownParent = decorations.parentName || text(p.parent_name);
  const parent = knownParent || parentParkName(p) || text(p.typeLabel);
  const agency = text(p.agency).trim();
  const region = text(p.state) || text(p.country);

  const verdict = seasonVerdict(p.season, p.reservable);
  const ctas = campgroundCtas(p);
  const details = structuredDetails(p);
  const signals = carrierSignals(p);
  const stars = rating(p);
  const freshness = verified(p);
  const about = descriptionHtml(p.description);
  const photo = text(p.photo_url);
  const sites = Number(p.sites);

  const tags: PoiTag[] = [
    ...amenityTags(p),
    ...activityList(p).map((label) => ({ label, absent: false })),
  ];

  const stay = presentSpecs([
    ...(specsFrom(details, STAY_DETAILS_GROUP) ?? []),
    decorations.stayLimit ? { label: 'Stay limit', value: decorations.stayLimit } : null,
    Number.isFinite(sites) && sites > 0 ? { label: 'Sites', value: sites.toLocaleString() } : null,
    stars
      ? { label: 'Rating', value: `${stars.stars} ${stars.average.toFixed(1)} (${stars.count.toLocaleString()})` }
      : null,
    text(p.booking_system) ? { label: 'Booking via', value: text(p.booking_system) } : null,
  ]);
  const contact = specsFrom(details, CONTACT_GROUP);
  const source = specsFrom(details, SOURCE_GROUP);
  // `CampgroundLink` is already `{ href, label }`, which is `PoiLink`.
  const links = details.links;

  const blocks: PoiBlockSlots = {
    ...(photo ? { hero: <PoiHero url={photo} alt={name} /> } : null),
    identity: (
      <PoiIdentity
        eyebrow={agency ? <>Campground · {agency}</> : 'Campground'}
        title={name}
        subtitle={subline([parent, region, distance])}
        verdict={
          verdict ? (
            <span className={`rt-poi-verdict-tone rt-poi-verdict-tone--${verdict.tone}`}>
              {verdict.text}
            </span>
          ) : null
        }
      />
    ),
    actions: (
      <PoiActions>
        <DirectionsButton name={name} lng={lng} lat={lat} kind="CG" onAdded={onClose ?? noop} />
        {isNoCta(ctas) ? (
          <span className="rt-poi-cta-disabled">{ctas.disabledLabel}</span>
        ) : (
          ctas.map((cta) => (
            <Button key={cta.url} variant={cta.variant} href={cta.url} target="_blank" rel="noreferrer">
              {cta.label}
            </Button>
          ))
        )}
      </PoiActions>
    ),
    // Gated on the backend's own provider-capability flag, so the grid only appears
    // for pins that genuinely have availability to show. "No availability shown" and
    // "this provider has no availability" are different facts, and only the flag
    // tells them apart.
    ...(availabilitySupported(p) && availability ? { availability } : null),
    ...(tags.length > 0 || signals.length > 0
      ? {
          glance: (
            <PoiGlance tags={tags} extra={signals.length > 0 ? <CarrierSignals signals={signals} /> : null} />
          ),
        }
      : null),
    ...(decorations.directionsHtml
      ? {
          gettingThere: (
            <PoiProse heading="Getting there">
              <ProviderHtml html={decorations.directionsHtml} />
            </PoiProse>
          ),
        }
      : null),
    ...(about || decorations.feesHtml || details.alerts.length > 0
      ? {
          goodToKnow: (
            <PoiProse heading="Good to know">
              {about ? <ProviderHtml html={about} /> : null}
              {decorations.feesHtml ? <ProviderHtml html={decorations.feesHtml} /> : null}
              {details.alerts.map((alert, index) => (
                <p className="rt-poi-alert" key={`${alert.title}-${index}`}>
                  {alert.title ? <strong>{alert.title}</strong> : null}
                  {alert.title && alert.body ? ' — ' : null}
                  {alert.body}
                </p>
              ))}
            </PoiProse>
          ),
        }
      : null),
    ...(stay.length > 0 ? { specs: <PoiSpecs list={{ heading: STAY_DETAILS_GROUP, rows: stay }} /> } : null),
    ...(contact ? { contact: <PoiContact rows={contact} /> } : null),
    ...(links.length > 0 ? { links: <PoiLinks links={links} /> } : null),
    ...(freshness ? { verified: <PoiVerifiedStamp verified={freshness} /> } : null),
    ...(source || p.upstream
      ? {
          provenance: (
            <PoiProvenance>
              {source ? <PoiSpecs list={{ heading: SOURCE_GROUP, rows: source }} /> : null}
              <UpstreamTable upstream={p.upstream} />
            </PoiProvenance>
          ),
        }
      : null),
  };

  // The INFERRED parent is deliberately not a crumb. `parentParkName` guesses a
  // containing park from official-link titles, and a subline that names it is a
  // hint the reader can discount, where a breadcrumb asserts containment. Only a
  // parent the record actually states earns a step in the trail.
  const crumbs = presentCrumbs(region, knownParent, name);

  return <PoiPageShell variant={variant} crumbs={crumbs} blocks={blocks} />;
}

/**
 * State → park → campground.
 *
 * Only the steps that exist appear, and the trail renders at all only when there is
 * more than one — which is `PoiBreadcrumbs`' own rule. Neither ancestor has a page
 * to link to yet, so both are plain text; the trail still says where you are.
 */
function presentCrumbs(region: string, parent: string, name: string) {
  return [region, parent, name].filter(Boolean).map((label) => ({ label }));
}

/** The directions button always dismisses after adding; a routed page has nothing to dismiss. */
const noop = () => {};
