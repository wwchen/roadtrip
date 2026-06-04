import { test, expect } from '@playwright/test';

// Trip-critical smoke. Runs against a live stack (server.py on 8765 +
// Kotlin backend on 8080 + Postgres with imported data). The goal is to
// catch regressions in the moveend → /api/pois → popup chain BEFORE deploy
// — the path that breaks if anyone touches the bbox plumbing or the popup
// flatten contract. NOT a full E2E suite.

test('cold load → /api/pois → Banff campground popup', async ({ page }) => {
  // Capture page errors so a silent module load failure (the WebGL bug we
  // hit in the headless browse env) shows up as a clean test failure
  // instead of a timeout.
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 1. Cold load. baseURL is set in playwright.config.mjs.
  await page.goto('/');

  // 2. Wait for map to be ready — `state.mapReady` is set inside the
  // `style.load` handler in app.js (after maplibregl.Map's style first
  // resolves). This is the one signal that says "layers can be installed."
  await page.waitForFunction(() => {
    return globalThis.__rtState?.mapReady === true;
  }, null, { timeout: 15_000 }).catch(async () => {
    // Fall back to digging state out via the module — set up by app.js
    // when QA_HOOK is true. Bail with a useful error if the hook is missing.
    throw new Error('window.__rtState never became ready. Is the QA hook installed?');
  });

  // 3. Programmatic pan to Banff. Fitting Tunnel Mountain in the viewport
  // also triggers a moveend, which fires the bbox refresh.
  await page.evaluate(() => {
    globalThis.__rtMap.flyTo({ center: [-115.55, 51.18], zoom: 13, animate: false });
  });

  // 4. Wait for the bbox response with ≥1 campground. We assert via the
  // installed source — `state.overlayData.cg` is the FeatureCollection
  // app.js stuffs into the cg source on every refreshBbox.
  await expect.poll(() => page.evaluate(() => {
    const fc = globalThis.__rtState?.overlayData?.cg;
    return (fc && fc.features && fc.features.length) || 0;
  }), { timeout: 15_000, message: 'no campgrounds returned for Banff bbox' }).toBeGreaterThan(0);

  // 5. Click a known Banff campground via search — search-result click
  // fires `synthesizeClick` which renders the popup deterministically,
  // dodging any lat/lng → pixel rounding issues from clicking the dot.
  // Tunnel Mountain Village I is in the parks-canada source with a
  // reserve link, so the popup verdict should include "Reserve on
  // parks.canada.ca".
  await page.fill('#search', 'tunnel mountain village');
  // Wait for results to populate (search index needs the bbox refresh to
  // have registered Banff items first).
  await expect.poll(async () => {
    return await page.locator('.sr-item').count();
  }, { timeout: 5_000, message: 'no search results for Tunnel Mountain' }).toBeGreaterThan(0);
  // Pick the first Tunnel Mountain Village result (parks-canada source
  // sorts after uscampgrounds in append-only insert order, but either
  // works for this assertion since both have a reserve link).
  await page.locator('.sr-item').first().click();

  // 6. Popup renders with name + reserve link.
  const popup = page.locator('.maplibregl-popup-content .popup');
  await expect(popup).toBeVisible({ timeout: 10_000 });
  await expect(popup.locator('h3')).toContainText('Tunnel Mountain Village');
  // The btn-primary is the Reserve link. uscampgrounds + parks-canada
  // dedup quirk: whichever source the search picked, both have a
  // reservation URL. Accept any of the canonical reserve hosts.
  const reserveBtn = popup.locator('a.btn-primary');
  await expect(reserveBtn).toBeVisible();
  const href = await reserveBtn.getAttribute('href');
  expect(href).toMatch(/(reservation\.pc\.gc\.ca|parks\.canada\.ca|recreation\.gov)/);

  // 7. last_verified footer renders. The curated AB/BC files all carry a
  // last_verified date and popups.js renders it as `Verified <date>`. If the
  // field gets dropped from data or the JSONB round-trip, this regresses.
  await expect(popup.locator('.footer')).toContainText(/Verified \d{4}-\d{2}-\d{2}/);

  // 8. No JS errors during the run.
  expect(pageErrors, `Page errors during smoke: ${pageErrors.join(' | ')}`).toHaveLength(0);
});
