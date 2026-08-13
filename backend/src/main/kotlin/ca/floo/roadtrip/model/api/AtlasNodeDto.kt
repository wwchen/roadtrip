package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The tier a node sits at in the Atlas index tree. */
@Serializable
enum class AtlasNodeKind {
    @SerialName("region")
    REGION,

    @SerialName("classification")
    CLASSIFICATION,

    @SerialName("campground")
    CAMPGROUND,

    @SerialName("campsite")
    CAMPSITE,
}

/**
 * One row in the Atlas index. The tree is expanded one level at a time: the
 * client asks for a [key]'s children via `GET /api/atlas/node?path={key}`.
 *
 * [childCount] is the rolled-up count the node advertises (campgrounds for a
 * region/classification, campsites for a campground) — it is the call-to-action
 * number, so it stays the true total even when the child list the client later
 * fetches is capped. [teaser] is a short peek at child labels shown before
 * expansion. [poiId] links a campground leaf into the existing map/detail
 * surface (`?poi={poiId}`).
 */
@Serializable
data class AtlasNodeDto(
    val key: String,
    val label: String,
    val kind: AtlasNodeKind,
    @SerialName("child_count") val childCount: Int,
    @SerialName("has_children") val hasChildren: Boolean,
    val teaser: List<String> = emptyList(),
    @SerialName("poi_id") val poiId: Long? = null,
)

/** Children of one Atlas node. [path] echoes the requested node key. */
@Serializable
data class AtlasNodeResponseDto(
    val path: String,
    val children: List<AtlasNodeDto>,
)
