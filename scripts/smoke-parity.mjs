#!/usr/bin/env node
/**
 * Drives the built React app in headless Chromium, with the same selectors
 * `SmokeTest.kt` uses — against a stub API instead of a live stack.
 *
 * **Why this exists.** The two cheap gates cannot see a class of failure that keeps
 * happening: jsdom has no layout and no bundler, so `vitest` passes on a page that
 * renders wrongly or fails to mount, and the colour/CSS checks read source rather
 * than a rendered page. The gate that WOULD catch it, `make qa`, needs Postgres, the
 * geocoder and tiles. This sits in between: no database, no network, ~30 seconds.
 *
 * It has paid for itself. Run against Phase 4e it found a P0 the whole unit suite was
 * green over — `flattenHydratedPoi` rewrites a campground's `category` to its
 * `subcategory`, so the drawer registry missed and every campground opened "No detail
 * view for this place yet" — plus three selector bugs in the rewritten smoke that
 * would each have failed CI (an LDS checkbox that cannot be clicked through its
 * input, a corridor slider that is not in the DOM until a route is live, and a
 * repaint seam that no longer repaints).
 *
 * **Keep it in step with `SmokeTest.kt`.** The point is that the assertions are the
 * same ones, so a selector that dies in the port fails HERE, in seconds, instead of
 * in CI. A weaker check is worse than none: this file once asserted
 * `.some(agency === 'US Forest Service')` where the Kotlin asserted the exact set,
 * and that is precisely the difference it failed to catch.
 *
 * NOT a CI gate: it needs a browser, and `smoke` already covers this ground against
 * real data. This is the fast local loop — `make browser-check`.
 *
 *   PORT=8794 CHROMIUM=/path/to/chrome node scripts/smoke-parity.mjs
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIST = path.join(REPO, 'frontend/dist');
const PORT = Number(process.env.PORT ?? 8794);

/**
 * playwright-core is deliberately NOT a dependency of this repo: the only other
 * browser driver here is Playwright-JVM, and adding an npm one for a local tool
 * would put it in everyone's install. Fail loudly with the fix rather than skipping,
 * because a skip that looks like a pass is the failure mode this whole file is about.
 */
let chromium;
try {
  ({ chromium } = await import('playwright-core'));
} catch {
  console.error(
    'scripts/smoke-parity.mjs needs playwright-core and a Chromium binary.\n' +
      '  npm i --no-save playwright-core\n' +
      '  CHROMIUM=$(which chromium || echo /opt/pw-browsers/chromium/chrome-linux/chrome) \\\n' +
      '    node scripts/smoke-parity.mjs',
  );
  process.exit(1);
}

const MIME = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.png': 'image/png',
};

const fc = (features, extra = {}) => ({ type: 'FeatureCollection', features, ...extra });

const cg = (id, lng, lat, agency) => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [lng, lat] },
  properties: { category: 'campground', subcategory: 'federal', agency },
});

const THREE_AGENCIES = fc(
  [
    cg(8101, -123.0, 49.0, 'National Park Service'),
    cg(8102, -123.02, 49.02, 'US Forest Service'),
    { ...cg(8103, -123.04, 49.04, 'WA State Parks'), properties: { category: 'campground', subcategory: 'state', agency: 'WA State Parks' } },
  ],
  { truncated: false },
);

const ROUTE = fc([
  {
    type: 'Feature',
    geometry: { type: 'LineString', coordinates: [[-123.12, 49.28], [-122.33, 47.61]] },
    properties: { distance_m: 230000, duration_s: 9000, legs: [{ distance_m: 230000, duration_s: 9000 }] },
  },
]);

const ROUTE_MODE_LINE = fc([
  {
    type: 'Feature',
    geometry: { type: 'LineString', coordinates: [[-122.33, 47.61], [-121.5, 48.1]] },
    properties: { distance_m: 100000, duration_s: 7200, legs: [{ distance_m: 100000, duration_s: 7200 }] },
  },
]);

const GYM = {
  type: 'Feature',
  id: 4242,
  geometry: { type: 'Point', coordinates: [-122.31, 47.62] },
  properties: {
    category: 'planet-fitness',
    name: 'Shared Gym',
    address: { street: '123 Test Way', city: 'Seattle', state: 'WA', postcode: '98101' },
  },
};

