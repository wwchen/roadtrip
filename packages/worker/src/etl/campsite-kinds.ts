import { CampsiteKind } from '@roadtrip/domain';
import { kotlinTrim } from './kotlin-text.js';

export interface VendorKind {
  kind: CampsiteKind;
  electric: boolean | null;
}

const RECGOV_ELECTRIC_TOKEN = 'ELECTRIC';
const RECGOV_NONELECTRIC_TOKEN = 'NONELECTRIC';
const RECGOV_TOKEN_SEPARATOR = ' ';
const RC_HOOKUP_SUBSTRING = 'hook up';
const RC_ELECTRIC_SUBSTRING = '(e';

type Rule = readonly [phrase: string, kind: CampsiteKind];

const RECGOV_LEADING_PHRASES: ReadonlyArray<readonly [readonly string[], CampsiteKind]> = (
  [
    ['GROUP', CampsiteKind.GROUP],
    ['TENT ONLY', CampsiteKind.TENT],
    ['RV', CampsiteKind.RV],
    ['CABIN', CampsiteKind.CABIN],
    ['YURT', CampsiteKind.CABIN],
    ['LOOKOUT', CampsiteKind.CABIN],
    ['OVERNIGHT SHELTER', CampsiteKind.CABIN],
    ['SHELTER', CampsiteKind.CABIN],
    ['WALK TO', CampsiteKind.WALK_IN],
    ['HIKE TO', CampsiteKind.WALK_IN],
    ['BOAT IN', CampsiteKind.BOAT_IN],
    ['MOORING', CampsiteKind.BOAT_IN],
    ['ANCHORAGE', CampsiteKind.BOAT_IN],
    ['EQUESTRIAN', CampsiteKind.EQUESTRIAN],
    ['STANDARD', CampsiteKind.STANDARD],
    ['ZONE', CampsiteKind.BACKCOUNTRY],
    ['PICNIC', CampsiteKind.DAY_USE],
    ['PARKING', CampsiteKind.DAY_USE],
    ['DAY USE', CampsiteKind.DAY_USE],
  ] satisfies Rule[]
).map(([phrase, kind]) => [phrase.split(RECGOV_TOKEN_SEPARATOR), kind] as const);

const CAMPFLARE_KINDS: ReadonlyMap<string, CampsiteKind> = new Map([
  ['standard', CampsiteKind.STANDARD],
  ['tent-only', CampsiteKind.TENT],
  ['rv', CampsiteKind.RV],
  ['cabin', CampsiteKind.CABIN],
  ['group', CampsiteKind.GROUP],
  ['walk-to', CampsiteKind.WALK_IN],
  ['water-access', CampsiteKind.BOAT_IN],
  ['equestrian', CampsiteKind.EQUESTRIAN],
  ['management', CampsiteKind.OTHER],
]);

const ASPIRA_EXACT_NAMES: ReadonlyMap<string, CampsiteKind> = new Map([
  ['Campsite', CampsiteKind.STANDARD],
  ['Campsite/Seasonal', CampsiteKind.STANDARD],
  ['Overflow', CampsiteKind.STANDARD],
  ['Cabin', CampsiteKind.CABIN],
  ['Rustic Cabin', CampsiteKind.CABIN],
  ['Deluxe Cabin', CampsiteKind.CABIN],
  ['Backcountry Cabin', CampsiteKind.CABIN],
  ['Yurt', CampsiteKind.CABIN],
  ['oTENTik', CampsiteKind.CABIN],
  ['Ôasis', CampsiteKind.CABIN],
  ['MicrOcube', CampsiteKind.CABIN],
  ['Teepee', CampsiteKind.CABIN],
  ['Prospector Tent', CampsiteKind.CABIN],
  ['Platform Tent', CampsiteKind.CABIN],
  ['Adirondack', CampsiteKind.CABIN],
  ['Equipped Camping', CampsiteKind.CABIN],
  ['Vacation House', CampsiteKind.CABIN],
  ['Equestrian', CampsiteKind.EQUESTRIAN],
  ['Marina', CampsiteKind.BOAT_IN],
  ['Mooring Buoy', CampsiteKind.BOAT_IN],
  ['Marine Trail', CampsiteKind.BOAT_IN],
  ['Annual Marina', CampsiteKind.BOAT_IN],
  ['Daily Fishing', CampsiteKind.DAY_USE],
  ['Guided Event', CampsiteKind.DAY_USE],
  ['Hiking Trip', CampsiteKind.DAY_USE],
  ['Ferry', CampsiteKind.DAY_USE],
]);

