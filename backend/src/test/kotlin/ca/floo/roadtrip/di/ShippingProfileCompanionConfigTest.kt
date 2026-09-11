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
 * `bookingAdapters` registers `RecGovBookingAdapter` only inside
 * `get<CompanionChannel>().atc?.let { … }`, and that channel exists only with
 * `companion-base-url` set. A profile shipped without it therefore registers no
 * booking adapter at all: `atc` is never offered, `add_to_cart` is
 * `UNSUPPORTED`, and every hold refuses with `UNSUPPORTED_TARGET` — with no
 * error anywhere. `docs/reservation-providers.md` states the requirement; this
 * is what enforces it.
 *
 * The drawer's CTA no longer depends on it (phase 4b resolves an aliased pin's
 * identity through `BookingIdentityResolver` and the registry's `sells`), so
 * the `atc` action is the whole of what this guards.
 */
class ShippingProfileCompanionConfigTest {
    @Test
    fun `every shipping profile sets the companion URL, so a hold has an adapter to run on`() {
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
                "$path must set $COMPANION_BASE_URL_KEY; without it RecGovBookingAdapter is never " +
                    "registered, `atc` is never offered and every hold refuses",
            )
        }
    }
}
