// Reading a campsite catalog row into facts a camper cares about.
//
// Every fact is a promoted column. What is still read out of `source_payload` has no
// column and comes from one vendor: rec.gov's reserve type and type of use, Aspira's
// description and attribute list, and the minimum party size both of them carry.
import type { Campsite } from '@/api/campsite-api';

/** Feature chips past this are noise in a row that is already dense. */
const MAX_FEATURES = 12;
const MAX_DESCRIPTION_CHARS = 260;
const MAX_FEATURE_CHARS = 84;
const MAX_EQUIPMENT = 4;

export interface SiteFact {
  label: string;
  value: string;
}

type Raw = Record<string, unknown>;

export const rawPayload = (site: Partial<Campsite>): Raw =>
  site.source_payload && typeof site.source_payload === 'object' && !Array.isArray(site.source_payload)
    ? (site.source_payload as Raw)
    : {};

/**
 * The labelled facts, in reading order: where the site is, what kind it is and how
 * many people fit come first; the provider and its id are last because they are for
 * us, not for the camper.
 */
export function detailFacts(site: Partial<Campsite>, raw: Raw = rawPayload(site)): SiteFact[] {
  const facts: SiteFact[] = [];
  const add = (label: string, value: unknown): void => {
    const text = compactText(value);
    if (text) facts.push({ label, value: text });
  };

  add('Loop', site.loop_name);
  add('Type', site.kind_listed || site.kind);
  add('Capacity', capacityLabel(site, raw));
  add('Reserve', raw.campsite_reserve_type);
  add('Use', raw.type_of_use);
  add('Equipment', names(site.equipment).slice(0, MAX_EQUIPMENT).join(', '));
  add('Provider', site.data_provider);
  add('Provider ID', site.data_provider_ref);
  return facts;
}

/**
 * "4-6 people" / "Up to 6 people" / "2+ people" — three different claims.
 *
 * The minimum has no column yet, so it is the one number still read from the payload
 * under the vendor's own name.
 */
export function capacityLabel(site: Partial<Campsite>, raw: Raw): string {
  const min = numberOrNull(raw.min_num_people ?? raw.min_capacity);
  const max = numberOrNull(site.max_people);
  if (min != null && max != null && min !== max) return `${min}-${max} people`;
  if (max != null) return `Up to ${max} people`;
  if (min != null) return `${min}+ people`;
  return '';
}

/**
 * Feature chips: the site's own boolean and measurement columns, then Aspira's
 * attribute list. Only a `true` column becomes a chip: `false` in these rows usually
 * means absence of data rather than absence of a firepit.
 */
export function featureLabels(site: Partial<Campsite>, raw: Raw = rawPayload(site)): string[] {
  const labels: string[] = [];
  const flag = (label: string, value: unknown): void => {
    if (value === true) labels.push(label);
  };
  const measure = (label: string, value: unknown, unit = ''): void => {
    if (typeof value === 'number') labels.push(`${label}: ${value}${unit}`);
  };

  flag('Firepit', site.firepit);
  flag('Picnic table', site.picnic_table);
  flag('ADA accessible', site.ada_accessible);
  flag('Water hookups', site.water_hookups);
  flag('Electric hookups', site.electric_hookups);
  flag('Sewer hookups', site.sewer_hookups);
  flag('Pull-through', site.pull_through);
  measure('Max cars', site.max_cars);
  measure('Driveway length', site.driveway_length, ' ft');
  measure('Max RV length', site.max_rv_length, ' ft');
  measure('Max trailer length', site.max_trailer_length, ' ft');
  return [...labels, ...attributeLabels(raw.defined_attributes)]
    .map((label) => truncateText(label, MAX_FEATURE_CHARS))
    .slice(0, MAX_FEATURES);
}

/**
 * Aspira's `defined_attributes`: `[{ name, value }]`, name and value labels already
 * resolved from the tenant dictionary by the ETL. An entry with no name is a
 * definition the dictionary did not know; rendering its id would be showing the user
 * our own plumbing.
 */
export function attributeLabels(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    const name = compactText(record(entry).name);
    if (!name) return [];
    const formatted = formatValue(record(entry).value);
    return [formatted ? `${name}: ${formatted}` : name];
  });
}

/** A number, a label, or a list of either, as display text. */
function formatValue(value: unknown): string {
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(formatValue).filter(Boolean).join(', ');
  return compactText(value);
}

/** A provider description as one clamped paragraph of plain text, tags stripped. */
export function descriptionText(value: unknown, maxChars = MAX_DESCRIPTION_CHARS): string {
  if (typeof value !== 'string') return '';
  return truncateText(compactText(value.replace(/<[^>]*>/g, ' ')), maxChars);
}

/** The first photo. `photos` is `[{ url }]` from every campsite ETL. */
export function photoUrl(site: Partial<Campsite>): string {
  const first = Array.isArray(site.photos) ? record(site.photos[0]) : {};
  return typeof first.url === 'string' ? first.url : '';
}

/** The `name` of each `[{ name }]` entry, which is how every vendor lists equipment. */
function names(items: unknown): string[] {
  if (!Array.isArray(items)) return [];
  return items.map((item) => compactText(record(item).name)).filter(Boolean);
}

const record = (value: unknown): Raw => (value && typeof value === 'object' ? (value as Raw) : {});

const numberOrNull = (value: unknown): number | null => (typeof value === 'number' ? value : null);

function compactText(value: unknown): string {
  return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
}

function truncateText(value: string, maxLength: number): string {
  if (value.length <= maxLength) return value;
  return `${value.slice(0, maxLength - 3).trim()}...`;
}
