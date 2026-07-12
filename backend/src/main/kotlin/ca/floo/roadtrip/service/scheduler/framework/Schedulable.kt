package ca.floo.roadtrip.service.scheduler.framework

/**
 * Things a scheduled row carries that the scheduler reads. Intentionally
 * minimal — anything domain-specific stays inside the row type and is
 * read by the handler.
 */
interface Schedulable {
    val id: Long
    val claimToken: String?
}
