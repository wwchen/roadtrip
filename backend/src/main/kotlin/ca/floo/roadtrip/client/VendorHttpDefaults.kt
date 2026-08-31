package ca.floo.roadtrip.client

import java.time.Duration

/**
 * Timeouts every JDK-`HttpClient`-backed vendor client shares. They were four
 * identical private copies, which is three chances for one vendor's ceiling to
 * drift away from the rest without anyone deciding it should.
 *
 * Per-vendor values stay per-vendor (throttle gaps, retry budgets); these are
 * the floor/ceiling every upstream gets unless it has a reason not to.
 */
object VendorHttpDefaults {
    /** Whole-request ceiling: vendor grids and matrices are slow but not this slow. */
    val requestTimeout: Duration = Duration.ofSeconds(30)

    /** TCP/TLS connect ceiling — a host that hasn't answered by now is unreachable. */
    val connectTimeout: Duration = Duration.ofSeconds(10)
}
