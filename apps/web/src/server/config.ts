import { readFileSync } from 'node:fs';
import type { BuildInfoDto } from './contract';

const DATABASE_URL_VARIABLE = 'DATABASE_URL';
const DATABASE_PASSWORD_FILE_VARIABLE = 'DATABASE_PASSWORD_FILE';
const DATABASE_PREPARE_VARIABLE = 'DATABASE_PREPARE';
const DATABASE_REQUIRE_READ_ONLY_VARIABLE = 'DATABASE_REQUIRE_READ_ONLY';
const ENABLED_DATA_PROVIDERS_VARIABLE = 'ENABLED_DATA_PROVIDERS';
const SEARCH_RESULT_LIMIT_VARIABLE = 'SEARCH_RESULT_LIMIT';
const CAMPGROUND_SEARCH_MAX_RESULTS_VARIABLE = 'CAMPGROUND_SEARCH_MAX_RESULTS';
const HEALTH_DATABASE_TOKEN_VARIABLE = 'HEALTH_DATABASE_TOKEN';
const MAPBOX_TOKEN_VARIABLE = 'MAPBOX_TOKEN';
const MAPBOX_TOKEN_FILE_VARIABLE = 'MAPBOX_TOKEN_FILE';
const AVAILABILITY_EGRESS_URL_VARIABLE = 'AVAILABILITY_EGRESS_URL';
const AVAILABILITY_EGRESS_ACCESS_CLIENT_ID_VARIABLE = 'AVAILABILITY_EGRESS_ACCESS_CLIENT_ID';
const AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET_VARIABLE = 'AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET';
const AVAILABILITY_TTL_SECONDS_VARIABLE = 'AVAILABILITY_TTL_SECONDS';
const ENABLED_AVAILABILITY_PROVIDERS_VARIABLE = 'ENABLED_AVAILABILITY_PROVIDERS';
const NEON_AUTH_BASE_URL_VARIABLE = 'NEON_AUTH_BASE_URL';
const NEON_AUTH_COOKIE_SECRET_VARIABLE = 'NEON_AUTH_COOKIE_SECRET';
const APP_DATABASE_URL_VARIABLE = 'APP_DATABASE_URL';
const ADMIN_EMAILS_VARIABLE = 'ADMIN_EMAILS';
const MIN_COOKIE_SECRET_LENGTH = 32;
const LIST_SEPARATOR = ',';

const DEFAULT_DATABASE_PREPARE = true;
const DEFAULT_DATABASE_REQUIRE_READ_ONLY = true;
const DEFAULT_ENABLED_DATA_PROVIDERS: readonly string[] = ['campflare', 'bcparks-strapi', 'aspira', 'reserveamerica', 'reservecalifornia'];
const DEFAULT_SEARCH_RESULT_LIMIT = 200;
const MAX_SEARCH_RESULT_LIMIT = 1000;
const DEFAULT_CAMPGROUND_SEARCH_MAX_RESULTS = 5000;
const MAX_CAMPGROUND_SEARCH_MAX_RESULTS = 20000;
const DEFAULT_BUILD_ENV = 'local';
const DEFAULT_AVAILABILITY_TTL_SECONDS = 7200;
const MAX_AVAILABILITY_TTL_SECONDS = 604_800;
const DEFAULT_ENABLED_AVAILABILITY_PROVIDERS: readonly string[] = ['aspira', 'campflare', 'reserveamerica', 'reservecalifornia'];
const LIST_JOINER = ', ';

export const AVAILABILITY_EGRESS_TIMEOUT_MS = 25_000;
export const AVAILABILITY_STORED_TIMEOUT_MS = 5_000;
export const STAY_CHECK_FAN_OUT = 3;
export const STAY_CHECK_PER_POI_TIMEOUT_MS = 45_000;
export const STAY_CHECK_STREAM_TIMEOUT_MS = 120_000;
export const STAY_CHECK_HEARTBEAT_MS = 15_000;

export interface EgressAccessCredentials {
  clientId: string;
  clientSecret: string;
}

export interface AvailabilityEgressConfig {
  egressUrl: URL;
  egressAccess: EgressAccessCredentials;
}

const PROVIDER_SEPARATOR = ',';
const TRUE_LITERAL = 'true';
const FALSE_LITERAL = 'false';
const TRAILING_NEWLINES = /[\r\n]+$/;

export interface SignInConfig {
  neonAuthBaseUrl: URL;
  cookieSecret: string;
  appDatabaseUrl: string;
  adminEmails: ReadonlySet<string>;
}

