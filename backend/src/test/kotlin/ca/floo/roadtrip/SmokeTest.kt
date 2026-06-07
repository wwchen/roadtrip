package ca.floo.roadtrip

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitForSelectorState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.regex.Pattern
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
                "() => globalThis.__rtMap.flyTo({ center: [-115.55, 51.18], zoom: 13, animate: false })",
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
            // at .tb-row[data-i="0"] .tb-input; matching pin results render as
            // .tb-result rows in #tb-dropdown.
            page.fill(".tb-row[data-i=\"0\"] .tb-input", "tunnel mountain village")
            page.locator("#tb-dropdown .tb-result").first().waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000.0),
            )
            page.locator("#tb-dropdown .tb-result").first().click()

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
            assertThat(drawer.locator("h2")).containsText("Tunnel Mountain Village")

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

            // 7. last_verified footer. The drawer also has a Booking-system
            // footnote that uses the same .footer class, so match the
            // verified one by its content.
            assertThat(drawer.getByText(Pattern.compile("Verified \\d{4}-\\d{2}-\\d{2}")))
                .isVisible()

            // 8. No JS errors during the run.
            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            context.close()
        }
    }
}
