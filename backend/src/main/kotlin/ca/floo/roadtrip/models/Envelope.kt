package ca.floo.roadtrip.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The envelope every Python fetcher writes (see scripts/_envelope.py).
// Outer shape is uniform across sources; the `payload` field carries the
// verbatim upstream body, which each per-source ETL deserializes into its
// own DTO.
//
// Wire format from the fetchers:
//   {
//     "fetcher": "fetch_planet_fitness",
//     "fetcher_version": "2",
//     "fetched_at": "2026-06-07T21:33:18Z",
//     "request":  { "url": ..., "method": "GET", "headers": {...} },
//     "response": { "status": 200, "headers": {...} },
//     "poller_run_id": null,
//     "payload": <verbatim>
//   }
@Serializable
data class Envelope(
    val fetcher: String,
    @kotlinx.serialization.SerialName("fetcher_version") val fetcherVersion: String,
    @kotlinx.serialization.SerialName("fetched_at") val fetchedAt: String,
    val request: RequestMeta,
    val response: ResponseMeta,
    @kotlinx.serialization.SerialName("poller_run_id") val pollerRunId: Long? = null,
    // Held as JsonElement so per-source ETLs can deserialize into their
    // own DTO with their own schema, instead of forcing every source
    // through one shared payload type.
    val payload: JsonElement,
    // Optional, set by paginated/multi-part captures.
    val part: String? = null,
)

@Serializable
data class RequestMeta(
    val url: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ResponseMeta(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
)
