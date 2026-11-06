import { memberList } from './members.js';

export const CampsiteKind = {
  STANDARD: 'standard',
  TENT: 'tent',
  RV: 'rv',
  CABIN: 'cabin',
  GROUP: 'group',
  WALK_IN: 'walk_in',
  BOAT_IN: 'boat_in',
  EQUESTRIAN: 'equestrian',
  BACKCOUNTRY: 'backcountry',
  DAY_USE: 'day_use',
  OTHER: 'other',
} as const;

export type CampsiteKind = (typeof CampsiteKind)[keyof typeof CampsiteKind];

export const campsiteKinds: readonly CampsiteKind[] = memberList(CampsiteKind);

const CAMPSITE_KIND_LABELS: Readonly<Record<CampsiteKind, string>> = {
  standard: 'Standard',
  tent: 'Tent',
  rv: 'RV',
  cabin: 'Cabin',
  group: 'Group',
  walk_in: 'Walk-in',
  boat_in: 'Boat-in',
  equestrian: 'Equestrian',
  backcountry: 'Backcountry',
  day_use: 'Day use',
  other: 'Other',
};

export function campsiteKindLabel(kind: CampsiteKind): string {
  return CAMPSITE_KIND_LABELS[kind];
}

export interface CampsiteAttribute {
  name: string;
  value?: string;
}
