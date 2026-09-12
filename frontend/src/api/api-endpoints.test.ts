// `API_ENDPOINTS` is generated from `model/api/ApiContract.kt`; this test is what
// makes it load-bearing. Every client in this directory still hardcodes its own
// URL, so without a check here a backend path rename passes the boot guard,
// `checkApiTypes` and `tsc`, and 404s in production. Read as source text rather
// than imported, because the URLs are module-private constants.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';
import { API_ENDPOINTS } from './generated/api-types';

const API_DIR = join(process.cwd(), 'src/api');
const SOURCE_SUFFIX = '.ts';
const TEST_SUFFIX = '.test.ts';

/** A string or template literal whose content starts at one of the two contracted prefixes. */
const PATH_LITERAL = /['"`](\/(?:api|auth\/password)\/[^'"`]*)['"`]/g;

/** One `${…}` in a template literal: a segment this test cannot read statically. */
const INTERPOLATION = /\$\{[^}]*\}/g;
const WILDCARD = '{param}';

/** A contract segment like `{id}`, which matches any single mounted segment. */
const CONTRACT_PARAM = /^\{.+\}$/;

/**
 * The contract paths the clients in this directory call today. A path that stops
 * being called is a client that stopped working, so the set is pinned rather
 * than merely non-empty. Not all 46 rows appear: the admin surface, the bulk
 * endpoint and the Slack webhook have no client here.
 */
const CALLED_TODAY = [
  '/api/availability/changes',
  '/api/availability/changes/summary',
  '/api/availability/pollers',
  '/api/availability/pollers/summary',
  '/api/availability/runs',
  '/api/booking/add-to-cart',
  '/api/build-info',
  '/api/geocode',
  '/api/me',
  '/api/pois',
  '/api/pois/on-route',
  '/api/pois/search',
  '/api/pois/{id}/campsites',
  '/api/pois/{id}/campsites/availability',
  '/api/route',
  '/api/settings',
  '/api/settings/notifications',
  '/api/settings/notifications/email/test',
  '/api/settings/notifications/slack',
  '/api/settings/notifications/slack/test',
  '/api/settings/profile',
  '/api/settings/recgov',
  '/api/settings/recgov/login',
  '/api/settings/recgov/login/mfa',
  '/api/settings/recgov/status',
  '/api/settings/recgov/verify',
  '/api/watches',
  '/auth/password/begin',
  '/auth/password/complete',
];

function clientSources(): string[] {
  return readdirSync(API_DIR)
    .filter((name) => name.endsWith(SOURCE_SUFFIX) && !name.endsWith(TEST_SUFFIX))
    .sort();
}

/**
 * An interpolation that is a whole segment becomes a wildcard; one glued to text
 * — the query-string suffix in `…/availability${suffix}` — is dropped, since
 * what it appends is not part of the path.
 */
function normalize(literal: string): string {
  return literal
    .replace(INTERPOLATION, WILDCARD)
    .split('/')
    .map((segment) => (segment === WILDCARD ? segment : segment.replaceAll(WILDCARD, '')))
    .join('/');
}

function pathsIn(source: string): string[] {
  return [...source.matchAll(PATH_LITERAL)].map((match) => normalize(match[1]));
}

function resolve(called: string): string | undefined {
  const wanted = called.split('/');
  return API_ENDPOINTS.map((row) => row.path as string).find((path) => {
    const declared = path.split('/');
    if (declared.length !== wanted.length) return false;
    return declared.every((segment, i) =>
      CONTRACT_PARAM.test(segment) ? wanted[i].length > 0 : segment === wanted[i],
    );
  });
}

test.each(clientSources())('%s calls only paths the contract declares', (name) => {
  const called = pathsIn(readFileSync(join(API_DIR, name), 'utf8'));
  for (const path of called) {
    expect(resolve(path), `${name} calls ${path}, which no API_ENDPOINTS row declares`).toBeDefined();
  }
});

test('the contract paths these clients call are exactly the pinned set', () => {
  const resolved = clientSources()
    .flatMap((name) => pathsIn(readFileSync(join(API_DIR, name), 'utf8')))
    .map(resolve)
    .filter((path): path is string => path !== undefined);
  expect([...new Set(resolved)].sort()).toEqual(CALLED_TODAY);
});
