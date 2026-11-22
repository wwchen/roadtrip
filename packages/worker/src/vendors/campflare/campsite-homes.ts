import type { Envelope } from '../../etl/envelope.js';
import { isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { stringField } from './support.js';

const SITE_ID_FIELD = 'id';
const SITE_CAMPGROUND_FIELD = 'campground_id';
const SITE_LOOP_FIELD = 'loop_name';
const CAMPGROUND_ID_FIELD = 'id';
const CAMPGROUND_NAME_FIELD = 'name';
const NAME_TOKEN_SEPARATOR = /[^a-z0-9]+/;

function* rows(envelopes: readonly Envelope[]): Generator<JsonObject> {
  for (const envelope of envelopes) {
    if (!Array.isArray(envelope.payload)) continue;
    for (const row of envelope.payload) if (isJsonObject(row)) yield row;
  }
}

function nameTokens(name: string | null | undefined): Set<string> {
  if (name === null || name === undefined) return new Set();
  return new Set(name.toLowerCase().split(NAME_TOKEN_SEPARATOR).filter((token) => token !== ''));
}

function campgroundNames(campgrounds: readonly Envelope[], wanted: ReadonlySet<string>): Map<string, string | null> {
  const names = new Map<string, string | null>();
  for (const row of rows(campgrounds)) {
    const id = stringField(row, CAMPGROUND_ID_FIELD);
    if (id !== null && wanted.has(id) && !names.has(id)) names.set(id, stringField(row, CAMPGROUND_NAME_FIELD));
  }
  return names;
}

function home(copies: readonly JsonObject[], names: ReadonlyMap<string, string | null>): string {
  const candidates = [
    ...new Set(copies.map((copy) => stringField(copy, SITE_CAMPGROUND_FIELD)).filter((id) => id !== null)),
  ];
  const tokens = new Map(candidates.map((candidate) => [candidate, nameTokens(names.get(candidate))]));
  const tokensOf = (candidate: string): Set<string> => tokens.get(candidate) ?? new Set();
  const distinctive = new Map(
    candidates.map((candidate) => {
      const others = new Set(candidates.filter((other) => other !== candidate).flatMap((other) => [...tokensOf(other)]));
      return [candidate, new Set([...tokensOf(candidate)].filter((token) => !others.has(token)))];
    }),
  );
  const distinctiveOf = (candidate: string): Set<string> => distinctive.get(candidate) ?? new Set();
  const loop = nameTokens(stringField(copies[0], SITE_LOOP_FIELD));
  const hits = new Map(
    candidates.map((candidate) => [candidate, [...distinctiveOf(candidate)].filter((token) => loop.has(token)).length]),
  );
  const best = Math.max(...hits.values());
  const leaders = candidates.filter((candidate) => hits.get(candidate) === best);
  const [leader] = leaders;
  if (best > 0 && leaders.length === 1 && leader !== undefined) return leader;
  const parkOnly = candidates.filter((candidate) => distinctiveOf(candidate).size === 0);
  const [onlyPark] = parkOnly;
  if (parkOnly.length === 1 && onlyPark !== undefined) return onlyPark;
  const [first] = candidates;
  if (first === undefined) throw new Error('a contested campsite has no campground candidates');
  return first;
}

export class CampflareCampsiteHomes {
  private constructor(private readonly homeBySiteId: ReadonlyMap<string, string>) {}

  static resolve(sites: readonly Envelope[], campgrounds: readonly Envelope[]): CampflareCampsiteHomes {
    const firstCampground = new Map<string, string>();
    const contested = new Set<string>();
    for (const row of rows(sites)) {
      const siteId = stringField(row, SITE_ID_FIELD);
      const campgroundId = stringField(row, SITE_CAMPGROUND_FIELD);
      if (siteId === null || campgroundId === null) continue;
      const first = firstCampground.get(siteId);
      if (first === undefined) firstCampground.set(siteId, campgroundId);
      else if (first !== campgroundId) contested.add(siteId);
    }
    if (contested.size === 0) return new CampflareCampsiteHomes(new Map());

    const listings = new Map<string, JsonObject[]>();
    for (const row of rows(sites)) {
      const siteId = stringField(row, SITE_ID_FIELD);
      if (siteId === null || !contested.has(siteId) || stringField(row, SITE_CAMPGROUND_FIELD) === null) continue;
      const copies = listings.get(siteId);
      if (copies === undefined) listings.set(siteId, [row]);
      else copies.push(row);
    }
    const wanted = new Set<string>();
    for (const copies of listings.values()) {
      for (const copy of copies) {
        const campgroundId = stringField(copy, SITE_CAMPGROUND_FIELD);
        if (campgroundId !== null) wanted.add(campgroundId);
      }
    }
    const names = campgroundNames(campgrounds, wanted);
    return new CampflareCampsiteHomes(new Map([...listings].map(([siteId, copies]) => [siteId, home(copies, names)])));
  }

  keeps(row: JsonObject): boolean {
    const siteId = stringField(row, SITE_ID_FIELD);
    if (siteId === null) return true;
    const campgroundHome = this.homeBySiteId.get(siteId);
    if (campgroundHome === undefined) return true;
    return stringField(row, SITE_CAMPGROUND_FIELD) === campgroundHome;
  }
}
