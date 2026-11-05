import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from 'vitest';
import { MINI_CA_TUNNEL_PATH } from './tunnelRule';

const APP_API = path.resolve(import.meta.dirname, '../app');
const DOC = path.resolve(import.meta.dirname, '../../../../docs/web-app.md');
const rule = new RegExp(MINI_CA_TUNNEL_PATH);

const ROUTED: readonly (readonly [string, string])[] = [
  ['/api/pois', 'api/pois'],
  ['/api/pois/search', 'api/pois/search'],
  ['/api/pois/on-route', 'api/pois/on-route'],
  ['/api/pois/11042', 'api/pois/[id]'],
  ['/api/campgrounds/search', 'api/campgrounds/search'],
  ['/api/campgrounds/details', 'api/campgrounds/details'],
  ['/api/geocode', 'api/geocode'],
  ['/api/route', 'api/route'],
];

const KOTLIN_ONLY = [
  '/api/pois/11042/campsites',
  '/api/pois/11042/campsites/availability',
  '/api/pois/availability/stays',
  '/api/pois/abc',
  '/api/me',
  '/api/watches',
  '/api/build-info',
  '/api/health',
  '/',
  '/poi',
  '/assets/index.js',
];

test('the rule sends every read route apps/web serves', () => {
  for (const [requestPath] of ROUTED) expect(rule.test(requestPath), requestPath).toBe(true);
});

test('the rule leaves every Kotlin-only route on Kotlin', () => {
  for (const requestPath of KOTLIN_ONLY) expect(rule.test(requestPath), requestPath).toBe(false);
});

test('every path the rule sends has a route handler in apps/web', () => {
  for (const [, handler] of ROUTED) expect(existsSync(path.join(APP_API, handler, 'route.ts')), handler).toBe(true);
});

test('the documented rule is this one', () => {
  expect(readFileSync(DOC, 'utf8')).toContain(MINI_CA_TUNNEL_PATH);
});
