package ca.floo.roadtrip.importer

import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Regression: data/campgrounds.geojson has ~46 Parks Canada entries (codes
// prefixed PC-AB / PC-BC / parks-canada-) merged in by an old enrichment
// pass. They duplicate the hand-curated `parks-canada` source — same
// lat/lng, no last_verified — and would outrank the curated rows in search
// because they appear first in the index. UsCampgroundsSource skips them so
// parks-canada is the single source of truth for Canadian parks.
class UsCampgroundsSourceTest {
    @Test
    fun `Canadian PC-prefixed codes are skipped`() {
        val tmp = createTempFile(prefix = "campgrounds-", suffix = ".geojson").toFile()
        tmp.deleteOnExit()
        tmp.writeText(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-115.547,51.1812]},
               "properties":{"code":"PC-AB-Tunnel-Mountain-Village-I","state":"AB","name":"Tunnel Mountain Village I"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-122.95,49.05]},
               "properties":{"code":"PC-BC-Goldstream","state":"BC","name":"Goldstream"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-115.5,51.0]},
               "properties":{"code":"parks-canada-banff","state":"AB","name":"Some PC site"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-122.0,47.0]},
               "properties":{"code":"WA-MORAN","state":"WA","name":"Moran State Park CG"}}
            ]}
            """.trimIndent(),
        )

        val staged = UsCampgroundsSource(tmp).staged().toList()

        assertEquals(1, staged.size, "only the WA campground should be staged")
        assertEquals("Moran State Park CG", staged[0].name)
        assertTrue(staged.none { it.name.contains("Tunnel Mountain") })
    }
}
