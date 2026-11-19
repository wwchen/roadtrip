import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CAMPGROUND_POI_TYPE, POI_TYPES } from '@roadtrip/db';
import { afterEach, expect, test, vi } from 'vitest';
import { ConfigError, config, configProblems } from './config';

const DATABASE_URL = 'postgres://reader@db.test/roadtrip';
const DEFAULT_PROVIDERS = ['campflare', 'bcparks-strapi', 'aspira', 'reserveamerica', 'reservecalifornia'];
const DEFAULT_LIMIT = 200;
const MAX_LIMIT = 1000;
const CUSTOM_LIMIT = 25;
const KOTLIN_APPLICATION_YAML = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', '..', '..', '..', 'backend', 'src', 'main', 'resources', 'application.yaml',
);
const KOTLIN_AVAILABILITY_LIST_KEY = 'enabled-availability-providers:';
const KOTLIN_DATA_LIST_KEY = 'enabled-data-providers:';
const NON_CAMPGROUND_POI_TYPES: readonly string[] = POI_TYPES.filter((type) => type !== CAMPGROUND_POI_TYPE);

function kotlinYamlList(key: string): string[] {
  const lines = readFileSync(KOTLIN_APPLICATION_YAML, 'utf8').split('\n');
  const start = lines.findIndex((line) => line.trim() === key);
  if (start === -1) throw new Error(`${key} is missing from application.yaml`);
  const entries: string[] = [];
  for (const line of lines.slice(start + 1)) {
    const trimmed = line.trim();
    if (trimmed.startsWith('#')) continue;
    if (!trimmed.startsWith('- ')) break;
    entries.push(trimmed.slice(2).trim());
  }
  return entries;
}

afterEach(() => {
  vi.unstubAllEnvs();
});

test('databaseUrl throws a named error when DATABASE_URL is unset', () => {
  vi.stubEnv('DATABASE_URL', undefined);
  expect(() => config.databaseUrl).toThrow(ConfigError);
  expect(() => config.databaseUrl).toThrow(/DATABASE_URL is not set/);
});

test('databaseUrl reads DATABASE_URL', () => {
  vi.stubEnv('DATABASE_URL', DATABASE_URL);
  expect(config.databaseUrl).toBe(DATABASE_URL);
});

test('the optional settings fall back to their defaults', () => {
  vi.stubEnv('DATABASE_PREPARE', undefined);
  vi.stubEnv('ENABLED_DATA_PROVIDERS', undefined);
  vi.stubEnv('SEARCH_RESULT_LIMIT', undefined);
  vi.stubEnv('DATABASE_REQUIRE_READ_ONLY', undefined);
  expect(config.databasePrepare).toBe(true);
  expect(config.databaseRequireReadOnly).toBe(true);
  expect(config.enabledDataProviders).toEqual(DEFAULT_PROVIDERS);
  expect(config.searchResultLimit).toBe(DEFAULT_LIMIT);
});

test('the optional settings read their variables', () => {
  vi.stubEnv('DATABASE_PREPARE', 'false');
  vi.stubEnv('ENABLED_DATA_PROVIDERS', ' campflare , aspira ,');
  vi.stubEnv('SEARCH_RESULT_LIMIT', String(CUSTOM_LIMIT));
  vi.stubEnv('DATABASE_REQUIRE_READ_ONLY', 'false');
  expect(config.databasePrepare).toBe(false);
  expect(config.databaseRequireReadOnly).toBe(false);
  expect(config.enabledDataProviders).toEqual(['campflare', 'aspira']);
  expect(config.searchResultLimit).toBe(CUSTOM_LIMIT);
});

test('an unparseable optional setting throws a named error', () => {
  vi.stubEnv('DATABASE_PREPARE', 'maybe');
  expect(() => config.databasePrepare).toThrow(ConfigError);
  vi.stubEnv('DATABASE_REQUIRE_READ_ONLY', 'no');
  expect(() => config.databaseRequireReadOnly).toThrow(ConfigError);
  vi.stubEnv('SEARCH_RESULT_LIMIT', '0');
  expect(() => config.searchResultLimit).toThrow(ConfigError);
});

