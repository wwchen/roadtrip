package ca.floo.roadtrip.fixtures

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * A classloader serving the given config documents from memory, so a loader test
 * reads a fixture tree instead of the shipped resources. Keyed by resource name,
 * e.g. `"application.yaml" to "roadtrip:\n  ..."`.
 */
fun configResourceClassLoader(vararg resources: Pair<String, String>): ClassLoader {
    val byName = resources.toMap()
    return object : ClassLoader(null) {
        override fun getResource(name: String): URL? =
            byName[name]?.let { content ->
                URL(
                    null,
                    "memory:$name",
                    object : URLStreamHandler() {
                        override fun openConnection(url: URL): URLConnection =
                            object : URLConnection(url) {
                                override fun connect() = Unit

                                override fun getInputStream(): InputStream = ByteArrayInputStream(content.toByteArray())
                            }
                    },
                )
            }

        override fun getResourceAsStream(name: String) = byName[name]?.byteInputStream()
    }
}
