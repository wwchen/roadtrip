package ca.floo.campsite

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Trip-critical smoke for the campsite alert UI: cold load → header + alerts
// list render → settings modal opens and closes. Gated on QA_BASE_URL so
// `gradle test` skips it unless a stack is already up. Run via `make qa`.
@EnabledIfEnvironmentVariable(named = "QA_BASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampsiteSmokeTest {
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
    fun `cold load - campsite page renders and settings modal opens`() {
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
            page.navigate("/campsite/")

            // Header chrome must paint — proves index.html, style.css, and
            // app.js all loaded under the /campsite/ base href.
            assertThat(page.locator(".logo")).containsText("Campsite Alert")
            assertThat(page.locator("#new-alert-panel h2")).containsText("New Alert")

            // app.js wires settings-btn → openSettings on DOMContentLoaded.
            // If the script crashed at parse, this click is a no-op and the
            // modal stays .hidden.
            page.locator("#settings-btn").click()
            assertThat(page.locator("#settings-modal")).not().hasClass("modal hidden")
            assertThat(page.locator("#settings-modal h3")).containsText("Settings")

            // Close via the X — confirms the close handler is wired too.
            page.locator("#settings-close").click()
            assertThat(page.locator("#settings-modal")).hasClass("modal hidden")

            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during campsite smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            context.close()
        }
    }

    @Test
    fun `SSE match event refreshes matches list`() {
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
        var matchId: Long? = null
        var alertId: Long? = null

        try {
            page.navigate("/campsite/")
            cleanupSyntheticCampsiteData(page)
            page.reload()
            page.waitForFunction(
                "() => globalThis.__campsiteState?.sseConnected === true",
                null,
                Page.WaitForFunctionOptions().setTimeout(5_000.0),
            )

            val created =
                page.evaluate(
                    """
                    async () => {
                      const resp = await fetch('/api/admin/campsite/debug/synth-match', {
                        method: 'POST',
                        headers: { 'content-type': 'application/json' },
                        body: JSON.stringify({
                          campgroundId: '232447',
                          campsiteId: '0',
                          startDate: '2026-08-01',
                          endDate: '2026-08-02'
                        })
                      });
                      return await resp.text();
                    }
                    """.trimIndent(),
                ) as String
            matchId =
                Pattern
                    .compile(""""id"\s*:\s*(\d+)""")
                    .matcher(created)
                    .let { matcher ->
                        if (matcher.find()) matcher.group(1).toLong() else null
                    }
            assertTrue(matchId != null, "synthetic match response did not contain id: $created")

            assertThat(page.locator("#matches-list")).containsText(
                "spike-232447",
                com.microsoft.playwright.assertions.LocatorAssertions
                    .ContainsTextOptions()
                    .setTimeout(5_000.0),
            )
            assertThat(page.locator("#matches-list")).containsText("spike-site")

            val matchJson =
                page.evaluate(
                    """
                    async (id) => await (await fetch('/api/campsite/matches/' + id)).text()
                    """.trimIndent(),
                    matchId.toString(),
                ) as String
            alertId =
                Pattern
                    .compile(""""alertId"\s*:\s*(\d+)""")
                    .matcher(matchJson)
                    .let { matcher ->
                        if (matcher.find()) matcher.group(1).toLong() else null
                    }
            assertTrue(alertId != null, "match payload did not contain alertId: $matchJson")

            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during campsite SSE smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            matchId?.let {
                page.evaluate(
                    "async (id) => fetch('/api/campsite/matches/' + id, { method: 'DELETE' })",
                    it.toString(),
                )
            }
            alertId?.let {
                page.evaluate(
                    "async (id) => fetch('/api/campsite/alerts/' + id, { method: 'DELETE' })",
                    it.toString(),
                )
            }
            cleanupSyntheticCampsiteData(page)
            context.close()
        }
    }

    @Test
    fun `create alert preflight failure restores submit button`() {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(baseUrl)
                    .setViewportSize(1280, 800),
            )
        context.route("**/api/campsite/campgrounds/search**") { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "parks": [],
                          "campgrounds": [{
                            "id": "232447",
                            "name": "Upper Pines",
                            "parent_name": "Yosemite National Park",
                            "parent_id": "2991",
                            "city": "Yosemite National Park",
                            "state": "CA"
                          }]
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route(Pattern.compile(".*/api/campsite/settings$")) { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(
                        """
                        {
                          "poll_interval": "60",
                          "slack_enabled": "false",
                          "recgov_token": "••••••••",
                          "recgov_token_expired": false
                        }
                        """.trimIndent(),
                    ),
            )
        }
        context.route("**/api/campsite/booking/session/validate") { route: Route ->
            Thread.sleep(1_000)
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"loggedIn":false,"error":"no token saved"}"""),
            )
        }
        val page = context.newPage()
        val pageErrors = mutableListOf<String>()
        page.onPageError { pageErrors.add(it) }

        try {
            page.navigate("/campsite?campground=232447")
            if (page.locator("#onboarding-close").isVisible()) page.locator("#onboarding-close").click()
            assertThat(page.locator("#campground-search")).hasValue(
                "Upper Pines",
                com.microsoft.playwright.assertions.LocatorAssertions
                    .HasValueOptions()
                    .setTimeout(5_000.0),
            )
            page.locator("#start-date").fill("2026-08-01")
            page.locator("#end-date").fill("2026-08-02")
            if (page.locator("#notify-slack").isVisible()) page.locator("#notify-slack").setChecked(false)

            val submit = page.locator("#alert-form button[type='submit']")
            val busyState =
                submit.evaluate(
                    """
                    button => {
                      button.click();
                      return { disabled: button.disabled, text: button.textContent };
                    }
                    """.trimIndent(),
                ) as Map<*, *>
            assertEquals(true, busyState["disabled"])
            assertEquals("Checking...", busyState["text"])
            assertThat(page.locator("#toast-container")).containsText(
                "Auto-cart requires a valid rec.gov token",
                com.microsoft.playwright.assertions.LocatorAssertions
                    .ContainsTextOptions()
                    .setTimeout(5_000.0),
            )
            assertThat(submit).isEnabled()
            assertThat(submit).hasText("Create Alert")

            assertTrue(
                pageErrors.isEmpty(),
                "Page errors during create-alert preflight smoke: ${pageErrors.joinToString(" | ")}",
            )
        } finally {
            context.close()
        }
    }

    private fun cleanupSyntheticCampsiteData(page: Page) {
        page.evaluate(
            """
            async () => {
              const matches = await (await fetch('/api/campsite/matches?limit=100')).json();
              for (const m of matches) {
                if ((m.campground_name || '').startsWith('spike-') ||
                    (m.campsite_site || '') === 'spike-site' ||
                    String(m.campsite_id || '').startsWith('e2e-')) {
                  await fetch('/api/campsite/matches/' + m.id, { method: 'DELETE' });
                }
              }
              const alerts = await (await fetch('/api/campsite/alerts')).json();
              for (const a of alerts) {
                if ((a.campground_name || '').startsWith('spike-') || a.notes === 'spike') {
                  await fetch('/api/campsite/alerts/' + a.id, { method: 'DELETE' });
                }
              }
            }
            """.trimIndent(),
        )
    }
}
