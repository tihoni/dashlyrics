package com.autolyrics.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class KaralyrClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchLyrics(
        artist: String,
        title: String,
        durationMs: Long
    ): FetchedLyricsResult? = withContext(Dispatchers.IO) {
        val durationSec = (durationMs / 1000L).coerceAtLeast(0L)

        // 1. Exact GET query
        val getResult = queryGetEndpoint(artist, title, durationSec)
        if (getResult != null && !getResult.syncedLyrics.isNullOrBlank()) {
            val lines = LrcParser.parseSyncedLrc(getResult.syncedLyrics)
            if (lines.isNotEmpty()) {
                return@withContext FetchedLyricsResult(
                    lines = lines,
                    isSynced = true,
                    source = "Karalyr (Synced)"
                )
            }
        }

        // 2. Search endpoint
        val searchResults = querySearchEndpoint(artist, title)
        val bestSynced = searchResults
            .filter { !it.syncedLyrics.isNullOrBlank() }
            .minByOrNull { item ->
                val itemDur = item.duration?.toLong() ?: durationSec
                if (durationSec > 0) abs(itemDur - durationSec) else 0L
            }

        if (bestSynced != null) {
            val lines = LrcParser.parseSyncedLrc(bestSynced.syncedLyrics.orEmpty())
            if (lines.isNotEmpty()) {
                return@withContext FetchedLyricsResult(
                    lines = lines,
                    isSynced = true,
                    source = "Karalyr (Synced)"
                )
            }
        }

        // 3. Fallback to plain if found
        val plainCandidate = getResult?.takeIf { !it.plainLyrics.isNullOrBlank() }
            ?: searchResults.firstOrNull { !it.plainLyrics.isNullOrBlank() }

        if (plainCandidate != null) {
            val lines = LrcParser.parsePlainLyrics(plainCandidate.plainLyrics.orEmpty(), durationMs)
            if (lines.isNotEmpty()) {
                return@withContext FetchedLyricsResult(
                    lines = lines,
                    isSynced = false,
                    statusMessage = "Karalyr (Unsynced)",
                    source = "Karalyr (Unsynced)"
                )
            }
        }

        null
    }

    private fun queryGetEndpoint(
        artist: String,
        title: String,
        durationSec: Long
    ): LrcLibResponse? {
        if (artist.isBlank() || title.isBlank()) return null
        return try {
            val urlBuilder = "https://karalyr.com/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
            if (durationSec > 0L) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "DashLyrics/1.0.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.decodeFromString<LrcLibResponse>(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun querySearchEndpoint(
        artist: String,
        title: String
    ): List<LrcLibResponse> {
        if (artist.isBlank() || title.isBlank()) return emptyList()
        return try {
            val url = "https://karalyr.com/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DashLyrics/1.0.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                json.decodeFromString<List<LrcLibResponse>>(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