const ASPIRA_PREFIXES: readonly Rule[] = [
  ['Backcountry', CampsiteKind.BACKCOUNTRY],
  ['Wilderness', CampsiteKind.BACKCOUNTRY],
  ['Group', CampsiteKind.GROUP],
  ['Day Use', CampsiteKind.DAY_USE],
  ['Conference', CampsiteKind.DAY_USE],
  ['Retreat', CampsiteKind.DAY_USE],
];

const RESERVE_CALIFORNIA_SUBSTRINGS: ReadonlyArray<readonly [readonly string[], CampsiteKind]> = [
  [['day use', 'dailyuse'], CampsiteKind.DAY_USE],
  [['group'], CampsiteKind.GROUP],
  [['equestrian', 'equestrain'], CampsiteKind.EQUESTRIAN],
  [['cabin', 'cottage', 'yurt'], CampsiteKind.CABIN],
  [['boat in', 'floating camp'], CampsiteKind.BOAT_IN],
  [['hike', 'bike', 'walk-in'], CampsiteKind.WALK_IN],
  [[RC_HOOKUP_SUBSTRING], CampsiteKind.RV],
  [['tent'], CampsiteKind.TENT],
  [['campsite'], CampsiteKind.STANDARD],
];

function recGovElectric(lastToken: string): boolean | null {
  if (lastToken === RECGOV_ELECTRIC_TOKEN) return true;
  if (lastToken === RECGOV_NONELECTRIC_TOKEN) return false;
  return null;
}

export function campsiteKindRecGov(type?: string | null): VendorKind {
  const tokens = (type == null ? '' : kotlinTrim(type).toUpperCase())
    .split(RECGOV_TOKEN_SEPARATOR)
    .filter((token) => token !== '');
  const last = tokens[tokens.length - 1];
  if (last === undefined) return { kind: CampsiteKind.OTHER, electric: null };
  const match = RECGOV_LEADING_PHRASES.find(([phrase]) =>
    phrase.every((token, index) => tokens[index] === token),
  );
  return { kind: match?.[1] ?? CampsiteKind.OTHER, electric: recGovElectric(last) };
}

export function campsiteKindCampflare(kind?: string | null): CampsiteKind {
  return (kind == null ? undefined : CAMPFLARE_KINDS.get(kotlinTrim(kind))) ?? CampsiteKind.OTHER;
}

export function campsiteKindAspira(category?: string | null): CampsiteKind {
  const name = category == null ? '' : kotlinTrim(category);
  if (name === '') return CampsiteKind.OTHER;
  const exact = ASPIRA_EXACT_NAMES.get(name);
  if (exact !== undefined) return exact;
  return ASPIRA_PREFIXES.find(([prefix]) => name.startsWith(prefix))?.[1] ?? CampsiteKind.OTHER;
}

export function campsiteKindReserveCalifornia(unitType?: string | null): VendorKind {
  const name = unitType == null ? '' : kotlinTrim(unitType).toLowerCase();
  if (name === '') return { kind: CampsiteKind.OTHER, electric: null };
  const match = RESERVE_CALIFORNIA_SUBSTRINGS.find(([substrings]) =>
    substrings.some((substring) => name.includes(substring)),
  );
  const electric = name.includes(RC_HOOKUP_SUBSTRING) && name.includes(RC_ELECTRIC_SUBSTRING);
  return { kind: match?.[1] ?? CampsiteKind.OTHER, electric: electric ? true : null };
}
