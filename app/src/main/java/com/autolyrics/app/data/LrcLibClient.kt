package com.autolyrics.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class LrcLibClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchLyrics(
        rawArtist: String,
        rawTitle: String,
        durationMs: Long
    ): FetchedLyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = LrcParser.sanitizeTitle(rawTitle)
        val cleanArtist = LrcParser.sanitizeArtist(rawArtist)
        val primaryArtist = LrcParser.extractPrimaryArtist(cleanArtist)
        val normTitle = LrcParser.normalizePunctuation(cleanTitle)
        val durationSec = (durationMs / 1000L).coerceAtLeast(0L)

        var fallbackPlainResponse: LrcLibResponse? = null
        var fallbackInstrumental = false

        // Phase 1: Exact lookups - prioritize synced lyrics, hold plain as fallback
        val exactAttempts = linkedSetOf<Triple<String, String, Long>>(
            Triple(cleanArtist, cleanTitle, durationSec),
            Triple(cleanArtist, cleanTitle, 0L),
            Triple(primaryArtist, cleanTitle, durationSec),
            Triple(primaryArtist, cleanTitle, 0L)
        )
        if (normTitle != cleanTitle) {
            exactAttempts.add(Triple(cleanArtist, normTitle, durationSec))
            exactAttempts.add(Triple(cleanArtist, normTitle, 0L))
            exactAttempts.add(Triple(primaryArtist, normTitle, 0L))
        }
        if (cleanArtist != rawArtist || cleanTitle != rawTitle) {
            exactAttempts.add(Triple(rawArtist, rawTitle, durationSec))
            exactAttempts.add(Triple(rawArtist, rawTitle, 0L))
        }

        for ((a, t, d) in exactAttempts) {
            val res = queryGetEndpoint(a, t, d) ?: continue

            // If synced lyrics found, immediately return!
            if (!res.syncedLyrics.isNullOrBlank()) {
                val lines = LrcParser.parseSyncedLrc(res.syncedLyrics)
                if (lines.isNotEmpty()) {
                    return@withContext FetchedLyricsResult(
                        lines = lines,
                        isSynced = true,
                        source = "LRCLIB (Synced)"
                    )
                }
            }

            if (res.instrumental == true) {
                fallbackInstrumental = true
            }

            // If only plain lyrics found, remember as candidate and KEEP SEARCHING
            if (!res.plainLyrics.isNullOrBlank() && fallbackPlainResponse == null) {
                fallbackPlainResponse = res
            }
        }

        // Phase 2: Structured search queries - search broadly for any entry with synced lyrics
        val searchCandidates = mutableListOf<LrcLibResponse>()
        searchCandidates.addAll(querySearchEndpoint(cleanArtist, cleanTitle))
        if (primaryArtist != cleanArtist) {
            searchCandidates.addAll(querySearchEndpoint(primaryArtist, cleanTitle))
        }
        if (normTitle != cleanTitle) {
            searchCandidates.addAll(querySearchEndpoint(cleanArtist, normTitle))
        }
        if (cleanArtist != rawArtist || cleanTitle != rawTitle) {
            searchCandidates.addAll(querySearchEndpoint(rawArtist, rawTitle))
        }

        // Phase 3: Full-text keyword searches
        searchCandidates.addAll(querySearchByKeyword("$cleanArtist $cleanTitle"))
        if (primaryArtist != cleanArtist) {
            searchCandidates.addAll(querySearchByKeyword("$primaryArtist $cleanTitle"))
        }
        if (cleanArtist != rawArtist || cleanTitle != rawTitle) {
            searchCandidates.addAll(querySearchByKeyword("$rawArtist $rawTitle"))
        }

        // Phase 4: Evaluate all search results for ANY entry with synced lyrics
        if (searchCandidates.isNotEmpty()) {
            val syncedCandidates = searchCandidates.filter { !it.syncedLyrics.isNullOrBlank() }
            if (syncedCandidates.isNotEmpty()) {
                // Select entry closest to duration
                val bestSynced = syncedCandidates.minByOrNull { candidate ->
                    val candDur = candidate.duration?.toLong() ?: durationSec
                    if (durationSec > 0) abs(candDur - durationSec) else 0L
                } ?: syncedCandidates.first()

                val lines = LrcParser.parseSyncedLrc(bestSynced.syncedLyrics.orEmpty())
                if (lines.isNotEmpty()) {
                    return@withContext FetchedLyricsResult(
                        lines = lines,
                        isSynced = true,
                        source = "LRCLIB (Synced)"
                    )
                }
            }

            // If no synced in search results, check for better plain lyrics
            if (fallbackPlainResponse == null) {
                fallbackPlainResponse = searchCandidates.firstOrNull { !it.plainLyrics.isNullOrBlank() }
            }
            if (searchCandidates.any { it.instrumental == true }) {
                fallbackInstrumental = true
            }
        }

        // Phase 5: Only when NO synced lyrics exist anywhere, fall back to plain lyrics
        if (fallbackPlainResponse != null) {
            val plainLines = LrcParser.parsePlainLyrics(
                fallbackPlainResponse.plainLyrics.orEmpty(),
                durationMs
            )
            if (plainLines.isNotEmpty()) {
                return@withContext FetchedLyricsResult(
                    lines = plainLines,
                    isSynced = false,
                    statusMessage = "LRCLIB (Unsynced lyrics)",
                    source = "LRCLIB (Unsynced)"
                )
            }
        }

        if (fallbackInstrumental) {
            return@withContext FetchedLyricsResult(
                lines = listOf(LyricLine(0L, "♪ Instrumental Track ♪")),
                isSynced = false,
                statusMessage = "Instrumental track",
                source = "LRCLIB"
            )
        }

        null
    }

    suspend fun fetchSyncedOnly(
        artist: String,
        title: String,
        durationMs: Long
    ): FetchedLyricsResult? = withContext(Dispatchers.IO) {
        val result = fetchLyrics(artist, title, durationMs)
        if (result != null && result.isSynced && result.lines.isNotEmpty()) {
            result
        } else {
            null
        }
    }

    private fun queryGetEndpoint(
        artist: String,
        title: String,
        durationSec: Long
    ): LrcLibResponse? {
        if (artist.isBlank() || title.isBlank()) return null
        return try {
            val urlBuilder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
            if (durationSec > 0L) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
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
            val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
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

    private fun querySearchByKeyword(query: String): List<LrcLibResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return try {
            val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", trimmed)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
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

    companion object {
        private const val USER_AGENT = "DashLyrics/1.0.0 (Android; AndroidAuto)"
    }
}
