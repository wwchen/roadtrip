package ca.floo.roadtrip

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
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
                              "subcategory": "federal",
                              "route_km": 25.0
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
            page.evaluate(
                "() => globalThis.__rtAddTripStop({ name: 'Route Destination', lng: -121.5, lat: 48.1, kind: 'PLACE' })",
            )

            page.waitForFunction(
                "() => globalThis.__rtRouteActive?.() === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )
            page.waitForFunction(
                "() => globalThis.__rtState?.overlayData?.cg?.features?.[0]?.id === 999",
                null,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )

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
}
