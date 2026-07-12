package ca.floo.roadtrip.models.metadata.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Fetcher(
    val executor: String,
    val filename: String,
    val args: Map<String, String> = emptyMap(),
    @kotlinx.serialization.SerialName("timeout_sec")
    val timeoutSec: Long = 30 * 60,
    @kotlinx.serialization.SerialName("output_dir_prefix")
    val outputDirPrefix: String,
)
