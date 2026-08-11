// Reading a campsite catalog row into facts a camper cares about.
//
// The catalog row carries a
// `source_payload` that is whatever the provider stored, and the four providers
// disagree about the spelling of every field in it. `min_capacity` /`minCapacity` /
// `min_num_people` / `minNumPeople` are the same number from four vendors.
//
// Deliberately read-only and fetch-free: it promotes what is already on the row and
// never asks a provider for more.
import type { Campsite } from '@/api/campsite-api';

/** Feature chips past this are noise in a row that is already dense. */
const MAX_FEATURES = 12;
const MAX_DESCRIPTION_CHARS = 260;
const MAX_FEATURE_CHARS = 84;
/** How deep to walk a payload looking for an image. */
const MAX_IMAGE_SEARCH_DEPTH = 6;

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
 * The labelled facts, in reading order.
 *
 * Order is the original's and is not alphabetical: where the site is, what kind it
 * is, and how many people fit are what a camper checks first; the provider and its
 * id are last because they are for us, not for them.
 */
export function detailFacts(site: Partial<Campsite>, raw: Raw = rawPayload(site)): SiteFact[] {
  const facts: SiteFact[] = [];
  const add = (label: string, value: unknown): void => {
    const text = compactText(formatValue(value));
    if (text) facts.push({ label, value: text });
  };

  add('Loop', site.loop_name || raw.loop || raw._parent_leaf_name);
  add('Type', site.kind_listed || site.kind || raw.site_type || raw.campsite_type);
  add('Capacity', capacityLabel(site, raw));
  add('Reserve', firstString(raw.campsite_reserve_type, raw.reserve_type, raw.reserveType));
  add('Use', firstString(raw.type_of_use, raw.typeOfUse));
  add('Equipment', equipmentLabel(site, raw));
  add('Provider', site.data_provider);
  add('Provider ID', site.data_provider_ref);
  return facts;
}

/** "4-6 people" / "Up to 6 people" / "2+ people" — three different claims. */
export function capacityLabel(site: Partial<Campsite>, raw: Raw): string {
  const min = numberValue(raw.min_capacity ?? raw.minCapacity ?? raw.min_num_people ?? raw.minNumPeople);
  const max = numberValue(
    site.max_people ??
      raw.max_capacity ??
      raw.maxCapacity ??
      raw.max_num_people ??
      raw.maxNumPeople ??
      raw.capacity_rating,
  );
  if (min != null && max != null && min !== max) return `${min}-${max} people`;
  if (max != null) return `Up to ${max} people`;
  if (min != null) return `${min}+ people`;
  return '';
}

function equipmentLabel(site: Partial<Campsite>, raw: Raw): string {
  return itemList(site.equipment ?? raw.allowed_equipment ?? raw.allowedEquipment ?? raw.equipment)
    .slice(0, 4)
    .join(', ');
}

/**
 * Feature chips: the site's own boolean columns first, then whatever the provider's
 * attribute bags describe.
 *
 * Deduplicated case-insensitively because the same fact routinely arrives twice — once
 * as a promoted column and once inside `defined_attributes`.
 */
export function featureLabels(site: Partial<Campsite>, raw: Raw = rawPayload(site)): string[] {
  const labels = [
    ...columnFeatureLabels(site),
    ...attributeLabels(raw.defined_attributes ?? raw.definedAttributes),
    ...attributeLabels(raw.attributes),
    ...attributeLabels(raw.campsite_rules ?? raw.campsiteRules),
    ...attributeLabels(raw.supplemental_camping ?? raw.supplementalCamping),
  ];
  return unique(
    labels.map((label) => truncateText(compactText(label), MAX_FEATURE_CHARS)).filter(Boolean),
  ).slice(0, MAX_FEATURES);
}

/**
 * The promoted boolean and measurement columns.
 *
 * Only `true` becomes a chip. A `false` firepit is not worth a chip saying so, and
 * — more importantly — neither is a missing one, which is what `false` usually means
 * in these rows: absence of data rather than absence of a firepit.
 */
function columnFeatureLabels(site: Partial<Campsite>): string[] {
  const labels: string[] = [];
  const flag = (label: string, value: unknown): void => {
    if (value === true) labels.push(label);
  };
  const measure = (label: string, value: unknown): void => {
    const formatted = formatValue(value);
    if (formatted) labels.push(`${label}: ${formatted}`);
  };

  flag('Firepit', site.firepit);
  flag('Picnic table', site.picnic_table);
  flag('ADA accessible', site.ada_accessible);
  flag('Water hookups', site.water_hookups);
  flag('Electric hookups', site.electric_hookups);
  flag('Sewer hookups', site.sewer_hookups);
  flag('Pull-through', site.pull_through);
  measure('Max cars', site.max_cars);
  measure('Driveway length', lengthLabel(site.driveway_length));
  measure('Max RV length', lengthLabel(site.max_rv_length));
  measure('Max trailer length', lengthLabel(site.max_trailer_length));
  return labels;
}

function lengthLabel(value: unknown): string {
  const feet = numberValue(value);
  return feet == null ? '' : `${feet} ft`;
}