test('the search result limit cannot be configured above its ceiling', () => {
  vi.stubEnv('SEARCH_RESULT_LIMIT', String(MAX_LIMIT));
  expect(config.searchResultLimit).toBe(MAX_LIMIT);
  vi.stubEnv('SEARCH_RESULT_LIMIT', String(MAX_LIMIT + 1));
  expect(() => config.searchResultLimit).toThrow(ConfigError);
});

test('the health database token is unavailable unless it holds a non-blank value', () => {
  vi.stubEnv('HEALTH_DATABASE_TOKEN', undefined);
  expect(config.healthDatabaseToken).toBeUndefined();
  vi.stubEnv('HEALTH_DATABASE_TOKEN', '');
  expect(config.healthDatabaseToken).toBeUndefined();
  vi.stubEnv('HEALTH_DATABASE_TOKEN', '  \t ');
  expect(config.healthDatabaseToken).toBeUndefined();
  vi.stubEnv('HEALTH_DATABASE_TOKEN', 'a-secret');
  expect(config.healthDatabaseToken).toBe('a-secret');
});

test('a set but empty provider list is refused rather than read as none enabled', () => {
  vi.stubEnv('ENABLED_DATA_PROVIDERS', '');
  expect(() => config.enabledDataProviders).toThrow(ConfigError);
  expect(() => config.enabledDataProviders).toThrow(/ENABLED_DATA_PROVIDERS must list at least one/);
  vi.stubEnv('ENABLED_DATA_PROVIDERS', ' , ');
  expect(() => config.enabledDataProviders).toThrow(ConfigError);
});

test('buildInfo reports the configured build', () => {
  vi.stubEnv('BUILD_ENV', 'preview');
  vi.stubEnv('BUILD_SHA', 'abc123');
  vi.stubEnv('BUILD_BRANCH', 'claude/x');
  expect(config.buildInfo).toEqual({ env: 'preview', sha: 'abc123', branch: 'claude/x' });
});

test('buildInfo defaults env to local and the commit to empty', () => {
  vi.stubEnv('BUILD_ENV', '');
  vi.stubEnv('BUILD_SHA', undefined);
  vi.stubEnv('BUILD_BRANCH', undefined);
  expect(config.buildInfo).toEqual({ env: 'local', sha: '', branch: '' });
});

function secretFile(contents: string): string {
  const file = path.join(mkdtempSync(path.join(tmpdir(), 'web-config-')), 'secret');
  writeFileSync(file, contents);
  return file;
}

test("DATABASE_PASSWORD_FILE puts the file's password on DATABASE_URL, URL-encoded", () => {
  vi.stubEnv('DATABASE_URL', 'postgres://roadtrip_web@postgres:5432/roadtrip');
  vi.stubEnv('DATABASE_PASSWORD_FILE', secretFile('p@ss:w/rd\n'));
  expect(config.databaseUrl).toBe('postgres://roadtrip_web:p%40ss%3Aw%2Frd@postgres:5432/roadtrip');
});

test('MAPBOX_TOKEN_FILE supplies the Mapbox token', () => {
  vi.stubEnv('MAPBOX_TOKEN', undefined);
  vi.stubEnv('MAPBOX_TOKEN_FILE', secretFile('pk.test\n'));
  expect(config.mapboxToken).toBe('pk.test');
});

test('a value and its _FILE together are a ConfigError', () => {
  vi.stubEnv('MAPBOX_TOKEN', 'x');
  vi.stubEnv('MAPBOX_TOKEN_FILE', secretFile('pk.test'));
  expect(() => config.mapboxToken).toThrow(ConfigError);
  expect(() => config.mapboxToken).toThrow(/MAPBOX_TOKEN_FILE/);
  vi.stubEnv('DATABASE_URL', 'postgres://u:p@h/d');
  vi.stubEnv('DATABASE_PASSWORD_FILE', secretFile('other'));
  expect(() => config.databaseUrl).toThrow(/DATABASE_PASSWORD_FILE/);
});

test('an unreadable or empty _FILE is a ConfigError naming the variable', () => {
  vi.stubEnv('DATABASE_URL', 'postgres://roadtrip_web@postgres:5432/roadtrip');
  vi.stubEnv('DATABASE_PASSWORD_FILE', path.join(tmpdir(), 'web-config-missing', 'nope'));
  expect(() => config.databaseUrl).toThrow(ConfigError);
  expect(() => config.databaseUrl).toThrow(/DATABASE_PASSWORD_FILE/);
  vi.stubEnv('DATABASE_PASSWORD_FILE', secretFile(''));
  expect(() => config.databaseUrl).toThrow(/DATABASE_PASSWORD_FILE/);
});

