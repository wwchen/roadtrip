// Facts a camper cares about, read from the typed catalog row.
import type { Campsite } from '@/api/campsite-api';

/** Feature chips past this are noise in a row that is already dense. */
const MAX_FEATURES = 12;
const MAX_DESCRIPTION_CHARS = 260;
const MAX_EQUIPMENT_ITEMS = 4;

export interface SiteFact {
  label: string;
  value: string;
}

/**
 * The labelled facts, in reading order.
 *
 * Order is not alphabetical: where the site is, what kind it is, and how many
 * people fit are what a camper checks first; the provider and its id are last
 * because they are for us, not for them.
 */
export function detailFacts(site: Partial<Campsite>): SiteFact[] {
  const facts: SiteFact[] = [];
  const add = (label: string, value: string | null | undefined): void => {
    const text = compactText(value);
    if (text) facts.push({ label, value: text });
  };

  add('Loop', site.loop_name);
  add('Type', site.kind_listed ?? site.kind);
  add('Capacity', capacityLabel(site));
  add('Equipment', (site.equipment ?? []).slice(0, MAX_EQUIPMENT_ITEMS).join(', '));
  add('Provider', site.data_provider);
  add('Provider ID', site.data_provider_ref);
  return facts;
}

/** "4-6 people" / "Up to 6 people" / "2+ people" — three different claims. */
export function capacityLabel(site: Partial<Campsite>): string {
  const min = site.min_people ?? null;
  const max = site.max_people ?? null;
  if (min != null && max != null && min !== max) return `${min}-${max} people`;
  if (max != null) return `Up to ${max} people`;
  if (min != null) return `${min}+ people`;
  return '';
}

/**
 * Boolean columns, then measurements, then the provider's named attributes.
 *
 * Only `true` is a chip. A `false` firepit is not worth a chip saying so, and
 * — more importantly — neither is a missing one, which is what `false` usually
 * means in these rows: absence of data rather than absence of a firepit.
 * Deduplicated case-insensitively because the same fact routinely arrives twice,
 * once as a promoted column and once as a provider attribute.
 */
export function featureLabels(site: Partial<Campsite>): string[] {
  const labels: string[] = [];
  const flag = (label: string, value: boolean | null | undefined): void => {
    if (value === true) labels.push(label);
  };
  const measure = (label: string, value: string): void => {
    if (value) labels.push(`${label}: ${value}`);
  };

  flag('Firepit', site.firepit);
  flag('Picnic table', site.picnic_table);
  flag('ADA accessible', site.ada_accessible);
  flag('Water hookups', site.water_hookups);
  flag('Electric hookups', site.electric_hookups);
  flag('Sewer hookups', site.sewer_hookups);
  flag('Pull-through', site.pull_through);
  measure('Max cars', site.max_cars != null ? String(site.max_cars) : '');
  measure('Driveway length', lengthLabel(site.driveway_length));
  measure('Max RV length', lengthLabel(site.max_rv_length));
  measure('Max trailer length', lengthLabel(site.max_trailer_length));
  for (const attribute of site.attributes ?? []) {
    const value = compactText(attribute.value);
    labels.push(value ? `${attribute.name}: ${value}` : attribute.name);
  }
  return unique(labels).slice(0, MAX_FEATURES);
}

export function descriptionText(value: string | null | undefined): string {
  const text = compactText(value);
  if (!text) return '';
  return text.length > MAX_DESCRIPTION_CHARS
    ? `${text.slice(0, MAX_DESCRIPTION_CHARS - 3).trim()}...`
    : text;
}

function lengthLabel(feet: number | null | undefined): string {
  return feet == null ? '' : `${feet} ft`;
}

function compactText(value: string | null | undefined): string {
  return value ? value.replace(/\s+/g, ' ').trim() : '';
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
