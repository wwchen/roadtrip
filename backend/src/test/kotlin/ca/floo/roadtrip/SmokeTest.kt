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
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `poi catalog links to poi reservables and reservable rows expand details and availability`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        val poiReservableCalls = AtomicInteger(0)
        val globalReservableCalls = AtomicInteger(0)
        page.onPageError { pageErrors.add(it) }

        context.route("**/api/pois/search?**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "results": [{
                            "id": 31337,
                            "name": "Tunnel Mountain Village II",
                            "category": "campground",
                            "region": "AB",
                            "lng": -115.55,
                            "lat": 51.18
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/reservables**") { route: Route ->
            globalReservableCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"total":0,"limit":100,"offset":0,"reservables":[]}"""),
            )
        }
        context.route("**/api/poi/31337/reservables**") { route: Route ->
            poiReservableCalls.incrementAndGet()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "poi_id": 31337,
                          "type": "site",
                          "total_at_poi": 2,
                          "reservables": [
                            {
                              "rid": "site:matrix:001",
                              "type": "site",
                              "vendor": "matrix",
                              "vendor_id": "001",
                              "name": "Site 1",
                              "loop": "A",
                              "site_type": "Tent",
                              "poi_ids": [31337],
                              "reservation_url_template": "https://example.test/book?site=1&start={start_date}&end={end_date}&nights={nights}",
                              "tags": {
                                "capacity": { "max": 8 },
                                "equipment": ["Small Tent"],
                                "attributes": {
                                  "ground_cover": "Soil",
                                  "firepit_on_site": "Yes"
                                }
                              },
                              "raw": {
                                "max_num_people": 6,
                                "defined_attributes": [
                                  { "name": "Shade", "value": "Partial" }
                                ]
                              }
                            },
                            {
                              "rid": "site:matrix:002",
                              "type": "site",
                              "vendor": "matrix",
                              "vendor_id": "002",
                              "name": "Site 2",
                              "loop": "B",
                              "site_type": "RV",
                              "poi_ids": [31337],
                              "reservation_url_template": "https://example.test/2"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/reservable/site%3Amatrix%3A001/availability?**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "state": "success",
                          "summary": "No availability data",
                          "provider": "aspira",
                          "availability": [
                            { "date": "2026-06-16", "available_count": 1, "total": 1, "available_reservable_ids": ["site:matrix:001"] },
                            { "date": "2026-06-17", "available_count": 0, "total": 1, "available_reservable_ids": [] }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
        }

        try {
            page.navigate("/pois?q=tunnel&limit=25")
            val poiNameLink = page.locator(".poi-table td[data-label=\"Name\"] a").first()
            assertThat(poiNameLink).hasAttribute("href", Pattern.compile(".*/reservables\\?poi_id=31337"))
            assertThat(page.locator(".reservables-row")).hasCount(0)

            poiNameLink.click()
            page.waitForURL(Pattern.compile(".*/reservables\\?poi_id=31337"))
            assertThat(page.locator("input[name=\"poi_id\"]")).hasValue("31337")
            assertThat(page.locator("textarea[name=\"tags\"]")).isVisible()
            page.waitForFunction(
                "() => document.querySelectorAll('.reservables-table tbody tr.result-row').length === 2",
                null,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )
            assertEquals(1, poiReservableCalls.get(), "POI-scoped reservable page should use /api/poi/{id}/reservables")
            assertEquals(0, globalReservableCalls.get(), "POI-scoped reservable page should not call /api/reservables")
            assertThat(page.locator(".reservables-table th", Page.LocatorOptions().setHasText("Tags"))).containsText("Tags")
            assertThat(page.locator(".reservables-table td[data-label=\"Tags\"]").first()).containsText("Small Tent")
            assertThat(page.locator(".reservables-table td[data-label=\"Tags\"]").first()).containsText("Ground Cover: Soil")

            page.locator("button[data-action=\"toggle-reservable-detail\"][data-rid=\"site:matrix:001\"]").click()
            assertThat(page.locator(".cg-site-detail")).containsText("Site 1")
            assertThat(page.locator(".cg-site-detail")).containsText("Up to 8 people")
            assertThat(page.locator(".cg-site-detail")).containsText("Small Tent")
            assertThat(page.locator(".cg-site-detail")).containsText("Ground Cover: Soil")
            assertThat(page.locator(".cg-site-detail")).containsText("Firepit On Site: Yes")

            page.locator("button[data-action=\"toggle-reservable-detail\"][data-rid=\"site:matrix:001\"]").click()
            page.fill("textarea[name=\"tags\"]", """{"attributes":{"firepit_on_site":"Yes"}}""")
            page.locator("#reservable-form button[type=\"submit\"]").click()
            page.waitForFunction(
                "() => document.querySelectorAll('.reservables-table tbody tr.result-row').length === 1",
                null,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )

            page.locator("button[data-action=\"toggle-availability\"][data-rid=\"site:matrix:001\"]").click()
            assertThat(page.locator(".cg-site-matrix")).isVisible()
            assertThat(page.locator("button[data-action=\"toggle-availability\"][data-rid=\"site:matrix:001\"]")).hasCount(1)
            assertThat(page.locator(".availability-panel")).not().containsText("Query availability for this reservable")
            assertThat(page.locator(".availability-panel input[name=\"days\"]")).hasCount(0)
            assertThat(page.locator(".availability-panel input[name=\"min_nights\"]")).hasCount(0)
            assertTrue(
                page.evaluate(
                    """
                    () => {
                      const form = document.querySelector('.availability-controls');
                      const actions = form?.querySelector('.actions');
                      const finalButton = actions?.querySelector('button:last-of-type');
                      if (!form || !finalButton) return false;
                      const formRect = form.getBoundingClientRect();
                      const buttonRect = finalButton.getBoundingClientRect();
                      return Math.abs(formRect.right - buttonRect.right) <= 2;
                    }
                    """.trimIndent(),
                ) as Boolean,
                "availability query actions should align to the right edge of the form",
            )
            assertThat(page.locator(".availability-panel .availability-result > .availability-summary")).hasCount(0)
            val availabilityVisibleText =
                page.evaluate(
                    """
                    () => document.querySelector('.availability-result')?.innerText || ''
                    """.trimIndent(),
                ) as String
            assertFalse(
                availabilityVisibleText.contains("No availability data") || availabilityVisibleText.contains("aspira"),
                "availability result should omit response summary/provider chrome: $availabilityVisibleText",
            )
            assertThat(page.locator(".cg-site-matrix-cell-available")).hasCount(1)
            assertThat(page.locator(".cg-site-matrix-cell-booked")).hasCount(1)
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during catalog reservables smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            page.close()
            context.close()
        }
    }

    @Test
    fun `horizontal matrix swipe does not drag mobile drawer`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(390, 844)
                    .setHasTouch(true)
                    .setIsMobile(true),
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
        context.route("**/api/pois/31337") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "type": "Feature",
                          "id": 31337,
                          "geometry": { "type": "Point", "coordinates": [-115.55, 51.18] },
                          "properties": {
                            "category": "campground",
                            "subcategory": "federal",
                            "name": "Matrix Campground",
                            "state": "AB",
                            "provider_ref": { "recgov_id": "31337" }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/poi/31337/availability?**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "state": "success",
                          "summary": "7 dates",
                          "cache": { "age_seconds": 60 },
                          "availability": [
                            { "date": "2026-06-16", "available_count": 3, "total": 6, "available_reservable_ids": ["site:matrix:001", "site:matrix:002", "site:matrix:003"] },
                            { "date": "2026-06-17", "available_count": 2, "total": 6, "available_reservable_ids": ["site:matrix:002", "site:matrix:004"] },
                            { "date": "2026-06-18", "available_count": 1, "total": 6, "available_reservable_ids": ["site:matrix:005"] },
                            { "date": "2026-06-19", "available_count": 0, "total": 6, "available_reservable_ids": [] },
                            { "date": "2026-06-20", "available_count": 2, "total": 6, "available_reservable_ids": ["site:matrix:001", "site:matrix:006"] },
                            { "date": "2026-06-21", "available_count": 0, "total": 0, "status": "closed", "available_reservable_ids": [] },
                            { "date": "2026-06-22", "available_count": 3, "total": 6, "available_reservable_ids": ["site:matrix:003", "site:matrix:004", "site:matrix:005"] }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/poi/31337/reservables**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "poi_id": 31337,
                          "total_at_poi": 6,
                          "reservables": [
                            {
                              "rid": "site:matrix:001",
                              "vendor": "recgov",
                              "vendor_id": "001",
                              "name": "Site 1",
                              "loop": "A",
                              "site_type": "Tent",
                              "reservation_url_template": "https://www.recreation.gov/camping/campsites/001?startDate={start_date}&endDate={end_date}",
                              "raw": {
                                "defined_attributes": [
                                  { "definition_id": -32751, "values": [1] },
                                  { "definition_id": -32748, "value": null }
                                ]
                              }
                            },
                            { "rid": "site:matrix:002", "name": "Site 2", "loop": "A", "site_type": "Tent", "reservation_url_template": "https://example.test/2" },
                            { "rid": "site:matrix:003", "name": "Site 3", "loop": "B", "site_type": "RV", "reservation_url_template": "https://example.test/3" },
                            { "rid": "site:matrix:004", "name": "Site 4", "loop": "B", "site_type": "RV", "reservation_url_template": "https://example.test/4" },
                            { "rid": "site:matrix:005", "name": "Site 5", "loop": "C", "site_type": "Cabin", "reservation_url_template": "https://example.test/5" },
                            { "rid": "site:matrix:006", "name": "Site 6", "loop": "C", "site_type": "Cabin", "reservation_url_template": "https://example.test/6" }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/campsite/alerts") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("[]"),
            )
        }

        try {
            page.navigate("/?poi=31337")
            val matrix = page.locator(".cg-site-matrix-scroll")
            assertThat(matrix).isVisible(
                com.microsoft.playwright.assertions.LocatorAssertions
                    .IsVisibleOptions()
                    .setTimeout(15_000.0),
            )
            assertThat(page.locator(".cg-availability")).not().containsText("Stay length")
            assertTrue(
                page.evaluate(
                    """
                    () => {
                      const matrix = document.querySelector('.cg-site-matrix-scroll');
                      return matrix.scrollWidth > matrix.clientWidth;
                    }
                    """.trimIndent(),
                ) as Boolean,
                "matrix fixture should require horizontal scrolling",
            )

            val availableCellPaint =
                page.evaluate(
                    """
                    () => {
                      const cell = document.querySelector('.cg-site-matrix-cell-available');
                      const button = cell?.querySelector('.cg-site-matrix-cell-button');
                      if (!cell || !button) return '';
                      return getComputedStyle(cell).backgroundColor + '|' + getComputedStyle(button).backgroundColor;
                    }
                    """.trimIndent(),
                ) as String
            assertTrue(
                availableCellPaint.startsWith("rgba(76, 185, 106, 0.16)|rgba(0, 0, 0, 0)"),
                "available matrix cell state should fill the full table cell height",
            )
            val firstSiteLabel =
                page.evaluate(
                    """
                    () => document.querySelector('.cg-site-matrix-site-button')?.innerText.trim() || ''
                    """.trimIndent(),
                ) as String
            assertTrue(
                firstSiteLabel.startsWith("A / Site 1"),
                "site row title should render as loop / site: $firstSiteLabel",
            )
            page.locator(".cg-site-matrix-date-button[data-matrix-date=\"2026-06-16\"]").click()
            assertThat(page.locator(".cg-sites-row[data-rid=\"site:matrix:001\"] .cg-sites-row-book")).containsText("Book")
            assertThat(page.locator(".cg-sites-row[data-rid=\"site:matrix:001\"] .cg-sites-row-link")).hasAttribute(
                "href",
                "https://www.recreation.gov/camping/campsites/001?startDate=2026-06-16&endDate=2026-06-17",
            )
            page.locator(".cg-site-matrix-site-button").first().click()
            val detailSubtitle =
                page.evaluate(
                    """
                    () => document.querySelector('.cg-site-detail-subtitle')?.innerText.trim() || ''
                    """.trimIndent(),
                ) as String
            assertEquals(
                "",
                detailSubtitle,
                "site detail header should not reuse the matrix loop/type label",
            )
            val detailText =
                page.evaluate(
                    """
                    () => document.querySelector('.cg-site-detail')?.innerText || ''
                    """.trimIndent(),
                ) as String
            assertFalse(
                detailText.contains("Definition Id") || detailText.contains("Values:"),
                "site detail should hide unresolved provider attribute ids: $detailText",
            )
            assertThat(page.locator(".cg-site-detail-book")).hasCount(0)
            page.locator(".cg-site-matrix-cell-available .cg-site-matrix-cell-button").first().click()
            assertThat(page.locator(".cg-site-detail-subtitle")).containsText("2026-06-16")
            assertThat(page.locator(".cg-site-detail-book")).hasAttribute(
                "href",
                "https://www.recreation.gov/camping/campsites/001?startDate=2026-06-16&endDate=2026-06-17",
            )
            assertThat(page.locator(".cg-site-detail-book")).containsText("Book on Recreation.gov")
            val dateSortControlCount =
                page.evaluate(
                    """
                    () => document.querySelectorAll('[data-matrix-sort-date]').length
                    """.trimIndent(),
                ) as Int
            assertEquals(
                0,
                dateSortControlCount,
                "date columns should not be clickable sort controls",
            )

            val drawerHeightAfterMove =
                page.evaluate(
                    """
                    () => {
                      const target = document.querySelector('.cg-site-matrix-scroll');
                      const drawer = document.getElementById('cg-drawer');
                      drawer.style.height = '';
                      drawer.scrollTop = 0;
                      const send = (type, x, y) => {
                        const touch = new Touch({ identifier: 1, target, clientX: x, clientY: y });
                        const activeTouches = type === 'touchend' ? [] : [touch];
                        target.dispatchEvent(new TouchEvent(type, {
                          bubbles: true,
                          cancelable: true,
                          touches: activeTouches,
                          targetTouches: activeTouches,
                          changedTouches: [touch],
                        }));
                      };
                      send('touchstart', 340, 540);
                      send('touchmove', 80, 558);
                      const heightAfterMove = drawer.style.height;
                      send('touchend', 80, 558);
                      return heightAfterMove;
                    }
                    """.trimIndent(),
                ) as String

            assertEquals(
                "",
                drawerHeightAfterMove,
                "horizontal swipes in the matrix should not resize or drag the mobile drawer",
            )
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during matrix gesture smoke: ${pageErrors.joinToString(" | ")}",
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
            assertThat(page.locator("#tb-results .tb-results-body #tb-trip-dates")).isVisible()
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
}
