package ca.floo.roadtrip

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
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
// /api/pois → Banff drawer. Gated on QA_BASE_URL so `gradle test` skips it
// unless a stack is already up. Run via `make qa`.
//
// **`/` is the React app.** Two kinds of selector appear below, and which one a
// step uses is deliberate:
//
//   the topbar's `tb-*` ids and classes, which the React components reproduce
//       verbatim (see the notes in `StopRow.tsx` and `TopBar.tsx`) — the topbar is
//       the one surface whose DOM contract was worth preserving, because these
//       assertions are what proves the rewritten planner behaves;
//   roles and accessible names for everything React reshaped — the drawer
//       (`aside.rt-drawer`, `role=dialog`) and the legend (`.rt-legend*`). Where the
//       vanilla's hooks were ids on hand-built DOM (`#cg-drawer`, `#panel`,
//       `#cg-agency-legend input[data-cg-agency]`), the port has real roles and
//       names, so the smoke asks for those rather than pinning class names that only
//       exist for CSS.
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

    /**
     * The POI drawer, once it has finished sliding in.
     *
     * The vanilla's `#cg-drawer.open` has no counterpart: React's drawer
     * (`features/drawer/Drawer.tsx`) unmounts when it closes rather than keeping one
     * element alive and toggling a class, so "open" is a dialog being present at all.
     * `--open` is still in the selector because that class lands a frame later, and an
     * assertion that fires mid-animation reads a half-positioned panel.
     */
    private fun drawerOf(page: Page): Locator = page.locator("aside.rt-drawer.rt-drawer--open[role='dialog']")

    /**
     * A legend agency row's clickable control.
     *
     * The label, not the checkbox: LDS renders the real input at `opacity: 0` with no
     * size and `pointer-events: none` and draws `.lds-check__box` in its place, so the
     * input can be READ but never clicked. Verified in Chromium — clicking it times out
     * with "element is not visible".
     *
     * Matched on the label's text, which also carries the count ("US Forest Service
     * (1)"), hence a substring match.
     */
    private fun agencyRow(
        page: Page,
        agency: String,
    ): Locator =
        page
            .locator(".rt-legend__agencies label")
            .filter(Locator.FilterOptions().setHasText(Pattern.compile(agency)))

    /** The same row's checkbox, which is what reports the filter's state. */
    private fun agencyBox(
        page: Page,
        agency: String,
    ): Locator = agencyRow(page, agency).locator("input[type='checkbox']")

    @Test
    fun `cold load - api pois - Banff campground drawer`() {
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
            // fetching tiles forever, so it never settles.
            page.navigate("/")

            // 2. Drive search → result click, rather than clicking the dot on the
            // map: a picked POI result opens its drawer by id (`useTripPlanner`'s
            // `pickResult`), which dodges the pixel-rounding of hitting a 6px circle.
            // The topbar keeps the vanilla's DOM: the search input is
            // `.tb-row[data-i="0"] .tb-input`, and dropdown rows are `.tb-result`
            // whose `.tb-kind` chip says what they are. The geocoder also answers
            // "ADDR" rows for this query, so filter on the chip to avoid picking an
            // address.
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

            // 3. Drawer renders with name + reserve link. (Tunnel Mountain is
            // a Parks Canada pin — no recgov_id — so the drawer skips the
            // availability grid and shows the provider CTA the backend promoted.)
            val drawer = drawerOf(page)
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

            // The drawer inherited the vanilla app's very high stacking level, but
            // search now exists only in the topbar. A detail view must not trap the
            // primary search/navigation surface on desktop.
            val topbar = page.locator("#topbar")
            assertThat(topbar).isVisible()
            assertThat(topbar.locator(".tb-input").first()).isVisible()
            val topbarZ = page.evaluate("() => Number(getComputedStyle(document.querySelector('#topbar')).zIndex)") as Number
            val drawerZ = page.evaluate("() => Number(getComputedStyle(document.querySelector('.rt-drawer')).zIndex)") as Number
            assertTrue(topbarZ.toInt() > drawerZ.toInt(), "desktop drawer should remain below the topbar")

            // The CTAs are LDS buttons rendered as anchors, in the actions row —
            // `a.cg-btn-primary` was the vanilla's hand-built equivalent.
            val reserveBtn = drawer.locator(".rt-poi-actions a[href]").first()
            assertThat(reserveBtn).isVisible()
            val href = reserveBtn.getAttribute("href") ?: ""
            assertTrue(
                Pattern
                    .compile("(reservation\\.pc\\.gc\\.ca|parks\\.canada\\.ca|recreation\\.gov)")
                    .matcher(href)
                    .find(),
                "reserve href didn't match expected hosts: $href",
            )

            // 4. No JS errors during the run.
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

    // The legend is a sheet on a phone and a panel on a desktop, and one button means
    // "close" in the first case and "collapse" in the second (`LegendPanel.hide`). What
    // this pins is the phone half: a sheet that opens and cannot be closed again covers
    // the map for the rest of the session.
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
            // The legend's search box is deliberately absent: it filtered a client-side
            // index nothing has populated since `/api/pois` went slim, so it could not
            // return a result. Cross-viewport search is the topbar's, above.
            assertThat(page.locator(".rt-legend input[type='search']")).hasCount(0)

            page.getByLabel("Toggle layers panel").click()
            page.waitForFunction(
                "() => document.querySelector('.rt-legend')?.classList.contains('rt-legend--open') === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(PANEL_TIMEOUT_MS),
            )
            val close = page.getByLabel("Hide layers panel")
            assertThat(close).isVisible()

            close.click()
            page.waitForFunction(
                "() => document.querySelector('.rt-legend')?.classList.contains('rt-legend--open') === false",
                null,
                Page.WaitForFunctionOptions().setTimeout(PANEL_TIMEOUT_MS),
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
    fun `campground agency controls MapLibre layer filter`() {
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
            // Flat legend: one checkbox per agency present in the viewport.
            page.waitForFunction(
                "() => document.querySelectorAll('.rt-legend__agencies input[type=\"checkbox\"]').length >= 3",
                null,
                Page.WaitForFunctionOptions().setTimeout(MAP_READY_TIMEOUT_MS),
            )

            // By label text, not by a `data-cg-agency` attribute: the React rows are
            // real labelled checkboxes, and an agency name is not a safe source for an
            // element id (see `LegendPanel.AgencyRow`).
            val nps = agencyBox(page, "National Park Service")
            val forestService = agencyBox(page, "US Forest Service")
            val stateParks = agencyBox(page, "WA State Parks")
            // Every agency present defaults to shown (checked).
            assertTrue(nps.isChecked())
            assertTrue(forestService.isChecked())
            assertTrue(stateParks.isChecked())

            // Un-checking one agency updates the user-visible filter state. The
            // imperative MapLibre filter expression is covered by the map unit suite;
            // this browser test stays on the public UI contract.
            agencyRow(page, "US Forest Service").click()
            assertFalse(forestService.isChecked())
            assertTrue(nps.isChecked())
            assertTrue(stateParks.isChecked())

            // A real map interaction causes a viewport refresh; the agency choice
            // must survive the response and legend rebuild.
            page.locator(".maplibregl-ctrl-zoom-in").click()
            page.waitForFunction(
                "() => document.querySelectorAll('.rt-legend__agencies input[type=\"checkbox\"]').length >= 3",
                null,
                Page.WaitForFunctionOptions().setTimeout(PANEL_TIMEOUT_MS),
            )
            assertTrue(nps.isChecked())
            assertFalse(forestService.isChecked())

            // Re-checking the agency removes both layer filters again.
            agencyRow(page, "US Forest Service").click()
            assertTrue(forestService.isChecked())
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
    fun `viewport POI is rendered and clickable`() {
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
                          "features": [{
                            "type": "Feature",
                            "id": 9001,
                            "geometry": { "type": "Point", "coordinates": [-98.5, 39.5] },
                            "properties": { "category": "planet_fitness_location" }
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/pois/9001") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "Feature",
                          "id": 9001,
                          "geometry": { "type": "Point", "coordinates": [-98.5, 39.5] },
                          "properties": {
                            "category": "planet-fitness",
                            "name": "Rendered Gym"
                          }
                        }
                        """.trimIndent(),
                    ),
            )
        }

        try {
            page.navigate("/")
            page.waitForFunction(
                "() => document.querySelector('.rt-legend')?.textContent?.includes('Planet Fitness (1)')",
                null,
                Page.WaitForFunctionOptions().setTimeout(MAP_READY_TIMEOUT_MS),
            )

            // The fixture is exactly at the map's initial center. Clicking the
            // canvas center proves the worker processed the GeoJSON and MapLibre
            // painted a pickable feature; a legend count alone proves only fetch.
            val canvas = page.locator(".maplibregl-canvas").boundingBox()
            assertTrue(canvas != null, "the MapLibre canvas has no bounding box")
            page.mouse().click(canvas.x + canvas.width / 2, canvas.y + canvas.height / 2)

            val drawer = drawerOf(page)
            assertThat(drawer).isVisible(
                com.microsoft.playwright.assertions.LocatorAssertions
                    .IsVisibleOptions()
                    .setTimeout(MAP_READY_TIMEOUT_MS),
            )
            assertThat(drawer.locator("h2")).containsText("Rendered Gym")
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors while rendering a viewport POI: ${pageErrors.joinToString(" | ")}",
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
            val drawer = drawerOf(page)
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
            val drawer = drawerOf(page)
            assertThat(drawer).isVisible(
                com.microsoft.playwright.assertions.LocatorAssertions
                    .IsVisibleOptions()
                    .setTimeout(15_000.0),
            )
            assertThat(drawer.locator("h2")).containsText("Clear Lake SP Cabins")
            // The agency shares the eyebrow with the type — "Campground · <agency>" —
            // which is what identifies the place before its name locates it.
            assertThat(drawer.locator(".rt-poi-eyebrow")).containsText("California State Parks")
            // The provider description is prose, so it reads in "Good to know" rather
            // than in a section named after the field it came from.
            val about = drawer.locator(".rt-poi-slot--goodToKnow")
            assertThat(about).containsText("Clear Lake State Park offers rental cabins")
            assertFalse(
                about.textContent().contains("Raw-only description"),
                "drawer prose should render DTO description, not raw upstream description",
            )
            // Provenance is what is left once everything that is trip content has been
            // promoted into a block above it — collapsed, and the last thing on the page.
            val details = drawer.locator(".rt-poi-provenance")
            assertThat(details).hasCount(1)
            assertThat(details).containsText("Source metadata")
            assertThat(details).containsText("rc-629")
            assertFalse(
                details.textContent().contains("Raw-only description"),
                "provenance should not render raw upstream description",
            )
            assertFalse(
                details.textContent().contains("raw-only.jpg"),
                "provenance should not render raw upstream media",
            )
            val heroImage =
                page.evaluate(
                    """
                    () => getComputedStyle(document.querySelector('.rt-poi-hero')).backgroundImage
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
    fun `route mode uses current location and shows only on-route pois`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800)
                    .setPermissions(listOf("geolocation"))
                    .setGeolocation(47.61, -122.33),
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
        context.route("**/api/geocode?**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "results": [{
                            "id": "route-destination",
                            "place_name": "Route Destination",
                            "place_type": "place",
                            "lng": -121.5,
                            "lat": 48.1
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/pois/search?**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"results":[]}"""),
            )
        }

        try {
            page.navigate("/")
            page.getByLabel("Use current location").click()
            assertThat(page.locator(".tb-row[data-i=\"0\"] .tb-input")).hasValue("Current location")

            page.locator("#tb-directions").click()
            page.waitForSelector(".tb-row[data-i=\"1\"] .tb-input")
            // The corridor slider is NOT here yet, and that is the port's doing: it
            // lives inside the results list now, which appears with a live route,
            // because without one there is nothing for a radius to be a radius of.
            // The vanilla built the row up front and hid it, so this assertion used to
            // pass against a `display: none` control.
            assertThat(page.locator("#tb-corridor-range")).hasCount(0)
            val destination = page.locator(".tb-row[data-i=\"1\"] .tb-input")
            destination.fill("route destination")
            page
                .getByRole(AriaRole.OPTION)
                .filter(
                    Locator.FilterOptions().setHasText("Route Destination"),
                ).click()

            assertThat(page.locator("#tb-route-summary")).containsText("100 km")
            assertTrue(page.url().contains("route="), "active route should update the visible URL")
            // Now that the list is up, so is its radius control, at its default.
            assertThat(page.locator("#tb-corridor-range")).hasValue("5")
            assertThat(page.locator("#tb-corridor-value")).containsText("5 mi")
            // The card lands with the pin's fallback name and takes its real one from
            // its own hydration, so this is a retrying assertion by necessity.
            val firstCard = page.locator(".tb-card").first()
            assertThat(firstCard.locator(".tb-card-head")).containsText("On-route Campground")
            assertThat(firstCard.locator(".tb-card-location")).containsText("WA")

            val viewportCallsAfterRoute = viewportPoiCalls.get()
            page.locator(".maplibregl-ctrl-zoom-in").click()
            page.waitForTimeout(750.0)

            assertEquals(1, routeCalls.get(), "route should be fetched once")
            assertTrue(onRoutePoiCalls.get() >= 1, "route mode should fetch /api/pois/on-route")
            assertEquals(
                viewportCallsAfterRoute,
                viewportPoiCalls.get(),
                "viewport /api/pois should not refetch while a route is active",
            )
            // LDS keeps the native checkbox in the accessibility tree but makes it
            // visually hidden; the wrapping label is the control a person sees and
            // clicks. Assert that public surface, including the route-scoped count.
            assertThat(
                page
                    .locator(".rt-legend label")
                    .filter(Locator.FilterOptions().setHasText(Pattern.compile("Superchargers \\(0\\)"))),
            ).isVisible()
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during route smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    // Every production React page. Three
    // purely visual bugs reached review during the migration (uncoloured error text, an
    // input that dropped every keystroke after the first, and a 0x0 map canvas); every
    // one passed tsc, the unit suite, the bundle and the colour-token check, because
    // none of those can see a page that renders wrongly or not at all.
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
                // The map's only heading is the legend's title. It renders whether or
                // not the style ever loads, which is what makes it the right probe
                // here: this test is about the bundle mounting, not about MapLibre.
                "/" to "Roadtrip Map",
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
    // hit exactly that in the React provider, and 4b hit it again through the shell's
    // body padding — neither is visible to tsc, to jsdom or to the bundle.
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
            page.waitForSelector(".rt-map-canvas")

            val box = page.locator(".rt-map-canvas").boundingBox()
            assertTrue(box != null, "the map container has no bounding box at all")
            assertTrue(box.width > 0, "the map container has zero width — the canvas would render nothing")
            assertTrue(box.height > 0, "the map container has zero height — the canvas would render nothing")
        } finally {
            page.close()
            context.close()
        }
    }

    private companion object {
        /** A cold load has to fetch the bundle, the style and its first tiles. */
        const val MAP_READY_TIMEOUT_MS = 15_000.0

        /** A panel toggle is a local state change: it is either immediate or broken. */
        const val PANEL_TIMEOUT_MS = 5_000.0
    }
}
