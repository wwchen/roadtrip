package ca.floo.roadtrip.service.etl.framework

private val nonAlnumRegex = Regex("[^a-z0-9]+")

fun campsiteTagKey(label: String): String =
    label
        .trim()
        .lowercase()
        .replace(nonAlnumRegex, "_")
        .trim('_')
