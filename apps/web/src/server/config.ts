import type { BuildInfoDto } from './contract';

const DATABASE_URL_VARIABLE = 'DATABASE_URL';
const DATABASE_PREPARE_VARIABLE = 'DATABASE_PREPARE';
const ENABLED_DATA_PROVIDERS_VARIABLE = 'ENABLED_DATA_PROVIDERS';
const SEARCH_RESULT_LIMIT_VARIABLE = 'SEARCH_RESULT_LIMIT';
const CAMPGROUND_SEARCH_MAX_RESULTS_VARIABLE = 'CAMPGROUND_SEARCH_MAX_RESULTS';
const HEALTH_DATABASE_TOKEN_VARIABLE = 'HEALTH_DATABASE_TOKEN';
const MAPBOX_TOKEN_VARIABLE = 'MAPBOX_TOKEN';

const DEFAULT_DATABASE_PREPARE = true;
const DEFAULT_ENABLED_DATA_PROVIDERS: readonly string[] = ['campflare', 'bcparks-strapi', 'aspira'];
const DEFAULT_SEARCH_RESULT_LIMIT = 200;
const MAX_SEARCH_RESULT_LIMIT = 1000;
const DEFAULT_CAMPGROUND_SEARCH_MAX_RESULTS = 5000;
const MAX_CAMPGROUND_SEARCH_MAX_RESULTS = 20000;
const DEFAULT_BUILD_ENV = 'local';

const PROVIDER_SEPARATOR = ',';
const TRUE_LITERAL = 'true';
const FALSE_LITERAL = 'false';

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

function secretFrom(variable: string): string | undefined {
  const raw = process.env[variable];
  if (raw === undefined || raw.trim().length === 0) return undefined;
  return raw;
}

export const config = {
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
    return raw;
  },
  get databasePrepare(): boolean {
    return booleanFrom(DATABASE_PREPARE_VARIABLE, DEFAULT_DATABASE_PREPARE);
  },
  get enabledDataProviders(): readonly string[] {
    return listFrom(ENABLED_DATA_PROVIDERS_VARIABLE, DEFAULT_ENABLED_DATA_PROVIDERS);
  },
  get mapboxToken(): string | undefined {
    return secretFrom(MAPBOX_TOKEN_VARIABLE);
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