test('a password with % survives the decode the Postgres driver applies', () => {
  vi.stubEnv('DATABASE_URL', 'postgres://roadtrip_web@postgres:5432/roadtrip');
  for (const password of ['ab%41cd', 'p%zz', 'a b?c#d']) {
    vi.stubEnv('DATABASE_PASSWORD_FILE', secretFile(password));
    expect(decodeURIComponent(new URL(config.databaseUrl).password)).toBe(password);
  }
});

const EGRESS_URL = 'https://egress.floo.test';
const ACCESS_ID = 'access-id.access';
const ACCESS_SECRET = 'access-secret';
const REFRESH_VARIABLES = {
  AVAILABILITY_EGRESS_URL: EGRESS_URL,
  AVAILABILITY_EGRESS_ACCESS_CLIENT_ID: ACCESS_ID,
  AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET: ACCESS_SECRET,
} as const;
const ALL_REFRESH = Object.keys(REFRESH_VARIABLES) as (keyof typeof REFRESH_VARIABLES)[];
const DEFAULT_TTL_SECONDS = 7200;
const CUSTOM_TTL_SECONDS = 900;

function stubRefresh(only: readonly (keyof typeof REFRESH_VARIABLES)[]) {
  for (const [variable, value] of Object.entries(REFRESH_VARIABLES)) {
    vi.stubEnv(variable, (only as readonly string[]).includes(variable) ? value : undefined);
  }
}

test('egress is null when none of the three is set', () => {
  stubRefresh([]);
  expect(config.availabilityEgress).toBeNull();
});

test('egress is configured, with its Access credentials, when all three are set', () => {
  stubRefresh(ALL_REFRESH);
  const refresh = config.availabilityEgress;
  expect(refresh?.egressUrl.toString()).toBe(`${EGRESS_URL}/`);
  expect(refresh?.egressAccess).toEqual({ clientId: ACCESS_ID, clientSecret: ACCESS_SECRET });
});

test.each([
  [['AVAILABILITY_EGRESS_URL'] as const, /AVAILABILITY_EGRESS_ACCESS_CLIENT_ID, AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET/],
  [['AVAILABILITY_EGRESS_URL', 'AVAILABILITY_EGRESS_ACCESS_CLIENT_ID'] as const, /AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET/],
  [['AVAILABILITY_EGRESS_ACCESS_CLIENT_ID', 'AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET'] as const, /AVAILABILITY_EGRESS_URL must/],
])('a partial egress configuration %j is a ConfigError naming the missing ones', (set, missing) => {
  stubRefresh(set);
  expect(() => config.availabilityEgress).toThrow(ConfigError);
  expect(() => config.availabilityEgress).toThrow(missing);
});

test('neither a bearer token nor a writer URL is part of the egress configuration', () => {
  stubRefresh(ALL_REFRESH);
  vi.stubEnv('AVAILABILITY_EGRESS_TOKEN', undefined);
  vi.stubEnv('DATABASE_WRITER_URL', undefined);
  expect(config.availabilityEgress).not.toBeNull();
});

test('an egress URL that does not parse is a ConfigError', () => {
  stubRefresh(ALL_REFRESH);
  vi.stubEnv('AVAILABILITY_EGRESS_URL', 'not a url');
  expect(() => config.availabilityEgress).toThrow(/AVAILABILITY_EGRESS_URL/);
});

test('the availability TTL defaults to two hours and reads AVAILABILITY_TTL_SECONDS', () => {
  vi.stubEnv('AVAILABILITY_TTL_SECONDS', undefined);
  expect(config.availabilityTtlSeconds).toBe(DEFAULT_TTL_SECONDS);
  vi.stubEnv('AVAILABILITY_TTL_SECONDS', String(CUSTOM_TTL_SECONDS));
  expect(config.availabilityTtlSeconds).toBe(CUSTOM_TTL_SECONDS);
});