const CABINS = {
  type: 'Feature',
  id: 45626,
  geometry: { type: 'Point', coordinates: [-122.8127778, 39.00722222] },
  properties: {
    source: 'reservecalifornia-campgrounds',
    source_id: 'rc-629',
    category: 'campground',
    subcategory: 'state',
    agency: 'California State Parks',
    name: 'Clear Lake SP Cabins',
    region: 'CA',
    country: 'US',
    detail: {
      availability_supported: true,
      description: 'Clear Lake State Park offers rental cabins near the lake.',
      photo_url: 'https://cali-content.usedirect.com/Images/California/ParkImages/Place/629.jpg',
      cta: [{ url: 'https://reservecalifornia.com/park/629', label: 'Reserve on ReserveCalifornia', kind: 'reserve' }],
      provider_ref: { place_id: 629, facility_ids: [889] },
      raw: {
        amenities: ['Restrooms', 'Showers'],
        upstream: {
          Name: 'Clear Lake SP Cabins',
          FacilityDescription: '<p>Raw-only description should not render.</p>',
          MEDIA: [{ URL: 'https://example.test/raw-only.jpg', IsPrimary: true }],
        },
      },
    },
  },
};

const ON_ROUTE_CG = {
  type: 'Feature',
  id: 999,
  geometry: { type: 'Point', coordinates: [-122.0, 47.8] },
  properties: { category: 'campground', subcategory: 'federal' },
};

const ON_ROUTE_DETAIL = {
  type: 'Feature',
  id: 999,
  geometry: { type: 'Point', coordinates: [-122.0, 47.8] },
  properties: {
    category: 'campground',
    subcategory: 'federal',
    name: 'On-route Campground',
    region: 'WA',
  },
};

const TUNNEL = {
  type: 'Feature',
  id: 5150,
  geometry: { type: 'Point', coordinates: [-115.55, 51.18] },
  properties: {
    category: 'campground',
    subcategory: 'federal',
    agency: 'Parks Canada',
    name: 'Tunnel Mountain - Village 1',
    region: 'AB',
    detail: {
      cta: [{ url: 'https://reservation.pc.gc.ca/Banff', label: 'Reserve with Parks Canada', kind: 'reserve' }],
    },
  },
};

// What the current scenario wants the endpoints to answer.
let viewportPois = fc([], { truncated: false });
let searchResults = [];
const calls = { pois: 0, route: 0, onRoute: 0 };

const json = (res, body, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

function serveFile(res, file) {
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404).end('not found');
    return;
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const p = url.pathname;

  if (req.method === 'POST') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      if (p === '/api/pois') {
        calls.pois += 1;
        return json(res, viewportPois);
      }
      if (p === '/api/pois/on-route') {
        calls.onRoute += 1;
        return json(res, fc([ON_ROUTE_CG]));
      }
      return json(res, {}, 404);
    });
    return;
  }

  if (p === '/api/me') {
    return json(res, { authenticated: false, auth_enabled: true, auth_embedded: false, user: null });
  }
  if (p === '/api/watches') return json(res, { watches: [], total: 0 });
  if (p === '/api/pois/search') return json(res, { results: searchResults });
  if (p === '/api/geocode') return json(res, { results: [] });
  if (p === '/api/route') {
    calls.route += 1;
    return json(res, url.searchParams.get('coords')?.includes('49.28') ? ROUTE : ROUTE_MODE_LINE);
  }
  if (p === '/api/pois/4242') return json(res, GYM);
  if (p === '/api/pois/45626') return json(res, CABINS);
  if (p === '/api/pois/999') return json(res, ON_ROUTE_DETAIL);
  if (p === '/api/pois/5150') return json(res, TUNNEL);
  if (p.startsWith('/api/')) return json(res, {}, 404);
  // No `/web/` branch: that tree is gone. `tokens.css` and the sandbox chrome are
  // bundled now, so everything the page needs is under `frontend/dist`.
  return serveFile(res, path.join(DIST, p === '/' ? 'index.html' : p.slice(1)));
});

if (!fs.existsSync(path.join(DIST, 'index.html'))) {
  console.error(`no build at ${DIST} — run \`cd frontend && npm run build\` first.`);
  process.exit(1);
}