/**
 * Flatten a provider attribute bag into chip text.
 *
 * The shapes vary wildly — arrays of strings, arrays of `{name, value}`, nested
 * arrays, and bare objects with no name at all — so this recurses and takes the first
 * plausible name/value pair it can find. `value === 'true'` is dropped from the label
 * because "Pets allowed: true" reads worse than "Pets allowed".
 */
export function attributeLabels(value: unknown): string[] {
  if (Array.isArray(value)) return value.flatMap((item) => attributeLabels(item));

  if (value && typeof value === 'object') {
    const record = value as Raw;
    const name = firstString(
      record.name,
      record.label,
      record.title,
      record.display_name,
      record.displayName,
      record.attribute_name,
      record.attributeName,
      record.description,
    );
    const rawValue =
      record.value ??
      record.values ??
      record.attribute_value ??
      record.attributeValue ??
      record.boolean_value ??
      record.booleanValue;
    const formatted = formatValue(rawValue);
    if (name && formatted && formatted !== 'true') return [`${name}: ${formatted}`];
    if (name) return [name];
    // An attribute that is only a definition id is a reference we never resolved.
    // Rendering "Definition Id: 41" would be showing the user our own plumbing.
    if (isUnresolvedAttribute(record)) return [];
    return Object.entries(record)
      .filter(([key]) => !/^id$/i.test(key))
      .map(([key, entryValue]) => `${humanize(key)}: ${formatValue(entryValue)}`)
      .filter((label) => !label.endsWith(': '));
  }

  const text = formatValue(value);
  return text ? [text] : [];
}

function isUnresolvedAttribute(record: Raw): boolean {
  return (
    record.definition_id != null ||
    record.definitionId != null ||
    record.attribute_definition_id != null ||
    record.attributeDefinitionId != null
  );
}

function itemList(value: unknown): string[] {
  if (Array.isArray(value)) return value.flatMap((item) => itemList(item));
  if (value && typeof value === 'object') {
    const record = value as Raw;
    const label = firstString(
      record.name,
      record.label,
      record.title,
      record.description,
      record.equipment_name,
      record.equipmentName,
    );
    return label ? [label] : [];
  }
  const text = formatValue(value);
  return text ? [text] : [];
}

/** Anything, as display text. `false` and null are nothing; `true` is `'true'`. */
export function formatValue(value: unknown): string {
  if (value == null || value === false) return '';
  if (value === true) return 'true';
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '';
  if (typeof value === 'string') return stripHtml(value);
  if (Array.isArray(value)) return value.map(formatValue).filter(Boolean).join(', ');
  if (typeof value === 'object') {
    const record = value as Raw;
    return firstString(record.name, record.label, record.title, record.description, record.value);
  }
  return '';
}

export function firstString(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return stripHtml(value);
    if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  }
  return '';
}

/** The site's own description, clamped. Tags stripped — see `SiteList`'s note. */
export function descriptionText(value: unknown): string {
  const text = compactText(formatValue(value));
  if (!text) return '';
  return text.length > MAX_DESCRIPTION_CHARS
    ? `${text.slice(0, MAX_DESCRIPTION_CHARS - 3).trim()}...`
    : text;
}

/**
 * The first plausible image in a payload.
 *
 * A recursive search rather than a known field path, because no two providers agree
 * on where an image lives. Two rules keep it honest: a key containing "map" is
 * skipped (a campground map is not a photo of the site), and a URL qualifies only if
 * its key or its extension says image — otherwise the first `https://` string in the
 * payload becomes the hero, and that is usually a booking link.
 */
export function findImageUrl(site: Partial<Campsite>): string {
  const urls: string[] = [];
  collectImageUrls(site.source_payload, urls);
  collectImageUrls(site as unknown, urls);
  return urls[0] || '';
}

function collectImageUrls(
  value: unknown,
  urls: string[],
  key = '',
  seen = new WeakSet<object>(),
  depth = 0,
): void {
  if (urls.length > 0 || value == null || depth > MAX_IMAGE_SEARCH_DEPTH) return;
  if (typeof value === 'string') {
    if (isPlausibleImageUrl(value, key)) urls.push(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) collectImageUrls(item, urls, key, seen, depth + 1);
    return;
  }
  if (typeof value !== 'object') return;
  // Provider payloads are JSON, but the site row itself is not — guard the cycle.
  if (seen.has(value as object)) return;
  seen.add(value as object);
  for (const [childKey, childValue] of Object.entries(value as Raw)) {
    collectImageUrls(childValue, urls, childKey, seen, depth + 1);
    if (urls.length > 0) return;
  }
}

function isPlausibleImageUrl(value: string, key: string): boolean {
  if (!/^https?:\/\//i.test(value)) return false;
  const normalizedKey = String(key).toLowerCase();
  if (normalizedKey.includes('map')) return false;
  return (
    /(image|img|photo|media|thumbnail|thumb)/i.test(normalizedKey) ||
    /\.(avif|gif|jpe?g|png|webp)(\?|#|$)/i.test(value)
  );
}

function stripHtml(value: unknown): string {
  return String(value)
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function compactText(value: unknown): string {
  return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
}

function truncateText(value: string, maxLength: number): string {
  if (!value || value.length <= maxLength) return value;
  return `${value.slice(0, maxLength - 3).trim()}...`;
}

function humanize(key: string): string {
  return String(key)
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function numberValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}

function unique(values: readonly string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const value of values) {
    const key = value.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(value);
  }
  return out;
}
