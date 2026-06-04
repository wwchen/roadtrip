package ca.floo.campsite.recgov.booker.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class Alert(
    val id: Long,
    val campgroundId: String,
    val campgroundName: String,
    val parentName: String? = null,
    val parentId: String? = null,
    val startDate: String,
    val endDate: String,
    val minNights: Int = 1,
    val campsiteTypes: List<String> = emptyList(),
    val equipmentTypes: List<String> = emptyList(),
    val maxPeople: Int? = null,
    val specificSites: List<String> = emptyList(),
    val notifySlack: Boolean = true,
    val autoCart: Boolean = false,
    val stopAfterMatch: Boolean = true,
    val status: String = "active",
    val lastChecked: String? = null,
    val lastMatch: String? = null,
    val notes: String? = null,
    val createdAt: String,
)

@Serializable
data class Match(
    val id: Long,
    val alertId: Long,
    val campgroundId: String,
    val campsiteId: String,
    val campsiteSite: String? = null,
    val campsiteLoop: String? = null,
    val campsiteType: String? = null,
    val availableDates: List<String>,
    val firstDate: String,
    val nights: Int,
    val foundAt: String,
    val notified: Boolean = false,
    val claimedBy: String? = null,
    val claimedAt: String? = null,
    val leaseExpires: String? = null,
    val cartAdded: Boolean? = null,
    val resultAt: String? = null,
    val dismissedAt: String? = null,
    // Joined from alert for UI convenience.
    val campgroundName: String? = null,
    val alertStart: String? = null,
    val alertEnd: String? = null,
)

fun LocalDate.iso(): String = toString()
fun OffsetDateTime.iso(): String = toString()
