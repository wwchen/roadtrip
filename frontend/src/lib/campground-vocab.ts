// The kinds and amenities the filter exposes, with the backend's own labels.
// Mirrors `CampsiteKind.label` and `AmenityKey.label` in the Kotlin enums; a card's
// own amenity chips still render the `label` the detail response carries.
import type { AmenityKey, CampsiteKind } from '@/api/generated/api-types';

export interface KindOption {
  value: CampsiteKind;
  label: string;
}

export interface AmenityOption {
  key: AmenityKey;
  label: string;
}

export const FILTER_SITE_TYPES: readonly KindOption[] = [
  { value: 'tent', label: 'Tent' },
  { value: 'rv', label: 'RV' },
  { value: 'cabin', label: 'Cabin' },
];

export const FILTER_AMENITIES: readonly AmenityOption[] = [
  { key: 'toilets', label: 'Toilets' },
  { key: 'showers', label: 'Showers' },
  { key: 'water', label: 'Water' },
  { key: 'pets_allowed', label: 'Pets allowed' },
];

export function kindLabel(kind: CampsiteKind): string {
  return FILTER_SITE_TYPES.find((option) => option.value === kind)?.label ?? kind;
}

export function amenityLabel(key: AmenityKey): string {
  return FILTER_AMENITIES.find((option) => option.key === key)?.label ?? key;
}