test.each(['0', '-5', 'soon'])('a non-positive or unparseable TTL %j is a ConfigError', (raw) => {
  vi.stubEnv('AVAILABILITY_TTL_SECONDS', raw);
  expect(() => config.availabilityTtlSeconds).toThrow(ConfigError);
});

test('the enabled availability providers default to Kotlin read-path list and read their variable', () => {
  vi.stubEnv('ENABLED_AVAILABILITY_PROVIDERS', undefined);
  expect(config.enabledAvailabilityProviders).toEqual(['aspira', 'campflare', 'reserveamerica', 'reservecalifornia']);
  vi.stubEnv('ENABLED_AVAILABILITY_PROVIDERS', 'campflare');
  expect(config.enabledAvailabilityProviders).toEqual(['campflare']);
});

test("the default enabled availability providers are Kotlin's application.yaml list", () => {
  vi.stubEnv('ENABLED_AVAILABILITY_PROVIDERS', undefined);
  expect(config.enabledAvailabilityProviders).toEqual(kotlinYamlList(KOTLIN_AVAILABILITY_LIST_KEY));
});

test("the default enabled data providers are Kotlin's application.yaml campground providers", () => {
  vi.stubEnv('ENABLED_DATA_PROVIDERS', undefined);
  const kotlinCampgroundProviders = kotlinYamlList(KOTLIN_DATA_LIST_KEY).filter((provider) => !NON_CAMPGROUND_POI_TYPES.includes(provider));
  expect(config.enabledDataProviders).toEqual(kotlinCampgroundProviders);
});

const AUTH_URL = 'https://ep-test.neonauth.test/neondb/auth';
const COOKIE_SECRET = 'c'.repeat(32);
const APP_DATABASE_URL = 'postgres://roadtrip_web_writer@db.test/roadtrip';
const SIGN_IN_VARIABLES = {
  NEON_AUTH_BASE_URL: AUTH_URL,
  NEON_AUTH_COOKIE_SECRET: COOKIE_SECRET,
  APP_DATABASE_URL,
} as const;
const ALL_SIGN_IN = Object.keys(SIGN_IN_VARIABLES) as (keyof typeof SIGN_IN_VARIABLES)[];

function stubSignIn(only: readonly (keyof typeof SIGN_IN_VARIABLES)[], adminEmails?: string) {
  for (const [variable, value] of Object.entries(SIGN_IN_VARIABLES)) {
    vi.stubEnv(variable, (only as readonly string[]).includes(variable) ? value : undefined);
  }
  vi.stubEnv('ADMIN_EMAILS', adminEmails);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET_FILE', undefined);
  vi.stubEnv('APP_DATABASE_PASSWORD_FILE', undefined);
}

test('sign-in is off when none of its variables are set', () => {
  stubSignIn([]);
  expect(config.signIn).toBeNull();
});

test('sign-in is on with all three variables', () => {
  stubSignIn(ALL_SIGN_IN);
  const signIn = config.signIn;
  expect(signIn?.neonAuthBaseUrl.href).toBe(AUTH_URL);
  expect(signIn?.cookieSecret).toBe(COOKIE_SECRET);
  expect(signIn?.appDatabaseUrl).toBe(APP_DATABASE_URL);
  expect(signIn?.adminEmails).toEqual(new Set());
});

test.each(ALL_SIGN_IN)('a partial sign-in configuration without %s is an error', (unset) => {
  stubSignIn(ALL_SIGN_IN.filter((variable) => variable !== unset));
  expect(() => config.signIn).toThrow(ConfigError);
  expect(() => config.signIn).toThrow(new RegExp(unset));
});

test('ADMIN_EMAILS is trimmed and lower-cased', () => {
  stubSignIn(ALL_SIGN_IN, ' Will@Example.com ,b@x.io');
  expect(config.signIn?.adminEmails).toEqual(new Set(['will@example.com', 'b@x.io']));
});

test('a cookie secret shorter than 32 characters is an error', () => {
  stubSignIn(ALL_SIGN_IN);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', 'c'.repeat(31));
  expect(() => config.signIn).toThrow(/NEON_AUTH_COOKIE_SECRET/);
});