export class ConfigError extends Error {
  constructor(variable: string, problem: string) {
    super(`${variable} ${problem}`);
    this.name = 'ConfigError';
  }
}

function booleanFrom(variable: string, fallback: boolean): boolean {
  const raw = process.env[variable];
  if (raw === undefined) return fallback;
  if (raw === TRUE_LITERAL) return true;
  if (raw === FALSE_LITERAL) return false;
  throw new ConfigError(variable, `must be ${TRUE_LITERAL} or ${FALSE_LITERAL}`);
}

function boundedIntegerFrom(variable: string, fallback: number, maximum: number): number {
  const raw = process.env[variable];
  if (raw === undefined) return fallback;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > maximum) {
    throw new ConfigError(variable, `must be an integer from 1 to ${maximum}`);
  }
  return parsed;
}

function listFrom(variable: string, fallback: readonly string[]): readonly string[] {
  const raw = process.env[variable];
  if (raw === undefined) return fallback;
  const entries = raw
    .split(PROVIDER_SEPARATOR)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  if (entries.length === 0) throw new ConfigError(variable, 'must list at least one entry');
  return entries;
}

function textOr(value: string | undefined, fallback: string): string {
  const raw = value?.trim();
  return raw === undefined || raw.length === 0 ? fallback : raw;
}

function fileSecretFrom(variable: string): string | undefined {
  const file = process.env[variable];
  if (file === undefined || file.trim().length === 0) return undefined;
  let contents: string;
  try {
    contents = readFileSync(file, 'utf8');
  } catch {
    throw new ConfigError(variable, `names a file that cannot be read: ${file}`);
  }
  const value = contents.replace(TRAILING_NEWLINES, '');
  if (value.length === 0) throw new ConfigError(variable, `names an empty file: ${file}`);
  return value;
}

function withPasswordFrom(rawUrl: string, password: string | undefined): string {
  if (password === undefined) return rawUrl;
  const url = new URL(rawUrl);
  if (url.password.length > 0) {
    throw new ConfigError(DATABASE_PASSWORD_FILE_VARIABLE, `is set but ${DATABASE_URL_VARIABLE} already carries a password`);
  }
  url.password = encodeURIComponent(password);
  return url.toString();
}

function secretFrom(variable: string): string | undefined {
  const raw = process.env[variable];
  if (raw === undefined || raw.trim().length === 0) return undefined;
  return raw;
}

function availabilityEgressFrom(): AvailabilityEgressConfig | null {
  const values = {
    [AVAILABILITY_EGRESS_URL_VARIABLE]: secretFrom(AVAILABILITY_EGRESS_URL_VARIABLE),
    [AVAILABILITY_EGRESS_ACCESS_CLIENT_ID_VARIABLE]: secretFrom(AVAILABILITY_EGRESS_ACCESS_CLIENT_ID_VARIABLE),
    [AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET_VARIABLE]: secretFrom(AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET_VARIABLE),
  };
  const missing = Object.entries(values).filter(([, value]) => value === undefined).map(([variable]) => variable);
  if (missing.length === Object.keys(values).length) return null;
  if (missing.length > 0) throw new ConfigError(missing.join(LIST_JOINER), 'must be set with the other availability egress settings');
  let egressUrl: URL;
  try {
    egressUrl = new URL(values[AVAILABILITY_EGRESS_URL_VARIABLE] as string);
  } catch {
    throw new ConfigError(AVAILABILITY_EGRESS_URL_VARIABLE, 'must be an absolute URL');
  }
  return {
    egressUrl,
    egressAccess: {
      clientId: values[AVAILABILITY_EGRESS_ACCESS_CLIENT_ID_VARIABLE] as string,
      clientSecret: values[AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET_VARIABLE] as string,
    },
  };
}

function emailSetFrom(variable: string): ReadonlySet<string> {
  const raw = process.env[variable] ?? '';
  return new Set(
    raw
      .split(LIST_SEPARATOR)
      .map((entry) => entry.trim().toLowerCase())
      .filter((entry) => entry.length > 0),
  );
}

