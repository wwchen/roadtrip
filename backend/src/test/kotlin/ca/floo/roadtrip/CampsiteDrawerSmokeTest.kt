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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Drawer smoke for RFC 0003. Six frontend states are mocked at the network
// boundary so the test stays hermetic — rec.gov is never called. Visits the
// landing page with ?drawer=1, mocks /api/campsite/availability/*, drives a
// US federal pin click via search → synthesizeClick path (same trick as
// SmokeTest), and asserts each state renders its expected DOM.
//
// Gated on QA_BASE_URL like SmokeTest. Run via `make qa`.
@EnabledIfEnvironmentVariable(named = "QA_BASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampsiteDrawerSmokeTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private val baseUrl: String = System.getenv("QA_BASE_URL") ?: "http://127.0.0.1:8765"

    // Upper Pines is the canonical test target — high-traffic, US federal,
    // present in data/campgrounds.geojson with recgov_id 232447.
    private val testCampgroundName = "Upper Pines"
    private val testRecgovId = "232447"

    @BeforeAll
    fun setUp() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }

    @AfterAll
    fun tearDown() {
        browser.close()
        playwright.close()
    }

    private fun newMobileContext(): Browser.NewContextOptions =
        Browser
            .NewContextOptions()
            .setBaseURL(baseUrl)
            .setViewportSize(390, 844) // iPhone 14 Pro

    private fun successJson(): String =
        """
        {
          "state": "success",
          "campground_id": "$testRecgovId",
          "window": { "start": "2026-06-05", "days": 30 },
          "summary": "8 nights available · weekends full",
          "availability": [
            ${(0..29).joinToString(",") { i ->
            val status =
                when (i % 3) {
                    0 -> "available"
                    1 -> "booked"
                    else -> "partial"
                }
            """{"date":"2026-06-${"%02d".format((i % 28) + 1)}","status":"$status"}"""
        }}
          ],
          "cache": { "hit": false, "age_seconds": 0, "ttl_seconds": 600 }
        }
        """.trimIndent()

    private fun zeroAvailableJson(): String =
        """
        {
          "state": "zero_available",
          "campground_id": "$testRecgovId",
          "window": { "start": "2026-06-05", "days": 30 },
          "summary": "Fully booked next 30 days",
          "availability": [
            ${(0..29).joinToString(",") { i ->
            """{"date":"2026-06-${"%02d".format((i % 28) + 1)}","status":"booked"}"""
        }}
          ],
          "cache": { "hit": true, "age_seconds": 120, "ttl_seconds": 600 }
        }
        """.trimIndent()

    private fun closedForSeasonJson(): String =
        """
        {
          "state": "closed_for_season",
          "campground_id": "$testRecgovId",
          "window": { "start": "2026-06-05", "days": 30 },
          "summary": "Closed for season",
          "season": { "reopens_on": "2026-09-15" },
          "availability": [],
          "cache": { "hit": false, "age_seconds": 0, "ttl_seconds": 600 }
        }
        """.trimIndent()

    private fun emptyJson(): String =
        """
        {
          "state": "empty",
          "campground_id": "$testRecgovId",
          "window": { "start": "2026-06-05", "days": 30 },
          "summary": "",
          "availability": [],
          "cache": { "hit": false, "age_seconds": 0, "ttl_seconds": 600 }
        }
        """.trimIndent()

    private fun errorJson(code: String): String =
        """
        {
          "state": "error",
          "error": "$code",
          "retry_after_s": 60
        }
        """.trimIndent()

    /**
     * Drive the landing page to the point where the drawer is open for our
     * test pin. Returns the Page once the drawer header is visible.
     */
    private fun openDrawerFor(page: Page) {
        page.navigate("/?drawer=1")
        page.waitForFunction(
            "() => globalThis.__rtState?.mapReady === true",
            null,
            Page.WaitForFunctionOptions().setTimeout(15_000.0),
        )
        // Pan to Yosemite (Upper Pines).
        page.evaluate(
            "() => globalThis.__rtMap.flyTo({ center: [-119.563, 37.735], zoom: 13, animate: false })",
        )
        page.waitForFunction(
            "() => (globalThis.__rtState?.overlayData?.cg?.features?.length || 0) > 0",
            null,
            Page.WaitForFunctionOptions().setTimeout(15_000.0),
        )
        page.fill(".tb-row[data-i=\"0\"] .tb-input", testCampgroundName)
        page.locator("#tb-dropdown .tb-result").first().waitFor(
            com.microsoft.playwright.Locator
                .WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(5_000.0),
        )
        page.locator("#tb-dropdown .tb-result").first().click()

        // Drawer header should appear; popup should NOT.
        val drawer = page.locator("#cg-drawer.open")
        assertThat(drawer).isVisible(
            com.microsoft.playwright.assertions.LocatorAssertions
                .IsVisibleOptions()
                .setTimeout(10_000.0),
        )
        assertThat(page.locator("#cg-drawer h2")).containsText("Upper Pines")
    }

    private fun routeAvailabilityWith(
        context: com.microsoft.playwright.BrowserContext,
        body: String,
        status: Int = 200,
    ) {
        context.route("**/api/campsite/availability/**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(status)
                    .setContentType("application/json")
                    .setBody(body),
            )
        }
    }

    @Test
    fun `state 1 - success - heat-strip renders 30 cells with primary CTA Watch for openings`() {
        val context = browser.newContext(newMobileContext())
        routeAvailabilityWith(context, successJson())
        val page = context.newPage()
        try {
            openDrawerFor(page)
            // 30 cells.
            assertThat(page.locator("#cg-drawer .cg-cell")).hasCount(30)
            // At least one available cell.
            assertTrue(page.locator("#cg-drawer .cg-cell.cg-cell-available").count() > 0)
            // Summary mirrors backend.
            assertThat(page.locator("#cg-drawer .cg-summary")).containsText("nights available")
            // Primary CTA copy + deeplink.
            val primary = page.locator("#cg-drawer .cg-btn-primary")
            assertThat(primary).hasText("Watch for openings")
            assertEquals("/campsite?campground=$testRecgovId", primary.getAttribute("href"))
            // Secondary CTA goes to rec.gov.
            assertEquals(
                "https://www.recreation.gov/camping/campgrounds/$testRecgovId",
                page.locator("#cg-drawer .cg-btn-secondary").getAttribute("href"),
            )
        } finally {
            context.close()
        }
    }

    @Test
    fun `state 2 - zero_available - CTA copy flips to Snipe a cancellation`() {
        val context = browser.newContext(newMobileContext())
        routeAvailabilityWith(context, zeroAvailableJson())
        val page = context.newPage()
        try {
            openDrawerFor(page)
            assertThat(page.locator("#cg-drawer .cg-summary")).containsText("Fully booked")
            assertThat(page.locator("#cg-drawer .cg-btn-primary")).hasText("Snipe a cancellation")
            // All cells should be booked.
            assertThat(page.locator("#cg-drawer .cg-cell.cg-cell-booked")).hasCount(30)
        } finally {
            context.close()
        }
    }

    @Test
    fun `state 3 - closed_for_season - banner replaces strip and CTA flips to Watch for opening day`() {
        val context = browser.newContext(newMobileContext())
        routeAvailabilityWith(context, closedForSeasonJson())
        val page = context.newPage()
        try {
            openDrawerFor(page)
            assertThat(page.locator("#cg-drawer .cg-closed-banner")).isVisible()
            assertThat(page.locator("#cg-drawer .cg-closed-banner")).containsText("2026-09-15")
            assertThat(page.locator("#cg-drawer .cg-btn-primary")).hasText("Watch for opening day")
        } finally {
            context.close()
        }
    }

    @Test
    fun `state 4 - empty - heat-strip hidden and fallback summary`() {
        val context = browser.newContext(newMobileContext())
        routeAvailabilityWith(context, emptyJson())
        val page = context.newPage()
        try {
            openDrawerFor(page)
            assertThat(page.locator("#cg-drawer .cg-summary")).containsText("No availability data")
            // Strip is hidden by display:none.
            val stripVisible =
                page.evaluate(
                    "() => { const el = document.querySelector('#cg-drawer .cg-strip'); " +
                        "return el ? getComputedStyle(el).display !== 'none' : false; }",
                ) as Boolean
            assertEquals(false, stripVisible)
        } finally {
            context.close()
        }
    }

    @Test
    fun `state 5 - error rate_limited - shows retry link and primary CTA stays enabled`() {
        val context = browser.newContext(newMobileContext())
        routeAvailabilityWith(context, errorJson("rate_limited"), status = 503)
        val page = context.newPage()
        try {
            openDrawerFor(page)
            assertThat(page.locator("#cg-drawer .cg-summary .cg-error")).containsText("rate-limiting")
            assertThat(page.locator("#cg-drawer .cg-summary .cg-retry")).isVisible()
            // CTAs stay clickable — user can still open campsite page or rec.gov.
            assertThat(page.locator("#cg-drawer .cg-btn-primary")).isEnabled()
            assertThat(page.locator("#cg-drawer .cg-btn-secondary")).isEnabled()
        } finally {
            context.close()
        }
    }

    @Test
    fun `state 6 - loading skeleton appears before fetch resolves`() {
        val context = browser.newContext(newMobileContext())
        // Hold the response open ~600ms so the skeleton is observable.
        context.route("**/api/campsite/availability/**") { route ->
            Thread.sleep(600)
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(successJson()),
            )
        }
        val page = context.newPage()
        try {
            page.navigate("/?drawer=1")
            page.waitForFunction(
                "() => globalThis.__rtState?.mapReady === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )
            page.evaluate(
                "() => globalThis.__rtMap.flyTo({ center: [-119.563, 37.735], zoom: 13, animate: false })",
            )
            page.waitForFunction(
                "() => (globalThis.__rtState?.overlayData?.cg?.features?.length || 0) > 0",
                null,
                Page.WaitForFunctionOptions().setTimeout(15_000.0),
            )
            page.fill(".tb-row[data-i=\"0\"] .tb-input", testCampgroundName)
            page.locator("#tb-dropdown .tb-result").first().waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000.0),
            )
            page.locator("#tb-dropdown .tb-result").first().click()
            // Skeleton cells render before fetch resolves.
            page.locator("#cg-drawer .cg-cell.skeleton").first().waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000.0),
            )
            // After resolution, real cells replace skeletons.
            page.locator("#cg-drawer .cg-cell.cg-cell-available").first().waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000.0),
            )
            assertEquals(0, page.locator("#cg-drawer .cg-cell.skeleton").count())
        } finally {
            context.close()
        }
    }
}
