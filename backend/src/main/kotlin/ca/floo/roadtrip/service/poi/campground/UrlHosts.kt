package ca.floo.roadtrip.service.poi.campground

internal object UrlHosts {
    fun extract(url: String): String? {
        val schemeIdx = url.indexOf("://").takeIf { it > 0 } ?: return null
        val afterScheme = url.substring(schemeIdx + 3)
        val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPort = if (end < 0) afterScheme else afterScheme.substring(0, end)
        val host = hostPort.substringBefore(':').lowercase().trim()
        if (host.isBlank()) return null
        return host.removePrefix("www.")
    }
}