await new Promise((r) => server.listen(PORT, r));

// `CHROMIUM` because there is no npm-managed browser download here by design; a
// system Chromium or a Playwright cache both work.
const browser = await chromium.launch(
  process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {},
);

const problems = [];
const check = (ok, message) => {
  if (!ok) problems.push(message);
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${message}`);
};

async function withPage(viewport, run) {
  const context = await browser.newContext({ viewport });
  // The Carto basemaps are inline styles, so `style.load` fires with no network —
  // which is what makes `__rtState.mapReady` reachable offline.
  await context.addInitScript(() => window.localStorage.setItem('basemap', 'carto-dark'));
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/tile|cartocdn|favicon|404|Failed to load resource/i.test(m.text())) {
      errors.push(`console: ${m.text()}`);
    }
  });
  try {
    await run(page, errors);
  } finally {
    for (const e of errors) console.log(`  page: ${e}`);
    await context.close();
  }
}

// Real functions, never strings: Playwright's JS binding evaluates a string as an
// EXPRESSION, so `'() => x'` yields a truthy function object and every waitForFunction
// would pass instantly. (Java has no function literals, so the Kotlin smoke's string
// form is the supported one there.)
const mapReady = (page) =>
  page.waitForFunction(() => globalThis.__rtState?.mapReady === true, null, { timeout: 15000 });

const DRAWER = "aside.rt-drawer.rt-drawer--open[role='dialog']";

/** Poll a locator's text, the way Playwright's own web-first assertions do. */
async function waitForText(locator, expected, timeout = 10000) {
  const deadline = Date.now() + timeout;
  let text = '';
  while (Date.now() < deadline) {
    text = (await locator.textContent().catch(() => '')) ?? '';
    if (text.includes(expected)) return text;
    await new Promise((r) => setTimeout(r, 100));
  }
  return text;
}

// --- the layers panel on a phone -------------------------------------------
console.log('\n# mobile layers panel');
await withPage({ width: 390, height: 844 }, async (page) => {
  viewportPois = fc([], { truncated: false });
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'load' });
  await mapReady(page);

  check((await page.locator(".rt-legend input[type='search']").count()) === 0, 'no legend search box');

  await page.getByLabel('Toggle layers panel').click();
  await page.waitForFunction(
    () => document.querySelector('.rt-legend')?.classList.contains('rt-legend--open') === true,
    null,
    { timeout: 5000 },
  );
  check(true, 'the hamburger opens the sheet');
  const close = page.getByLabel('Hide layers panel');
  check(await close.isVisible(), 'the close control is visible while open');
  await close.click();
  await page.waitForFunction(
    () => document.querySelector('.rt-legend')?.classList.contains('rt-legend--open') === false,
    null,
    { timeout: 5000 },
  );
  check(true, 'the close control closes it again');
});

// --- the agency filter ------------------------------------------------------
console.log('\n# agency filter');
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = THREE_AGENCIES;
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'load' });
  await mapReady(page);
  await page.evaluate(() => {
    globalThis.__rtMap.jumpTo({ center: [-123.02, 49.02], zoom: 10 });
  });
  await page.waitForFunction(
    () => document.querySelectorAll('.rt-legend__agencies input[type="checkbox"]').length >= 3,
    null,
    { timeout: 15000 },
  );
  check(true, 'three agency rows appear');

  // LDS hides the real input (`.lds-check input { opacity: 0; width: 0; pointer-events: none }`)
  // and draws `.lds-check__box`, so the label is the clickable control and the input is
  // only good for reading state.
  const row = (name) => page.locator('.rt-legend__agencies label').filter({ hasText: name });
  const box = (name) => row(name).locator("input[type='checkbox']");
  const nps = box('National Park Service');
  const forest = box('US Forest Service');
  const state = box('WA State Parks');
  check(await nps.isChecked(), 'NPS defaults to shown');
  check(await forest.isChecked(), 'USFS defaults to shown');
  check(await state.isChecked(), 'WA State Parks defaults to shown');

  await row('US Forest Service').click();
  check(!(await forest.isChecked()), 'clicking the USFS row unchecks it');
  await page.waitForFunction(
    () => {
      const map = globalThis.__rtMap;
      const canvas = map?.getCanvas?.();
      if (!map || !canvas || !map.getLayer('cg-points')) return false;
      const agencies = map
        .queryRenderedFeatures([[0, 0], [canvas.width, canvas.height]], { layers: ['cg-points'] })
        .map((f) => f.properties.agency)
        .filter(Boolean)
        .sort();
      return JSON.stringify(agencies) === JSON.stringify(['National Park Service', 'WA State Parks']);
    },
    null,
    { timeout: 5000 },
  );
  check(true, 'the map drops only the unchecked agency');

  await page.evaluate(() => globalThis.__rtRefreshBbox());
  await page.waitForFunction(
    () => document.querySelectorAll('.rt-legend__agencies input[type="checkbox"]').length >= 3,
    null,
    { timeout: 5000 },
  );
  check(await nps.isChecked(), 'NPS still shown after a repaint');
  check(!(await forest.isChecked()), 'the un-check survives a repaint');

  await row('US Forest Service').click();
  await page.waitForFunction(
    () => {
      const map = globalThis.__rtMap;
      const canvas = map?.getCanvas?.();
      if (!map || !canvas || !map.getLayer('cg-points')) return false;
      // The SAME predicate the Kotlin asserts, not a weaker one: this harness said
      // `.some(agency === 'US Forest Service')` at first, and that is exactly why it
      // missed the real failure — the exact set is three agencies after a refetch
      // repaint, where the vanilla's route-POI substitution left two.
      const agencies = map
        .queryRenderedFeatures([[0, 0], [canvas.width, canvas.height]], { layers: ['cg-points'] })
        .map((f) => f.properties.agency)
        .filter(Boolean)
        .sort();
      return (
        JSON.stringify(agencies) ===
        JSON.stringify(['National Park Service', 'US Forest Service', 'WA State Parks'])
      );
    },
    null,
    { timeout: 5000 },
  );
  check(true, 're-checking brings its pins back');
});

// --- a shared POI link ------------------------------------------------------
console.log('\n# poi share link');
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = fc([], { truncated: false });
  await page.goto(`http://127.0.0.1:${PORT}/?poi=4242`, { waitUntil: 'load' });
  const drawer = page.locator(DRAWER);
  await drawer.waitFor({ state: 'visible', timeout: 15000 });
  check(true, 'the drawer opens from ?poi=');
  check((await drawer.locator('h2').innerText()).includes('Shared Gym'), 'it names the POI');
  check((await drawer.locator('.rt-poi-share').count()) === 0, 'no share control in the drawer');
  check(page.url().includes('poi=4242'), 'the URL still carries the POI');
});

