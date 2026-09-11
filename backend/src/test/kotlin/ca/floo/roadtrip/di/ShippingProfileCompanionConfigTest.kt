package ca.floo.roadtrip.di

import ca.floo.roadtrip.fixtures.repoFile
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

private const val RESOURCES_DIR = "backend/src/main/resources"
private const val COMPANION_BASE_URL_KEY = "companion-base-url"

/** The profiles a deployment actually boots with; the base yaml leaves the key blank on purpose. */
private val shippingProfiles = listOf("local", "compose-local", "prod")

private val companionBaseUrlLine = Regex("""^\s*$COMPANION_BASE_URL_KEY:(.*)$""", RegexOption.MULTILINE)

/**
 * `bookingAdapters` registers `RecGovBookingAdapter` only when the companion
 * channel has an ATC client, and the channel exists only with
 * `companion-base-url` set. The drawer's rec.gov identity is resolved through
 * that same registry, so a profile that ships without the companion URL would
 * silently drop the Reserve on Recreation.gov CTA from every aliased pin —
 * a display regression with no error anywhere.
 */
class ShippingProfileCompanionConfigTest {
    @Test
    fun `every shipping profile sets the companion URL, so aliased pins keep their rec_gov CTA`() {
        for (profile in shippingProfiles) {
            val path = "$RESOURCES_DIR/application-$profile.yaml"
            val value =
                companionBaseUrlLine
                    .find(repoFile(path).readText())
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.trim('"', '\'')

            assertTrue(
                !value.isNullOrBlank(),
                "$path must set $COMPANION_BASE_URL_KEY; without it the rec.gov booking adapter is " +
                    "never registered and aliased pins lose their Recreation.gov CTA",
            )
        }
    }
}
