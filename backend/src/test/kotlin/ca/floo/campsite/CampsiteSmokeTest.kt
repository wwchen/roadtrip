package ca.floo.campsite

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
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
}