test('NEON_AUTH_BASE_URL must be absolute', () => {
  stubSignIn(ALL_SIGN_IN);
  vi.stubEnv('NEON_AUTH_BASE_URL', '/neondb/auth');
  expect(() => config.signIn).toThrow(/NEON_AUTH_BASE_URL must be an absolute URL/);
});

test('the cookie secret can come from a mounted file', () => {
  stubSignIn(ALL_SIGN_IN);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET_FILE', secretFile('f'.repeat(32)));
  expect(config.signIn?.cookieSecret).toBe('f'.repeat(32));
});

test('the cookie secret cannot come from both the variable and a file', () => {
  stubSignIn(ALL_SIGN_IN);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET_FILE', secretFile('f'.repeat(32)));
  expect(() => config.signIn).toThrow(/NEON_AUTH_COOKIE_SECRET_FILE and NEON_AUTH_COOKIE_SECRET are both set/);
});

test('the app database password can come from a mounted file', () => {
  stubSignIn(ALL_SIGN_IN);
  vi.stubEnv('APP_DATABASE_PASSWORD_FILE', secretFile('wr%ter:pw'));
  expect(decodeURIComponent(new URL(config.signIn?.appDatabaseUrl ?? '').password)).toBe('wr%ter:pw');
});

test('an app database URL that already carries a password refuses a password file', () => {
  stubSignIn(ALL_SIGN_IN);
  vi.stubEnv('APP_DATABASE_URL', 'postgres://roadtrip_web_writer:inline@db.test/roadtrip');
  vi.stubEnv('APP_DATABASE_PASSWORD_FILE', secretFile('other'));
  expect(() => config.signIn).toThrow(/APP_DATABASE_PASSWORD_FILE is set but APP_DATABASE_URL already carries a password/);
});

const BOOKING_URL = 'https://roadtrip-egress.example.test';
const BOOKING_TOKEN = 'relay-token';
const BOOKING_KEY = Buffer.alloc(32, 7).toString('base64');

function stubBooking(values: Partial<Record<string, string>>): void {
  stubRefresh(ALL_REFRESH);
  for (const variable of ['BOOKING_EGRESS_URL', 'BOOKING_EGRESS_TOKEN', 'BOOKING_FRESHNESS_MAX_AGE_SECONDS', 'MFA_CHALLENGE_TTL_SECONDS']) {
    vi.stubEnv(variable, values[variable]);
  }
}

test('booking is null when neither the relay URL nor its token is set', () => {
  stubBooking({});
  expect(config.booking).toBeNull();
});

test('booking carries the relay, its bearer, the Access pair and the defaults', () => {
  stubBooking({ BOOKING_EGRESS_URL: BOOKING_URL, BOOKING_EGRESS_TOKEN: BOOKING_TOKEN });
  const booking = config.booking;
  expect(booking?.relayUrl.toString()).toBe(`${BOOKING_URL}/`);
  expect(booking?.relayToken).toBe(BOOKING_TOKEN);
  expect(booking?.access).toEqual({ clientId: ACCESS_ID, clientSecret: ACCESS_SECRET });
  expect(booking?.freshnessMaxAgeMs).toBe(300_000);
  expect(booking?.mfaChallengeTtlMs).toBe(300_000);
});

test('booking reads its two windows in seconds', () => {
  stubBooking({
    BOOKING_EGRESS_URL: BOOKING_URL,
    BOOKING_EGRESS_TOKEN: BOOKING_TOKEN,
    BOOKING_FRESHNESS_MAX_AGE_SECONDS: '120',
    MFA_CHALLENGE_TTL_SECONDS: '60',
  });
  expect(config.booking?.freshnessMaxAgeMs).toBe(120_000);
  expect(config.booking?.mfaChallengeTtlMs).toBe(60_000);
});

test('a partial booking configuration is a ConfigError naming the missing one', () => {
  stubBooking({ BOOKING_EGRESS_URL: BOOKING_URL });
  expect(() => config.booking).toThrow(/BOOKING_EGRESS_TOKEN/);
});

test('booking without the egress Access pair is a ConfigError', () => {
  stubBooking({ BOOKING_EGRESS_URL: BOOKING_URL, BOOKING_EGRESS_TOKEN: BOOKING_TOKEN });
  stubRefresh([]);
  expect(() => config.booking).toThrow(/AVAILABILITY_EGRESS_ACCESS_CLIENT_ID/);
});

