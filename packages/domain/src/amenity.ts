import { memberList } from './members.js';

export const AmenityKey = {
  CAMP_STORE: 'camp_store',
  DUMP_STATION: 'dump_station',
  ELECTRIC_HOOKUPS: 'electric_hookups',
  FIRES_ALLOWED: 'fires_allowed',
  PETS_ALLOWED: 'pets_allowed',
  SEWER_HOOKUPS: 'sewer_hookups',
  SHOWERS: 'showers',
  TOILETS: 'toilets',
  TRASH: 'trash',
  WATER: 'water',
  WATER_HOOKUPS: 'water_hookups',
  WIFI: 'wifi',
  OTHER: 'other',
} as const;

export type AmenityKey = (typeof AmenityKey)[keyof typeof AmenityKey];

export const amenityKeys: readonly AmenityKey[] = memberList(AmenityKey);

const AMENITY_LABELS: Readonly<Record<AmenityKey, string>> = {
  camp_store: 'Camp store',
  dump_station: 'Dump station',
  electric_hookups: 'Electric hookups',
  fires_allowed: 'Fires allowed',
  pets_allowed: 'Pets allowed',
  sewer_hookups: 'Sewer hookups',
  showers: 'Showers',
  toilets: 'Toilets',
  trash: 'Trash',
  water: 'Water',
  water_hookups: 'Water hookups',
  wifi: 'Wi-Fi',
  other: 'Other',
};

const AMENITY_NEGATIVE_LABELS: Readonly<Partial<Record<AmenityKey, string>>> = {
  electric_hookups: 'No electric hookups',
  sewer_hookups: 'No sewer hookups',
  showers: 'No showers',
  toilets: 'No toilets',
  water: 'No water',
  water_hookups: 'No water hookups',
};

export function amenityLabel(key: AmenityKey): string {
  return AMENITY_LABELS[key];
}

export function amenityNegativeLabel(key: AmenityKey): string | undefined {
  return AMENITY_NEGATIVE_LABELS[key];
}
