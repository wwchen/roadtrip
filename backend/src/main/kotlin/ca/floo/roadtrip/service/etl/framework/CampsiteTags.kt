package ca.floo.roadtrip.service.etl.framework

private val NON_ALNUM = Regex("[^a-z0-9]+")

fun campsiteTagKey(label: String): String =
    label
        .trim()
        .lowercase()
        .replace(NON_ALNUM, "_")
        .trim('_')
