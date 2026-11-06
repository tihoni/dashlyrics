package com.autolyrics.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class LyricsOvhResponse(
    val lyrics: String? = null
)

class LyricsOvhClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchPlainLyrics(
        artist: String,
        title: String,
        durationMs: Long
    ): FetchedLyricsResult? = withContext(Dispatchers.IO) {
        val cleanArtist = LrcParser.sanitizeArtist(artist)
        val cleanTitle = LrcParser.sanitizeTitle(title)

        // Try clean artist and title
        val result = queryOvh(cleanArtist, cleanTitle, durationMs)
            ?: run {
                val primaryArtist = LrcParser.extractPrimaryArtist(cleanArtist)
                if (primaryArtist != cleanArtist) {
                    queryOvh(primaryArtist, cleanTitle, durationMs)
                } else null
            }
            ?: run {
                val normTitle = LrcParser.normalizePunctuation(cleanTitle)
                if (normTitle != cleanTitle) {
                    queryOvh(cleanArtist, normTitle, durationMs)
                } else null
            }

        result
    }

    private fun queryOvh(artist: String, title: String, durationMs: Long): FetchedLyricsResult? {
        return try {
            val url = "https://api.lyrics.ovh/v1/${artist.trim()}/${title.trim()}".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DashLyrics/1.0.0 (Android Auto)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val parsed = json.decodeFromString<LyricsOvhResponse>(body)
                val text = parsed.lyrics.orEmpty()
                if (text.isNotBlank()) {
                    val lines = LrcParser.parsePlainLyrics(text, durationMs)
                    if (lines.isNotEmpty()) {
                        FetchedLyricsResult(
                            lines = lines,
                            isSynced = false,
                            statusMessage = "Lyrics.ovh (Auto-timed lyrics)",
                            source = "Lyrics.ovh"
                        )
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
