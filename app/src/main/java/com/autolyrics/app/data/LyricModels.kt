package com.autolyrics.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsState(
    val trackTitle: String = "",
    val artistName: String = "",
    val lines: List<LyricLine> = emptyList(),
    val activeIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val isSynced: Boolean = true,
    val statusMessage: String? = null,
    val source: String? = null
)

@Serializable
data class LrcLibResponse(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("albumName") val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null
)

data class FetchedLyricsResult(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val statusMessage: String? = null,
    val source: String = "LRCLIB"
)