function signInFrom(): SignInConfig | null {
  const values = {
    [NEON_AUTH_BASE_URL_VARIABLE]: secretFrom(NEON_AUTH_BASE_URL_VARIABLE),
    [NEON_AUTH_COOKIE_SECRET_VARIABLE]: secretFrom(NEON_AUTH_COOKIE_SECRET_VARIABLE),
    [APP_DATABASE_URL_VARIABLE]: secretFrom(APP_DATABASE_URL_VARIABLE),
  };
  const missing = Object.entries(values).filter(([, value]) => value === undefined).map(([variable]) => variable);
  if (missing.length === Object.keys(values).length) return null;
  if (missing.length > 0) throw new ConfigError(missing.join(LIST_JOINER), 'must be set with the other sign-in settings');
  let neonAuthBaseUrl: URL;
  try {
    neonAuthBaseUrl = new URL(values[NEON_AUTH_BASE_URL_VARIABLE] as string);
  } catch {
    throw new ConfigError(NEON_AUTH_BASE_URL_VARIABLE, 'must be an absolute URL');
  }
  const cookieSecret = values[NEON_AUTH_COOKIE_SECRET_VARIABLE] as string;
  if (cookieSecret.length < MIN_COOKIE_SECRET_LENGTH) {
    throw new ConfigError(NEON_AUTH_COOKIE_SECRET_VARIABLE, `must be at least ${MIN_COOKIE_SECRET_LENGTH} characters`);
  }
  return {
    neonAuthBaseUrl,
    cookieSecret,
    appDatabaseUrl: values[APP_DATABASE_URL_VARIABLE] as string,
    adminEmails: emailSetFrom(ADMIN_EMAILS_VARIABLE),
  };
}

export const config = {
  get signIn(): SignInConfig | null {
    return signInFrom();
  },
  get availabilityEgress(): AvailabilityEgressConfig | null {
    return availabilityEgressFrom();
  },
  get availabilityTtlSeconds(): number {
    return boundedIntegerFrom(AVAILABILITY_TTL_SECONDS_VARIABLE, DEFAULT_AVAILABILITY_TTL_SECONDS, MAX_AVAILABILITY_TTL_SECONDS);
  },
  get enabledAvailabilityProviders(): readonly string[] {
    return listFrom(ENABLED_AVAILABILITY_PROVIDERS_VARIABLE, DEFAULT_ENABLED_AVAILABILITY_PROVIDERS);
  },
  get buildInfo(): BuildInfoDto {
    return {
      env: textOr(process.env.BUILD_ENV, DEFAULT_BUILD_ENV),
      sha: textOr(process.env.BUILD_SHA, ''),
      branch: textOr(process.env.BUILD_BRANCH, ''),
    };
  },
  get databaseUrl(): string {
    const raw = process.env[DATABASE_URL_VARIABLE];
    if (raw === undefined || raw.trim().length === 0) {
      throw new ConfigError(DATABASE_URL_VARIABLE, 'is not set');
    }
    return withPasswordFrom(raw, fileSecretFrom(DATABASE_PASSWORD_FILE_VARIABLE));
  },
  get databasePrepare(): boolean {
    return booleanFrom(DATABASE_PREPARE_VARIABLE, DEFAULT_DATABASE_PREPARE);
  },
  get databaseRequireReadOnly(): boolean {
    return booleanFrom(DATABASE_REQUIRE_READ_ONLY_VARIABLE, DEFAULT_DATABASE_REQUIRE_READ_ONLY);
  },
  get enabledDataProviders(): readonly string[] {
    return listFrom(ENABLED_DATA_PROVIDERS_VARIABLE, DEFAULT_ENABLED_DATA_PROVIDERS);
  },
  get mapboxToken(): string | undefined {
    const fromFile = fileSecretFrom(MAPBOX_TOKEN_FILE_VARIABLE);
    const direct = secretFrom(MAPBOX_TOKEN_VARIABLE);
    if (fromFile !== undefined && direct !== undefined) {
      throw new ConfigError(MAPBOX_TOKEN_FILE_VARIABLE, `and ${MAPBOX_TOKEN_VARIABLE} are both set`);
    }
    return fromFile ?? direct;
  },
  get healthDatabaseToken(): string | undefined {
    return secretFrom(HEALTH_DATABASE_TOKEN_VARIABLE);
  },
  get campgroundSearchMaxResults(): number {
    return boundedIntegerFrom(
      CAMPGROUND_SEARCH_MAX_RESULTS_VARIABLE,
      DEFAULT_CAMPGROUND_SEARCH_MAX_RESULTS,
      MAX_CAMPGROUND_SEARCH_MAX_RESULTS,
    );
  },
  get searchResultLimit(): number {
    return boundedIntegerFrom(
      SEARCH_RESULT_LIMIT_VARIABLE,
      DEFAULT_SEARCH_RESULT_LIMIT,
      MAX_SEARCH_RESULT_LIMIT,
    );
  },
};