// --- the promoted-DTO campground drawer ------------------------------------
console.log('\n# promoted poi dto fields');
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = fc([], { truncated: false });
  await page.goto(`http://127.0.0.1:${PORT}/?poi=45626`, { waitUntil: 'load' });
  const drawer = page.locator(DRAWER);
  await drawer.waitFor({ state: 'visible', timeout: 15000 });
  check((await drawer.locator('h2').innerText()).includes('Clear Lake SP Cabins'), 'names the campground');
  check(
    (await drawer.locator('.rt-cg-agency').innerText()).includes('California State Parks'),
    'the agency line is present',
  );

  const about = drawer.locator("section:has(> h3:text-is('About'))");
  check((await about.count()) === 1, 'the About section resolves');
  const aboutText = await about.innerText();
  check(aboutText.includes('Clear Lake State Park offers rental cabins'), 'About renders the DTO description');
  check(!aboutText.includes('Raw-only description'), 'About does not render the raw upstream description');

  const details = drawer.locator('.rt-cg-details');
  check((await details.count()) === 1, 'one details block');
  // textContent, not innerText: `containsText` in the Kotlin smoke compares the
  // former, so a CSS `text-transform: uppercase` heading still matches its source case.
  const detailsText = await details.textContent();
  check(detailsText.includes('Source metadata'), 'details carry the source metadata group');
  check(detailsText.includes('rc-629'), 'details name the source id');
  check(!detailsText.includes('raw-only.jpg'), 'details do not render raw upstream media');

  const hero = await page.evaluate(() => {
    const el = document.querySelector('.rt-drawer-hero');
    return el ? getComputedStyle(el).backgroundImage : 'NO HERO ELEMENT';
  });
  check(hero.includes('/Place/629.jpg'), `hero uses the DTO photo (${hero.slice(0, 60)}…)`);
  check(!hero.includes('raw-only.jpg'), 'hero does not use raw upstream media');
});

