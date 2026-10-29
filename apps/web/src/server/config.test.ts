import { afterEach, expect, test, vi } from 'vitest';
import { ConfigError, config } from './config';

const DATABASE_URL = 'postgres://reader@db.test/roadtrip';
const DEFAULT_PROVIDERS = ['campflare', 'bcparks-strapi', 'aspira'];
const DEFAULT_LIMIT = 200;
const MAX_LIMIT = 1000;
const CUSTOM_LIMIT = 25;

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
  expect(config.databasePrepare).toBe(true);
  expect(config.enabledDataProviders).toEqual(DEFAULT_PROVIDERS);
  expect(config.searchResultLimit).toBe(DEFAULT_LIMIT);
});

test('the optional settings read their variables', () => {
  vi.stubEnv('DATABASE_PREPARE', 'false');
  vi.stubEnv('ENABLED_DATA_PROVIDERS', ' campflare , aspira ,');
  vi.stubEnv('SEARCH_RESULT_LIMIT', String(CUSTOM_LIMIT));
  expect(config.databasePrepare).toBe(false);
  expect(config.enabledDataProviders).toEqual(['campflare', 'aspira']);
  expect(config.searchResultLimit).toBe(CUSTOM_LIMIT);
});

test('an unparseable optional setting throws a named error', () => {
  vi.stubEnv('DATABASE_PREPARE', 'maybe');
  expect(() => config.databasePrepare).toThrow(ConfigError);
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
