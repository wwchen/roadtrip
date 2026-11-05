import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from 'vitest';
import { WEB_ROUTED_PATH } from './webRouting';

const APP_API = path.resolve(import.meta.dirname, '../app');
const CADDYFILE = path.resolve(import.meta.dirname, '../../../../caddy/Caddyfile');
const rule = new RegExp(WEB_ROUTED_PATH);

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

test('the pattern sends every read route apps/web serves', () => {
  for (const [requestPath] of ROUTED) expect(rule.test(requestPath), requestPath).toBe(true);
});

test('the pattern leaves every Kotlin-only route on Kotlin', () => {
  for (const requestPath of KOTLIN_ONLY) expect(rule.test(requestPath), requestPath).toBe(false);
});

test('every path the pattern sends has a route handler in apps/web', () => {
  for (const [, handler] of ROUTED) expect(existsSync(path.join(APP_API, handler, 'route.ts')), handler).toBe(true);
});

test("Caddy routes exactly this pattern to web", () => {
  const caddyfile = readFileSync(CADDYFILE, 'utf8');
  expect(caddyfile).toContain(`@web path_regexp ${WEB_ROUTED_PATH}`);
  expect(caddyfile).toContain('reverse_proxy web:3000');
});