// --- a shared route link ----------------------------------------------------
console.log('\n# route share link');
const ROUTE_PARAM =
  'eyJ2IjoxLCJyYWRpdXNfbWlsZXMiOjUsInN0b3BzIjpbeyJuYW1lIjoiVmFuY291dmVyIiwibG5nIjotMTIzLjEyLCJsYXQiOjQ5LjI4LCJraW5kIjoiUExBQ0UifSx7Im5hbWUiOiJTZWF0dGxlIiwibG5nIjotMTIyLjMzLCJsYXQiOjQ3LjYxLCJraW5kIjoiUExBQ0UifV19';
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = fc([], { truncated: false });
  calls.route = 0;
  calls.onRoute = 0;
  await page.goto(`http://127.0.0.1:${PORT}/?route=${ROUTE_PARAM}`, { waitUntil: 'load' });
  await page.waitForFunction(() => globalThis.__rtRouteActive?.() === true, null, { timeout: 15000 });
  check(true, 'the shared route becomes active');

  check(
    (await page.locator('.tb-row[data-i="0"] .tb-input').inputValue()) === 'Vancouver',
    'row 0 is the shared origin',
  );
  check(
    (await page.locator('.tb-row[data-i="1"] .tb-input').inputValue()) === 'Seattle',
    'row 1 is the shared destination',
  );
  check((await page.locator('#tb-corridor-value').innerText()).includes('5 mi'), 'the radius is restored');
  check((await page.locator('#tb-share-route').count()) === 0, 'no share-route control');
  const summary = await page.locator('#tb-actions #tb-route-summary').innerText();
  check(summary.includes('230 km'), `the summary has the distance (${summary})`);
  check(summary.includes('2h 30m'), 'the summary has the driving time');
  check(
    await page.locator('#tb-results .tb-results-body #tb-corridor').isVisible(),
    'the corridor slider is inside the results body',
  );
  check((await page.locator('#tb-results .tb-results-body #tb-trip-dates').count()) === 0, 'no trip dates');

  await page.locator('#tb-results .tb-results-head').click();
  check(await page.locator('#tb-results .tb-results-body').isHidden(), 'the head collapses the body');
  check(page.url().includes('route='), 'the URL still carries the route');
  const shareUrl = await page.evaluate(() => globalThis.__rtRouteShareUrl?.() || '');
  check(shareUrl.includes('route='), 'the share URL carries the route');
  check(calls.route === 1, `the route is fetched once (was ${calls.route})`);
  await page.waitForTimeout(750);
  check(calls.onRoute >= 1, 'the corridor is queried');
});

