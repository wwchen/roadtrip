import { amenityLabel as domainAmenityLabel, campsiteKindLabel } from '@roadtrip/domain';
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
  { value: 'tent', label: campsiteKindLabel('tent') },
  { value: 'rv', label: campsiteKindLabel('rv') },
  { value: 'cabin', label: campsiteKindLabel('cabin') },
];

export const FILTER_AMENITIES: readonly AmenityOption[] = [
  { key: 'toilets', label: domainAmenityLabel('toilets') },
  { key: 'showers', label: domainAmenityLabel('showers') },
  { key: 'water', label: domainAmenityLabel('water') },
  { key: 'pets_allowed', label: domainAmenityLabel('pets_allowed') },
];

export function kindLabel(kind: CampsiteKind): string {
  return FILTER_SITE_TYPES.find((option) => option.value === kind)?.label ?? kind;
}

export function amenityLabel(key: AmenityKey): string {
  return FILTER_AMENITIES.find((option) => option.key === key)?.label ?? key;
}