test('the booking cipher key is null when unset and 32 bytes when set', () => {
  vi.stubEnv('BOOKING_CREDENTIAL_ENCRYPTION_KEY', undefined);
  expect(config.bookingCipherKey).toBeNull();
  vi.stubEnv('BOOKING_CREDENTIAL_ENCRYPTION_KEY', BOOKING_KEY);
  expect(config.bookingCipherKey?.length).toBe(32);
});

test('a booking cipher key that is not 32 bytes is a ConfigError', () => {
  vi.stubEnv('BOOKING_CREDENTIAL_ENCRYPTION_KEY', Buffer.alloc(16).toString('base64'));
  expect(() => config.bookingCipherKey).toThrow(ConfigError);
});

function clearConfigEnv(): void {
  for (const name of Object.keys(process.env)) {
    if (/^(NEON_AUTH_|APP_DATABASE_|AVAILABILITY_EGRESS_|BOOKING_|MAPBOX_TOKEN|DATABASE_|SEARCH_RESULT_LIMIT|CAMPGROUND_SEARCH_|ENABLED_|AVAILABILITY_TTL|MFA_CHALLENGE_|RATE_LIMIT_)/.test(name)) {
      vi.stubEnv(name, undefined);
    }
  }
  vi.stubEnv('DATABASE_URL', DATABASE_URL);
}

test('with every group unset or whole, there are no config problems', () => {
  clearConfigEnv();
  expect(configProblems()).toEqual([]);
});

test('a group set in part is a problem named by its group, with the missing variables and none of the values', () => {
  clearConfigEnv();
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://auth.secret-host.test');

  const problems = configProblems();

  expect(problems.map((problem) => problem.group)).toEqual(['signIn']);
  expect(problems[0]!.message).toMatch(/NEON_AUTH_COOKIE_SECRET/);
  expect(problems[0]!.message).toMatch(/APP_DATABASE_URL/);
  expect(JSON.stringify(problems)).not.toContain('secret-host');
});

test('every group with a problem is listed, in the order config declares them', () => {
  clearConfigEnv();
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://auth.example.test');
  vi.stubEnv('BOOKING_CREDENTIAL_ENCRYPTION_KEY', Buffer.alloc(16).toString('base64'));
  vi.stubEnv('DATABASE_PREPARE', 'maybe');

  expect(configProblems().map((problem) => problem.group)).toEqual(['signIn', 'bookingCipherKey', 'databasePrepare']);
});

test('the per-caller limits default to the backend rates and read their variables', () => {
  expect(config.callerLimits).toEqual({ refreshPerMinute: 30, stayPoisPerMinute: 150, mapboxPerMinute: 60 });
  vi.stubEnv('RATE_LIMIT_REFRESH_PER_MINUTE', '5');
  vi.stubEnv('RATE_LIMIT_STAY_POIS_PER_MINUTE', '50');
  vi.stubEnv('RATE_LIMIT_MAPBOX_PER_MINUTE', '7');
  expect(config.callerLimits).toEqual({ refreshPerMinute: 5, stayPoisPerMinute: 50, mapboxPerMinute: 7 });
});

test('a stay-check limit below the largest stay check is a ConfigError, since that check could never pass', () => {
  vi.stubEnv('RATE_LIMIT_STAY_POIS_PER_MINUTE', '49');
  expect(() => config.callerLimits).toThrow(ConfigError);
  expect(() => config.callerLimits).toThrow(/RATE_LIMIT_STAY_POIS_PER_MINUTE must be at least 50/);
});

test.each(['0', 'lots'])('an unparseable per-caller limit %j is a ConfigError', (raw) => {
  vi.stubEnv('RATE_LIMIT_MAPBOX_PER_MINUTE', raw);
  expect(() => config.callerLimits).toThrow(/RATE_LIMIT_MAPBOX_PER_MINUTE/);
});

test('a per-caller limit that does not parse is reported as a config problem', () => {
  clearConfigEnv();
  vi.stubEnv('RATE_LIMIT_REFRESH_PER_MINUTE', 'lots');
  expect(configProblems().map((problem) => problem.group)).toEqual(['callerLimits']);
});
