// `API_ENDPOINTS` is generated from `model/api/ApiContract.kt`; this test is what
// makes it load-bearing. Every client in this directory still hardcodes its own
// URL, so without a check here a backend path rename passes the boot guard,
// `checkApiTypes` and `tsc`, and 404s in production. Read as source text rather
// than imported, because the URLs are module-private constants — and the clients
// compose most of them through those constants, so a `${NAME}` reference is
// resolved against the same file's `const NAME = '…'` before matching.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';
import { API_ENDPOINTS } from './generated/api-types';

const API_DIR = join(process.cwd(), 'src/api');
const SOURCE_SUFFIX = '.ts';
const TEST_SUFFIX = '.test.ts';

const CONTRACTED_PREFIXES = ['/api/', '/auth/password/'];
const QUERY_SEPARATOR = '?';
const SEGMENT_SEPARATOR = '/';

/** Every string and template literal in a source. A template keeps its `${…}` for now. */
const LITERAL = /'([^'\n]*)'|"([^"\n]*)"|`([^`]*)`/g;

/** A `const NAME = '…'`, module-level or local: the indirection the URLs are built through. */
const CONST_LITERAL = /\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*(?:'([^'\n]*)'|`([^`]*)`)/g;

/** A `${NAME}` a same-file constant may resolve. A constant's own value can hold more. */
const REFERENCE = /\$\{\s*([A-Za-z_$][\w$]*)\s*\}/g;
const RESOLUTION_PASSES = 4;

/** Any interpolation left over: a value this test cannot read statically. */
const INTERPOLATION = /\$\{[^}]*\}/g;
const WILDCARD = '{param}';

/** A `{param}` here or an `{id}` in the contract: either side matches any one segment. */
const ONE_SEGMENT = /^\{.+\}$/;

/**
 * Every contract row these clients reach. A row that stops being reached is a
 * client that stopped working, and a row renamed in the backend disappears from
 * here, so the set is pinned rather than merely non-empty. Not all 46 rows
 * appear: the admin surface, the bulk endpoint and the Slack webhook have no
 * client in this directory.
 */
const CALLED_TODAY = [
  '/api/availability/changes',
  '/api/availability/changes/summary',
  '/api/availability/pollers',
  '/api/availability/pollers/summary',
  '/api/availability/pollers/{id}/force',
  '/api/availability/pollers/{id}/runs',
  '/api/availability/runs',
  '/api/booking/add-to-cart',
  '/api/build-info',
  '/api/geocode',
  '/api/me',
  '/api/pois',
  '/api/pois/on-route',
  '/api/pois/search',
  '/api/pois/{id}',
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
  '/api/watches/{id}',
  '/api/watches/{id}/delete',
  '/api/watches/{id}/modify',
  '/auth/password/begin',
  '/auth/password/complete',
];

const CONTRACT_PATHS: string[] = [...new Set(API_ENDPOINTS.map((row) => row.path as string))];

function clientSources(): string[] {
  return readdirSync(API_DIR)
    .filter((name) => name.endsWith(SOURCE_SUFFIX) && !name.endsWith(TEST_SUFFIX))
    .sort();
}

function constantsIn(source: string): Map<string, string> {
  const constants = new Map<string, string>();
  for (const match of source.matchAll(CONST_LITERAL)) {
    constants.set(match[1], match[2] ?? match[3]);
  }
  return constants;
}

/** `${BASE}/x` becomes `/api/watches/x`. Repeated, because a constant's value can reference another. */
function resolveReferences(literal: string, constants: Map<string, string>): string {
  let resolved = literal;
  for (let pass = 0; pass < RESOLUTION_PASSES; pass += 1) {
    const next = resolved.replace(REFERENCE, (reference, name) => constants.get(name) ?? reference);
    if (next === resolved) break;
    resolved = next;
  }
  return resolved;
}

/**
 * The query string is cut off, and an unreadable interpolation becomes a wildcard
 * segment. Glued to text — the suffix in `…/availability${suffix}` — it is
 * dropped instead, since what it appends is not part of the path.
 */
function normalize(resolved: string): string {
  return resolved
    .split(QUERY_SEPARATOR)[0]
    .replace(INTERPOLATION, WILDCARD)
    .split(SEGMENT_SEPARATOR)
    .map((segment) => (segment === WILDCARD ? segment : segment.replaceAll(WILDCARD, '')))
    .join(SEGMENT_SEPARATOR);
}

function pathsIn(source: string): string[] {
  const constants = constantsIn(source);
  return [...source.matchAll(LITERAL)]
    .map((match) => normalize(resolveReferences(match[1] ?? match[2] ?? match[3], constants)))
    .filter((path) => CONTRACTED_PREFIXES.some((prefix) => path.startsWith(prefix)));
}

/**
 * Every row a called path could be. All of them, not the first: a path whose last
 * segment arrives as an argument (`watchUrl(id, MODIFY_ACTION)`) reads here as
 * `/api/watches/{param}/{param}`, and both rows it could be are reached.
 */
function matchingPaths(called: string): string[] {
  const wanted = called.split(SEGMENT_SEPARATOR);
  return CONTRACT_PATHS.filter((declared) => {
    const segments = declared.split(SEGMENT_SEPARATOR);
    if (segments.length !== wanted.length) return false;
    return segments.every(
      (segment, i) => ONE_SEGMENT.test(segment) || ONE_SEGMENT.test(wanted[i]) || segment === wanted[i],
    );
  });
}

const sourceOf = (name: string) => readFileSync(join(API_DIR, name), 'utf8');

test.each(clientSources())('%s calls only paths the contract declares', (name) => {
  for (const path of pathsIn(sourceOf(name))) {
    expect(
      matchingPaths(path),
      `${name} calls ${path}, which no API_ENDPOINTS row declares`,
    ).not.toEqual([]);
  }
});

test('the contract rows these clients reach are exactly the pinned set', () => {
  const reached = clientSources().flatMap((name) => pathsIn(sourceOf(name))).flatMap(matchingPaths);
  expect([...new Set(reached)].sort()).toEqual(CALLED_TODAY);
});
