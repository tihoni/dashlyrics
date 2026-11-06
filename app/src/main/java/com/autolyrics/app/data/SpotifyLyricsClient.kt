package com.autolyrics.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class SpotifyTokenResponse(
    val accessToken: String? = null,
    val accessTokenExpirationTimestampMs: Long? = null,
    val isAnonymous: Boolean? = null
)

@Serializable
data class SpotifyColorLyricsResponse(
    val lyrics: SpotifyLyricsPayload? = null
)

@Serializable
data class SpotifyLyricsPayload(
    val syncType: String? = null,
    val lines: List<SpotifyLyricLineItem>? = null
)

@Serializable
data class SpotifyLyricLineItem(
    val startTimeMs: String? = null,
    val words: String? = null
)

@Serializable
data class SpotifySearchTrackResponse(
    val tracks: SpotifyTracksPaging? = null
)

@Serializable
data class SpotifyTracksPaging(
    val items: List<SpotifyTrackSummaryItem>? = null
)

@Serializable
data class SpotifyTrackSummaryItem(
    val id: String? = null,
    val name: String? = null
)

class SpotifyLyricsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiryMs: Long = 0L

    suspend fun fetchLyricsWithSpDc(
        spDcCookie: String,
        artist: String,
        title: String,
        trackId: String? = null
    ): FetchedLyricsResult? = withContext(Dispatchers.IO) {
        if (spDcCookie.isBlank()) return@withContext null

        val token = getValidAccessToken(spDcCookie) ?: return@withContext null
        val targetTrackId = trackId?.removePrefix("spotify:track:")
            ?: resolveTrackId(token, artist, title)
            ?: return@withContext null

        queryColorLyrics(token, targetTrackId)
    }

    private fun getValidAccessToken(spDcCookie: String): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < (tokenExpiryMs - 60_000L)) {
            return cachedToken
        }

        return try {
            val request = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", WEB_USER_AGENT)
                .header("Cookie", "sp_dc=${spDcCookie.trim()}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val parsed = json.decodeFromString<SpotifyTokenResponse>(body)
                val token = parsed.accessToken
                if (!token.isNullOrBlank() && parsed.isAnonymous != true) {
                    cachedToken = token
                    tokenExpiryMs = parsed.accessTokenExpirationTimestampMs
                        ?: (now + 3600_000L)
                    token
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTrackId(token: String, artist: String, title: String): String? {
        val cleanArtist = LrcParser.sanitizeArtist(artist)
        val cleanTitle = LrcParser.sanitizeTitle(title)
        val query = "$cleanArtist $cleanTitle".trim()
        if (query.isBlank()) return null

        return try {
            val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("type", "track")
                .addQueryParameter("limit", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", WEB_USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val parsed = json.decodeFromString<SpotifySearchTrackResponse>(body)
                parsed.tracks?.items?.firstOrNull()?.id
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryColorLyrics(token: String, trackId: String): FetchedLyricsResult? {
        return try {
            val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId?format=json&vocalRemoval=false&market=from_token".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("app-platform", "WebPlayer")
                .header("User-Agent", WEB_USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val parsed = json.decodeFromString<SpotifyColorLyricsResponse>(body)
                val payload = parsed.lyrics ?: return null
                val rawLines = payload.lines ?: return null

                val isSynced = payload.syncType?.contains("SYNCED", ignoreCase = true) == true
                val lines = rawLines.mapNotNull { item ->
                    val text = item.words?.trim().orEmpty()
                    val timeMs = item.startTimeMs?.toLongOrNull() ?: 0L
                    if (text.isNotEmpty()) LyricLine(timeMs, text) else null
                }

                if (lines.isNotEmpty()) {
                    FetchedLyricsResult(
                        lines = lines,
                        isSynced = isSynced,
                        statusMessage = if (isSynced) "Spotify Official (Synced)" else "Spotify Official (Unsynced)",
                        source = "Spotify Direct"
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
