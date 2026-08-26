// The group page — one park, and the campgrounds inside it against the same nights.
//
// 4f's national-parks page is not a POI page with a different theme: it is this
// page. Identity says which park, and the block after the rule — the availability table —
// is the one thing the type is *for*. A park page answers "can I stay here"; this
// page answers "which of these can I stay at, and when", and that comparison is why
// the routed page exists at all: twelve campgrounds against twelve nights does not
// fit in a 520px drawer.
//
// No fetching happens here. The rows arrive on the feature's own properties, as
// every other type's fields do, and the block is omitted when they are absent —
// the same rule the park page follows for the rollup it has no endpoint for.
import { UpstreamTable, eyebrowFor, subline, text } from '../fields';
import { PoiIdentity, PoiProvenance } from '../PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from '../PoiPageShell';
import {
  AvailabilityTable,
  type AvailabilityCell,
  type AvailabilityNight,
  type AvailabilityPlace,
} from '../AvailabilityTable';
import type { PoiTypeProps } from './common';

/**
 * The step above the group page.
 *
 * The same step a national park itself carries: 4f groups parks by country and
 * agency, so the system lists the park and the park lists its campgrounds.
 */
const PARK_SYSTEM_PARENT = 'National parks';

const EYEBROW = 'National park';
const FALLBACK_NAME = 'Park';
const AVAILABILITY_HEADING = 'Campgrounds by night';
const AVAILABILITY_EMPTY = 'No nights are published for this park yet.';

export function ParkGroupPoiPage({ feature, variant, availability }: PoiTypeProps) {
  const p = feature.properties;
  const name = text(p.name) || text(p.Unit_Nm) || FALLBACK_NAME;
  const manager = text(p.Mang_Name) || text(p.agency);
  const stateName = text(p.State_Nm) || text(p.state);

  const nights = parseNights(p.nights);
  const places = parsePlaces(p.campgrounds);

  // The injected slot wins when a surface has a live grid to put there; otherwise
  // the page renders whatever the record itself carries. Either way the slot is
  // absent — not empty — when there is nothing at all to show.
  const grid =
    availability ??
    (nights.length > 0 && places.length > 0 ? (
      <AvailabilityTable
        heading={AVAILABILITY_HEADING}
        caption={captionFor(places.length)}
        emptyLabel={AVAILABILITY_EMPTY}
        nights={nights}
        places={places}
      />
    ) : null);

  const blocks: PoiBlockSlots = {
    identity: (
      <PoiIdentity
        eyebrow={eyebrowFor(EYEBROW, name)}
        title={name}
        // The step above names the park system, never the state, so nothing here
        // restates it — unlike the park page, where the two can collide.
        subtitle={subline([stateName, manager])}
      />
    ),
    ...(grid ? { availability: grid } : null),
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

  return (
    <PoiPageShell variant={variant} parent={{ label: PARK_SYSTEM_PARENT }} blocks={blocks} />
  );
}

const captionFor = (count: number) =>
  `${count} ${count === 1 ? 'campground' : 'campgrounds'} in this park.`;

type Unknown = Record<string, unknown>;

const asArray = (value: unknown): Unknown[] =>
  Array.isArray(value) ? value.filter((item): item is Unknown => isObject(item)) : [];

const isObject = (value: unknown): value is Unknown =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** The columns. A night with no date is not a column — nothing could key a cell to it. */
function parseNights(value: unknown): AvailabilityNight[] {
  return asArray(value)
    .map((night) => {
      const date = text(night.date);
      if (!date) return null;
      const sublabel = text(night.sublabel);
      return {
        date,
        label: text(night.label) || date,
        ...(sublabel ? { sublabel } : null),
      };
    })
    .filter((night): night is AvailabilityNight => night !== null);
}

/** The rows. Same rule: a campground with no id cannot be keyed, so it is not a row. */
function parsePlaces(value: unknown): AvailabilityPlace[] {
  return asArray(value)
    .map((place) => {
      const id = text(place.id);
      if (!id) return null;
      const note = text(place.note);
      const href = text(place.href);
      return {
        id,
        name: text(place.name) || id,
        ...(note ? { note } : null),
        ...(href ? { href } : null),
        cells: parseCells(place.nights),
      };
    })
    .filter((place): place is AvailabilityPlace => place !== null);
}

/**
 * One row's cells, by night key.
 *
 * Two shapes because both are honest: a bare status string when the source knows
 * only open-or-not, and an object when it also counted the sites. Anything else
 * normalises to `unknown` inside `AvailabilityTable`, which is the truthful reading of a
 * value we cannot interpret.
 */
function parseCells(value: unknown): ReadonlyMap<string, AvailabilityCell> {
  const cells = new Map<string, AvailabilityCell>();
  if (!isObject(value)) return cells;
  for (const [date, raw] of Object.entries(value)) {
    cells.set(date, parseCell(raw));
  }
  return cells;
}

function parseCell(raw: unknown): AvailabilityCell {
  const status = isObject(raw) ? text(raw.status) : text(raw);
  if (status !== 'available') return { status: coerceClosed(status) };
  const open = isObject(raw) ? Number(raw.open) : Number.NaN;
  return Number.isFinite(open) && open >= 0 ? { status: 'available', open } : { status: 'available' };
}

/**
 * The non-open statuses, spelled here rather than trusted from the wire.
 *
 * `AvailabilityTable` would normalise an unrecognised string anyway, but the union is
 * what callers see, so the coercion belongs at the boundary that produces it.
 */
const CLOSED_STATUSES = new Set(['first_come', 'reserved', 'closed', 'past']);

const coerceClosed = (status: string): Exclude<AvailabilityCell['status'], 'available'> =>
  CLOSED_STATUSES.has(status)
    ? (status as Exclude<AvailabilityCell['status'], 'available'>)
    : 'unknown';
