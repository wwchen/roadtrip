package ca.floo.roadtrip

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.WaitForSelectorState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Trip-critical smoke. Mirrors the deleted qa/smoke.spec.mjs: cold load →
// /api/pois → Banff popup. Gated on QA_BASE_URL so `gradle test` skips it
// unless a stack is already up. Run via `make qa`.
@EnabledIfEnvironmentVariable(named = "QA_BASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmokeTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private val baseUrl: String = System.getenv("QA_BASE_URL") ?: "http://127.0.0.1:8765"

    @BeforeAll
    fun setUp() {
        playwright = Playwright.create()
        browser =
            playwright.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true),
            )
    }

    @AfterAll
    fun tearDown() {
        browser.close()
        playwright.close()
    }

    @Test
    fun `cold load - api pois - Banff campground popup`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        try {
            // 1. Cold load. Don't wait for NETWORKIDLE — MapLibre keeps
            // fetching tiles forever, so it never settles. `load` (default)
            // is enough; the next step polls __rtState.mapReady directly.
            page.navigate("/")

            // 2. Wait for map to be ready — state.mapReady is set inside the
            // style.load handler in app.js after maplibregl resolves the style.
            try {
                page.waitForFunction(
                    "() => globalThis.__rtState?.mapReady === true",
                    null,
                    Page.WaitForFunctionOptions().setTimeout(15_000.0),
                )
            } catch (e: Exception) {
                val diag =
                    page.evaluate(
                        "() => JSON.stringify({ rt: typeof globalThis.__rtState, " +
                            "rtMap: typeof globalThis.__rtMap, " +
                            "title: document.title, " +
                            "scripts: Array.from(document.scripts).map(s => s.src) })",
                    )
                throw IllegalStateException("mapReady never fired. Page state: $diag", e)
            }

            // 3. Programmatic pan to Banff. Triggers moveend → bbox refresh.
            page.evaluate(
                "() => { globalThis.__rtMap.jumpTo({ center: [-115.55, 51.18], zoom: 13 }); return true; }",
            )

            // 4. Wait for ≥1 campground in the cg source.
            page.waitForFunction(
                "() => (globalThis.__rtState?.overlayData?.cg?.features?.length || 0) > 0",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )

            // 5. Drive search → result click → synthesizeClick (deterministic
            // popup render, dodges pixel-rounding issues from clicking the dot).
            // The Google-Maps-style top bar (web/topbar.js) puts the search input
            // at .tb-row[data-i="0"] .tb-input; pin rows in the dropdown are
            // .tb-result rows whose .tb-kind chip contains "CG" (campgrounds).
            // Mapbox geocoder also surfaces "ADDR" rows for the same query,
            // so filter on the kind chip to avoid clicking an address.
            page.fill(".tb-row[data-i=\"0\"] .tb-input", "tunnel mountain village")
            val pinResult =
                page.locator("#tb-dropdown .tb-result").filter(
                    com.microsoft.playwright.Locator
                        .FilterOptions()
                        .setHas(
                            page.locator(
                                ".tb-kind",
                                com.microsoft.playwright.Page
                                    .LocatorOptions()
                                    .setHasText("CG"),
                            ),
                        ),
                )
            pinResult.first().waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000.0),
            )
            pinResult.first().click()

            // 6. Drawer renders with name + reserve link. (Tunnel Mountain is
            // a Parks Canada pin — no recgov_id — so the drawer skips the
            // availability section and shows reserveButtonHTML's parks_canada
            // branch.)
            val drawer = page.locator("#cg-drawer.open")
            assertThat(drawer).isVisible(
                com.microsoft.playwright.assertions.LocatorAssertions
                    .IsVisibleOptions()
                    .setTimeout(10_000.0),
            )
            // Aspira-PC names this pin "Tunnel Mountain - Village 1" /
            // "...- Village 2" / "...- Trailer Court"; the loose "Tunnel
            // Mountain" prefix is enough to confirm we landed on the
            // right cluster regardless of which sibling sort-orders first.
            assertThat(drawer.locator("h2")).containsText("Tunnel Mountain")
            assertTrue(page.url().contains("poi="), "opening a POI should update the visible URL")

            val reserveBtn = drawer.locator("a.cg-btn-primary")
            assertThat(reserveBtn).isVisible()
            val href = reserveBtn.getAttribute("href") ?: ""
            assertTrue(
                Pattern
                    .compile("(reservation\\.pc\\.gc\\.ca|parks\\.canada\\.ca|recreation\\.gov)")
                    .matcher(href)
                    .find(),
                "reserve href didn't match expected hosts: $href",
            )

            // 7. No JS errors during the run.
            //
            // (Removed the "Verified YYYY-MM-DD" footer check that the old
            // smoke had — Aspira pins are emitted with last_verified=null
            // because /api/maps doesn't carry editorial-touch metadata.
            // Re-add when ETL gains a last_verified source.)
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    fun `mobile layers panel can close after opening`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(390, 844),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        try {
            page.navigate("/")
            page.waitForFunction(
                "() => globalThis.__rtState?.mapReady === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )
            assertThat(page.locator("#panel #status")).hasCount(0)

            page.locator("#panel-toggle").click()
            page.waitForFunction(
                "() => document.getElementById('panel')?.classList.contains('open') === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(5_000.0),
            )
            assertThat(page.locator("#panel-collapse")).isVisible()

            page.locator("#panel-collapse").click()
            page.waitForFunction(
                "() => document.getElementById('panel')?.classList.contains('open') === false",
                null,
                Page.WaitForFunctionOptions().setTimeout(5_000.0),
            )
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during layers panel smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    fun `campground agency filter narrows rendered federal pins`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        context.route("**/api/pois") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "FeatureCollection",
                          "truncated": false,
                          "features": [
                            {
                              "type": "Feature",
                              "id": 8101,
                              "geometry": { "type": "Point", "coordinates": [-123.00, 49.00] },
                              "properties": {
                                "category": "campground",
                                "subcategory": "federal",
                                "agency": "National Park Service"
                              }
                            },
                            {
                              "type": "Feature",
                              "id": 8102,
                              "geometry": { "type": "Point", "coordinates": [-123.02, 49.02] },
                              "properties": {
                                "category": "campground",
                                "subcategory": "federal",
                                "agency": "US Forest Service"
                              }
                            },
                            {
                              "type": "Feature",
                              "id": 8103,
                              "geometry": { "type": "Point", "coordinates": [-123.04, 49.04] },
                              "properties": {
                                "category": "campground",
                                "subcategory": "state",
                                "agency": "WA State Parks"
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
        }

        try {
            page.navigate("/")
            page.waitForFunction(
                "() => globalThis.__rtState?.mapReady === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )
            page.evaluate(
                "() => { globalThis.__rtMap.jumpTo({ center: [-123.02, 49.02], zoom: 10 }); return true; }",
            )
            // Flat legend: one checkbox per agency present in the viewport.
            page.waitForFunction(
                "() => document.querySelectorAll('#cg-agency-legend input[data-cg-agency]').length >= 3",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )

            val nps = page.locator("""#cg-agency-legend input[data-cg-agency="National Park Service"]""")
            val forestService =
                page.locator("""#cg-agency-legend input[data-cg-agency="US Forest Service"]""")
            val stateParks = page.locator("""#cg-agency-legend input[data-cg-agency="WA State Parks"]""")
            // Every agency present defaults to shown (checked).
            assertTrue(nps.isChecked())
            assertTrue(forestService.isChecked())
            assertTrue(stateParks.isChecked())

            // Un-checking one agency hides only its pins from the map.
            forestService.uncheck()
            assertFalse(forestService.isChecked())
            assertTrue(nps.isChecked())
            assertTrue(stateParks.isChecked())
            page.waitForFunction(
                """
                () => {
                  const map = globalThis.__rtMap;
                  const canvas = map?.getCanvas?.();
                  if (!map || !canvas || !map.getLayer('cg-points')) return false;
                  const agencies = map
                    .queryRenderedFeatures([[0, 0], [canvas.width, canvas.height]], { layers: ['cg-points'] })
                    .map(f => f.properties.agency)
                    .filter(Boolean)
                    .sort();
                  return JSON.stringify(agencies) === JSON.stringify(['National Park Service', 'WA State Parks']);
                }
                """.trimIndent(),
                null,
                Page.WaitForFunctionOptions().setTimeout(5_000.0),
            )

            // The un-check persists across a re-render (route-scoped repaint),
            // even though the agency is still present in the data.
            page.evaluate(
                """
                () => {
                  globalThis.__rtSetRoutePois([
                    {
                      type: 'Feature',
                      id: 8101,
                      geometry: { type: 'Point', coordinates: [-123.00, 49.00] },
                      properties: { category: 'campground', agency: 'National Park Service' }
                    },
                    {
                      type: 'Feature',
                      id: 8102,
                      geometry: { type: 'Point', coordinates: [-123.02, 49.02] },
                      properties: { category: 'campground', agency: 'US Forest Service' }
                    }
                  ]);
                  return true;
                }
                """.trimIndent(),
            )
            page.waitForFunction(
                "() => document.querySelectorAll('#cg-agency-legend input[data-cg-agency]').length >= 2",
                null,
                Page.WaitForFunctionOptions().setTimeout(5_000.0),
            )
            assertTrue(nps.isChecked())
            assertFalse(forestService.isChecked())

            // Re-checking the agency shows its pins again.
            forestService.check()
            assertTrue(forestService.isChecked())
            page.waitForFunction(
                """
                () => {
                  const map = globalThis.__rtMap;
                  const canvas = map?.getCanvas?.();
                  if (!map || !canvas || !map.getLayer('cg-points')) return false;
                  const agencies = map
                    .queryRenderedFeatures([[0, 0], [canvas.width, canvas.height]], { layers: ['cg-points'] })
                    .map(f => f.properties.agency)
                    .filter(Boolean)
                    .sort();
                  return JSON.stringify(agencies) === JSON.stringify(['National Park Service', 'US Forest Service']);
                }
                """.trimIndent(),
                null,
                Page.WaitForFunctionOptions().setTimeout(5_000.0),
            )
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during agency filter smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    fun `poi share link opens drawer by id`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        context.route("**/api/pois") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"type":"FeatureCollection","features":[],"truncated":false}"""),
            )
        }
        context.route("**/api/pois/4242") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "Feature",
                          "id": 4242,
                          "geometry": { "type": "Point", "coordinates": [-122.31, 47.62] },
                          "properties": {
                            "category": "planet-fitness",
                            "name": "Shared Gym",
                            "address": { "street": "123 Test Way", "city": "Seattle", "state": "WA", "postcode": "98101" }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
        }

        try {
            page.navigate("/?poi=4242")
            val drawer = page.locator("#cg-drawer.open")
            assertThat(drawer).isVisible(
                com.microsoft.playwright.assertions.LocatorAssertions
                    .IsVisibleOptions()
                    .setTimeout(15_000.0),
            )
            assertThat(drawer.locator("h2")).containsText("Shared Gym")
            assertThat(drawer.locator(".rt-poi-share")).hasCount(0)
            assertTrue(page.url().contains("poi=4242"), "shared POI should be represented by the visible URL")
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during POI share smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    fun `campground drawer surfaces promoted poi dto fields`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        context.route("**/api/pois") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"type":"FeatureCollection","features":[],"truncated":false}"""),
            )
        }
        context.route("**/api/pois/45626") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "Feature",
                          "id": 45626,
                          "geometry": { "type": "Point", "coordinates": [-122.8127778, 39.00722222] },
                          "properties": {
                            "source": "reservecalifornia-campgrounds",
                            "source_id": "rc-629",
                            "category": "campground",
                            "subcategory": "state",
                            "agency": "California State Parks",
                            "name": "Clear Lake SP Cabins",
                            "region": "CA",
                            "country": "US",
                            "detail": {
                              "availability_supported": true,
                              "description": "Clear Lake State Park offers rental cabins near the lake.",
                              "photo_url": "https://cali-content.usedirect.com/Images/California/ParkImages/Place/629.jpg",
                              "cta": [
                                {
                                  "url": "https://reservecalifornia.com/park/629",
                                  "label": "Reserve on ReserveCalifornia",
                                  "kind": "reserve"
                                }
                              ],
                              "provider_ref": { "place_id": 629, "facility_ids": [889] },
                              "raw": {
                                "amenities": ["Restrooms", "Showers"],
                                "activities": ["Fishing", "Hiking"],
                                "upstream": {
                                  "Name": "Clear Lake SP Cabins",
                                  "FacilityDescription": "<p>Raw-only description should not render.</p>",
                                  "MEDIA": [
                                    {
                                      "URL": "https://example.test/raw-only.jpg",
                                      "IsPrimary": true
                                    }
                                  ]
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
        }
        try {
            page.navigate("/?poi=45626")
            val drawer = page.locator("#cg-drawer.open")
            assertThat(drawer).isVisible(
                com.microsoft.playwright.assertions.LocatorAssertions
                    .IsVisibleOptions()
                    .setTimeout(15_000.0),
            )
            assertThat(drawer.locator("h2")).containsText("Clear Lake SP Cabins")
            assertThat(drawer.locator("h2 + .cg-agency-subtitle")).containsText("California State Parks")
            assertThat(drawer.locator(".cg-about")).containsText("Clear Lake State Park offers rental cabins")
            assertFalse(
                drawer.locator(".cg-about").textContent().contains("Raw-only description"),
                "drawer About should render DTO description, not raw upstream description",
            )
            val details = drawer.locator(".cg-details")
            assertThat(details).hasCount(1)
            assertThat(details).containsText("Source metadata")
            assertThat(details).containsText("rc-629")
            assertFalse(
                details.textContent().contains("Raw-only description"),
                "drawer details should not render raw upstream description",
            )
            assertFalse(
                details.textContent().contains("raw-only.jpg"),
                "drawer details should not render raw upstream media",
            )
            val heroImage =
                page.evaluate(
                    """
                    () => getComputedStyle(document.querySelector('.cg-hero')).backgroundImage
                    """.trimIndent(),
                ) as String
            assertTrue(heroImage.contains("/Place/629.jpg"), "drawer should use DTO photo_url as hero image")
            assertFalse(heroImage.contains("raw-only.jpg"), "drawer hero should render DTO photo_url, not raw upstream media")
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during promoted POI DTO smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `route share link restores stops and route`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val routeCalls = AtomicInteger(0)
        val onRoutePoiCalls = AtomicInteger(0)
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        context.route("**/api/pois") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"type":"FeatureCollection","features":[],"truncated":false}"""),
            )
        }
        context.route("**/api/route?**") { route: Route ->
            routeCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "FeatureCollection",
                          "features": [{
                            "type": "Feature",
                            "geometry": {
                              "type": "LineString",
                              "coordinates": [[-123.12, 49.28], [-122.33, 47.61]]
                            },
                            "properties": {
                              "distance_m": 230000,
                              "duration_s": 9000,
                              "legs": [{ "distance_m": 230000, "duration_s": 9000 }]
                            }
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/pois/on-route") { route: Route ->
            onRoutePoiCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"type":"FeatureCollection","features":[]}"""),
            )
        }

        val routeParam =
            "eyJ2IjoxLCJyYWRpdXNfbWlsZXMiOjUsInN0b3BzIjpbeyJuYW1lIjoiVmFuY291dmVyIiwibG5nIjotMTIzLjEyLCJsYXQiOjQ5LjI4LCJraW5kIjoiUExBQ0UifSx7Im5hbWUiOiJTZWF0dGxlIiwibG5nIjotMTIyLjMzLCJsYXQiOjQ3LjYxLCJraW5kIjoiUExBQ0UifV19"

        try {
            page.navigate("/?route=$routeParam")
            page.waitForFunction(
                "() => globalThis.__rtRouteActive?.() === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )
            assertThat(page.locator(".tb-row[data-i=\"0\"] .tb-input")).hasValue("Vancouver")
            assertThat(page.locator(".tb-row[data-i=\"1\"] .tb-input")).hasValue("Seattle")
            assertThat(page.locator("#tb-corridor-value")).containsText("5 mi")
            assertThat(page.locator("#tb-share-route")).hasCount(0)
            assertThat(page.locator("#tb-actions #tb-route-summary")).containsText("230 km")
            assertThat(page.locator("#tb-actions #tb-route-summary")).containsText("2h 30m")
            assertThat(page.locator("#tb-results .tb-results-body #tb-corridor")).isVisible()
            assertThat(page.locator("#tb-results .tb-results-body #tb-trip-dates")).hasCount(0)
            page.locator("#tb-results .tb-results-head").click()
            assertThat(page.locator("#tb-results .tb-results-body")).isHidden()
            assertTrue(page.url().contains("route="), "shared route should be represented by the visible URL")
            val copiedRouteUrl = page.evaluate("() => globalThis.__rtRouteShareUrl?.() || ''").toString()
            assertTrue(copiedRouteUrl.contains("route="), "route share URL should include route param")
            assertEquals(1, routeCalls.get(), "shared route should fetch /api/route once")
            page.waitForTimeout(750.0)
            assertTrue(onRoutePoiCalls.get() >= 1, "shared route should fetch /api/pois/on-route")
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during route share smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `route mode uses current location and paints only on-route pois`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val viewportPoiCalls = AtomicInteger(0)
        val onRoutePoiCalls = AtomicInteger(0)
        val routeCalls = AtomicInteger(0)
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        context.route("**/api/pois") { route: Route ->
            viewportPoiCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "FeatureCollection",
                          "features": [{
                            "type": "Feature",
                            "id": 7,
                            "geometry": { "type": "Point", "coordinates": [-90.0, 40.0] },
                            "properties": { "category": "supercharger" }
                          }],
                          "truncated": false
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/route?**") { route: Route ->
            routeCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "FeatureCollection",
                          "features": [{
                            "type": "Feature",
                            "geometry": {
                              "type": "LineString",
                              "coordinates": [[-122.33, 47.61], [-121.50, 48.10]]
                            },
                            "properties": {
                              "distance_m": 100000,
                              "duration_s": 7200,
                              "legs": [{ "distance_m": 100000, "duration_s": 7200 }]
                            }
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/pois/on-route") { route: Route ->
            onRoutePoiCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "FeatureCollection",
                          "features": [{
                            "type": "Feature",
                            "id": 999,
                            "geometry": { "type": "Point", "coordinates": [-122.0, 47.8] },
                            "properties": {
                              "category": "campground",
                              "subcategory": "federal"
                            }
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/pois/999") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "Feature",
                          "id": 999,
                          "geometry": { "type": "Point", "coordinates": [-122.0, 47.8] },
                          "properties": {
                            "category": "campground",
                            "subcategory": "federal",
                            "name": "On-route Campground",
                            "region": "WA"
                          }
                        }
                        """.trimIndent(),
                    ),
            )
        }

        try {
            page.navigate("/")
            page.waitForFunction(
                "() => globalThis.__rtState?.mapReady === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )

            page.evaluate(
                "() => globalThis.__rtUseCurrentLocationForTripStop(0, { lng: -122.33, lat: 47.61 })",
            )
            assertThat(page.locator(".tb-row[data-i=\"0\"] .tb-input")).hasValue("Current location")

            page.locator("#tb-directions").click()
            page.waitForSelector(".tb-row[data-i=\"1\"] .tb-input")
            assertThat(page.locator("#tb-corridor-range")).hasValue("5")
            assertThat(page.locator("#tb-corridor-value")).containsText("5 mi")
            page.evaluate(
                "() => globalThis.__rtAddTripStop({ name: 'Route Destination', lng: -121.5, lat: 48.1, kind: 'PLACE' })",
            )

            page.waitForFunction(
                "() => globalThis.__rtRouteActive?.() === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )
            assertTrue(page.url().contains("route="), "active route should update the visible URL")
            page.waitForFunction(
                "() => globalThis.__rtState?.overlayData?.cg?.features?.[0]?.id === 999",
                null,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )
            val firstCard = page.locator(".tb-card").first()
            assertThat(firstCard.locator(".tb-card-head")).containsText("On-route Campground")
            assertThat(firstCard.locator(".tb-card-location")).containsText("WA")

            val viewportCallsAfterRoute = viewportPoiCalls.get()
            page.evaluate(
                "() => { globalThis.__rtMap.jumpTo({ center: [-120.5, 48.0], zoom: 10 }); return true; }",
            )
            page.waitForTimeout(750.0)

            assertEquals(1, routeCalls.get(), "route should be fetched once")
            assertTrue(onRoutePoiCalls.get() >= 1, "route mode should fetch /api/pois/on-route")
            assertEquals(
                viewportCallsAfterRoute,
                viewportPoiCalls.get(),
                "viewport /api/pois should not refetch while a route is active",
            )
            assertEquals(
                "999",
                page.evaluate("() => String(globalThis.__rtState.overlayData.cg.features[0].id)"),
            )
            assertEquals(
                "0",
                page.evaluate("() => String(globalThis.__rtState.overlayData.sc.features.length)"),
                "route-scoped map paint should clear viewport supercharger POIs",
            )
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during route smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    // The migrated React pages. Until now this suite only ever navigated `/` and its
    // query variants — all the vanilla map — so neither page React owns had any
    // browser-level coverage at all. Three purely visual bugs reached review during
    // the migration (uncoloured error text, an input that dropped every keystroke
    // after the first, and a 0x0 map canvas); every one passed tsc, the unit suite,
    // the bundle and the colour-token check, because none of those can see a page
    // that renders wrongly or not at all.
    //
    // The assertions are deliberately shallow: the page's own heading, plus a
    // non-empty #root. A non-empty root is the real signal — it means the hashed
    // bundle resolved, parsed and mounted. A 404 on `/assets/*`, a broken entry, or a
    // crash during mount all leave it empty, and those are the failures the strangler
    // seam can actually produce.
    //
    // Nothing here depends on being signed in. Both headings render outside the
    // signed-out branch, so this passes whether or not the smoke stack has a session.
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun `migrated pages mount and render`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        // path to the heading that page always renders, signed in or not.
        val migrated =
            listOf(
                "/watches" to "Watches",
                "/availability" to "Availability Dashboard",
            )

        try {
            for ((path, heading) in migrated) {
                page.navigate(path)

                assertThat(page.locator("h1")).hasText(heading)

                val rootHtml = page.locator("#root").innerHTML()
                assertTrue(
                    rootHtml.isNotBlank(),
                    "$path mounted nothing into #root — the bundle did not load or threw during mount",
                )
            }

            // The dashboard's tabs are its whole navigation, and they are plain
            // anchors precisely so they stay linkable.
            page.navigate("/availability")
            for (tab in listOf("Pollers", "Runs", "Changes")) {
                val link = page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(tab))
                assertThat(link).isVisible()
            }

            assertTrue(
                pageErrors.isEmpty(),
                "Page errors on the migrated pages: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    // The map container must have real dimensions. MapLibre sizes its WebGL canvas
    // from the container, so an unsized one yields a 0x0 canvas: a map that
    // initialises without error, passes every unit test, and draws nothing. Phase 4a
    // hit exactly that in the React provider, and `/` moves to React in 4b — so this
    // guards the vanilla container now and the React one the moment it takes over.
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun `the map container has non-zero dimensions`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()

        try {
            page.navigate("/")
            page.waitForSelector("#map")

            val box = page.locator("#map").boundingBox()
            assertTrue(box != null, "#map has no bounding box at all")
            assertTrue(box.width > 0, "#map has zero width — the canvas would render nothing")
            assertTrue(box.height > 0, "#map has zero height — the canvas would render nothing")
        } finally {
            page.close()
            context.close()
        }
    }
}