// --- route mode from the current location ----------------------------------
console.log('\n# route mode');
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = fc(
    [
      {
        type: 'Feature',
        id: 7,
        geometry: { type: 'Point', coordinates: [-90.0, 40.0] },
        properties: { category: 'supercharger' },
      },
    ],
    { truncated: false },
  );
  calls.pois = 0;
  calls.route = 0;
  calls.onRoute = 0;
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'load' });
  await mapReady(page);

  await page.evaluate(() =>
    globalThis.__rtUseCurrentLocationForTripStop(0, { lng: -122.33, lat: 47.61 }),
  );
  check(
    (await page.locator('.tb-row[data-i="0"] .tb-input').inputValue()) === 'Current location',
    'the seam fills row 0 with the current location',
  );

  await page.locator('#tb-directions').click();
  await page.waitForSelector('.tb-row[data-i="1"] .tb-input', { timeout: 5000 });
  check(
    (await page.locator('#tb-corridor-range').count()) === 0,
    'the corridor slider is not there yet — it comes with the results list',
  );

  await page.evaluate(() =>
    globalThis.__rtAddTripStop({ name: 'Route Destination', lng: -121.5, lat: 48.1, kind: 'PLACE' }),
  );
  await page.waitForFunction(() => globalThis.__rtRouteActive?.() === true, null, { timeout: 10000 });
  check(page.url().includes('route='), 'an active route updates the URL');

  await page.waitForFunction(
    () => globalThis.__rtState?.overlayData?.cg?.features?.[0]?.id === 999,
    null,
    { timeout: 10000 },
  );
  check(true, 'the corridor POIs are what the map paints');

  check((await page.locator('#tb-corridor-range').inputValue()) === '5', 'the slider starts at 5');
  check((await page.locator('#tb-corridor-value').innerText()).includes('5 mi'), 'and says so');

  const firstCard = page.locator('.tb-card').first();
  await firstCard.waitFor({ state: 'visible', timeout: 10000 });
  // Polled, because `assertThat(...).containsText(...)` in the Kotlin smoke is a
  // web-first assertion that retries: the card lands with the pin's fallback name
  // ("Campground") and takes the real one from its own `/api/pois/{id}` hydration.
  const head = await waitForText(firstCard.locator('.tb-card-head'), 'On-route Campground');
  check(head.includes('On-route Campground'), `the first card names the campground (${head})`);
  check((await firstCard.locator('.tb-card-location').innerText()).includes('WA'), 'and its region');

  const poisAfterRoute = calls.pois;
  await page.evaluate(() => {
    globalThis.__rtMap.jumpTo({ center: [-120.5, 48.0], zoom: 10 });
  });
  await page.waitForTimeout(750);
  check(calls.route === 1, `the route is fetched once (was ${calls.route})`);
  check(calls.onRoute >= 1, 'the corridor is queried');
  check(calls.pois === poisAfterRoute, `no viewport refetch while routed (${poisAfterRoute} → ${calls.pois})`);
  check(
    (await page.evaluate(() => globalThis.__rtState.overlayData.sc.features.length)) === 0,
    'the route paint clears the viewport superchargers',
  );
});

// --- search → pick → drawer (the Banff path) -------------------------------
console.log('\n# search, pick, drawer');
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = fc([], { truncated: false });
  searchResults = [
    { id: 5150, name: 'Tunnel Mountain - Village 1', category: 'campground', region: 'AB', lng: -115.55, lat: 51.18 },
  ];
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'load' });
  await mapReady(page);

  await page.fill('.tb-row[data-i="0"] .tb-input', 'tunnel mountain village');
  const pinResult = page.locator('#tb-dropdown .tb-result').filter({ has: page.locator('.tb-kind', { hasText: 'CG' }) });
  await pinResult.first().waitFor({ state: 'visible', timeout: 5000 });
  check(true, 'the dropdown offers a CG row');
  await pinResult.first().click();

  const drawer = page.locator(DRAWER);
  await drawer.waitFor({ state: 'visible', timeout: 10000 });
  check((await drawer.locator('h2').innerText()).includes('Tunnel Mountain'), 'picking it opens its drawer');
  check(page.url().includes('poi='), 'and puts the POI in the URL');
  const reserve = drawer.locator('.rt-drawer-actions a[href]').first();
  check(await reserve.isVisible(), 'the reserve CTA is visible');
  const href = await reserve.getAttribute('href');
  check(
    /(reservation\.pc\.gc\.ca|parks\.canada\.ca|recreation\.gov)/.test(href ?? ''),
    `the CTA points at a booking host (${href})`,
  );
  searchResults = [];
});

// --- the page mounts, and the canvas has a size ----------------------------
console.log('\n# mount and canvas size');
await withPage({ width: 1280, height: 800 }, async (page) => {
  viewportPois = fc([], { truncated: false });
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'load' });
  const h1 = page.locator('h1');
  check((await h1.count()) === 1, `exactly one h1 (${await h1.count()})`);
  check((await h1.innerText()) === 'Roadtrip Map', `the h1 is the legend title (${await h1.innerText()})`);
  check((await page.locator('#root').innerHTML()).trim().length > 0, '#root is not empty');
  await page.waitForSelector('.rt-map-canvas', { timeout: 15000 });
  const box = await page.locator('.rt-map-canvas').boundingBox();
  check(!!box && box.width > 0 && box.height > 0, `the canvas has a size (${JSON.stringify(box)})`);
});

await browser.close();
server.close();

console.log(`\n${problems.length === 0 ? 'ALL SELECTORS OK' : `${problems.length} PROBLEMS`}`);
for (const p of problems) console.log(` - ${p}`);
process.exit(problems.length === 0 ? 0 : 1);
