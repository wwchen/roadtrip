package ca.floo.roadtrip.service.etl.framework

/** Upstream descriptions and highlights arrive as HTML fragments; the catalog stores plain text. */
object HtmlText {
    private val tag = Regex("<[^>]*>")
    private val whitespace = Regex("\\s+")

    fun stripTags(value: String): String = value.replace(tag, " ").replace(whitespace, " ").trim()
}
