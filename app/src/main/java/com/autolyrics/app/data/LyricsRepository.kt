package com.autolyrics.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsRepository(
    private val context: Context? = null,
    private val lrcLibClient: LrcLibClient = LrcLibClient(),
    private val karalyrClient: KaralyrClient = KaralyrClient(),
    private val lyricsOvhClient: LyricsOvhClient = LyricsOvhClient(),
    private val spotifyLyricsClient: SpotifyLyricsClient = SpotifyLyricsClient()
) {

    suspend fun fetchLyricsCascade(
        rawArtist: String,
        rawTitle: String,
        durationMs: Long,
        spotifyTrackId: String? = null
    ): FetchedLyricsResult = withContext(Dispatchers.IO) {
        val cleanArtist = LrcParser.sanitizeArtist(rawArtist)
        val cleanTitle = LrcParser.sanitizeTitle(rawTitle)

        var fallbackUnsynced: FetchedLyricsResult? = null

        // Tier 0: Spotify Direct (if user saved sp_dc cookie in preferences)
        val spDcCookie = getSpDcCookie()
        if (!spDcCookie.isNullOrBlank()) {
            val spotifyResult = spotifyLyricsClient.fetchLyricsWithSpDc(
                spDcCookie = spDcCookie,
                artist = cleanArtist,
                title = cleanTitle,
                trackId = spotifyTrackId
            )
            if (spotifyResult != null && spotifyResult.lines.isNotEmpty()) {
                if (spotifyResult.isSynced) {
                    return@withContext spotifyResult
                } else if (fallbackUnsynced == null) {
                    fallbackUnsynced = spotifyResult
                }
            }
        }

        // Tier 1: LRCLIB (Aggressive multi-strategy synced search)
        val lrcClean = lrcLibClient.fetchLyrics(
            rawArtist = cleanArtist,
            rawTitle = cleanTitle,
            durationMs = durationMs
        )
        if (lrcClean != null && lrcClean.lines.isNotEmpty()) {
            if (lrcClean.isSynced) {
                return@withContext lrcClean
            } else if (fallbackUnsynced == null) {
                fallbackUnsynced = lrcClean
            }
        }

        // Tier 2: LRCLIB with raw unmodified title/artist (in case sanitizer altered a critical keyword)
        if (cleanArtist != rawArtist || cleanTitle != rawTitle) {
            val lrcRaw = lrcLibClient.fetchLyrics(
                rawArtist = rawArtist,
                rawTitle = rawTitle,
                durationMs = durationMs
            )
            if (lrcRaw != null && lrcRaw.lines.isNotEmpty()) {
                if (lrcRaw.isSynced) {
                    return@withContext lrcRaw
                } else if (fallbackUnsynced == null) {
                    fallbackUnsynced = lrcRaw
                }
            }
        }

        // Tier 3: Karalyr API (Secondary synced database)
        val karalyrResult = karalyrClient.fetchLyrics(
            artist = cleanArtist,
            title = cleanTitle,
            durationMs = durationMs
        )
        if (karalyrResult != null && karalyrResult.lines.isNotEmpty()) {
            if (karalyrResult.isSynced) {
                return@withContext karalyrResult
            } else if (fallbackUnsynced == null) {
                fallbackUnsynced = karalyrResult
            }
        }

        // Tier 4: Lyrics.ovh (Free public plain text lyrics failover if nothing found yet)
        if (fallbackUnsynced == null) {
            val ovhResult = lyricsOvhClient.fetchPlainLyrics(
                artist = cleanArtist,
                title = cleanTitle,
                durationMs = durationMs
            )
            if (ovhResult != null && ovhResult.lines.isNotEmpty()) {
                fallbackUnsynced = ovhResult
            }
        }

        // If any unsynced lyrics were found across any tier, return them
        if (fallbackUnsynced != null) {
            return@withContext fallbackUnsynced
        }

        // Tier 5: No lyrics found across any provider
        FetchedLyricsResult(
            lines = emptyList(),
            isSynced = false,
            statusMessage = "No lyrics found across providers",
            source = "None"
        )
    }

    suspend fun searchForSyncedUpgrade(
        rawArtist: String,
        rawTitle: String,
        durationMs: Long,
        spotifyTrackId: String? = null
    ): FetchedLyricsResult? = withContext(Dispatchers.IO) {
        val cleanArtist = LrcParser.sanitizeArtist(rawArtist)
        val cleanTitle = LrcParser.sanitizeTitle(rawTitle)

        // 1. Check Spotify Direct if cookie configured
        val spDcCookie = getSpDcCookie()
        if (!spDcCookie.isNullOrBlank()) {
            val spotify = spotifyLyricsClient.fetchLyricsWithSpDc(
                spDcCookie = spDcCookie,
                artist = cleanArtist,
                title = cleanTitle,
                trackId = spotifyTrackId
            )
            if (spotify != null && spotify.isSynced && spotify.lines.isNotEmpty()) {
                return@withContext spotify
            }
        }

        // 2. Check LRCLIB specifically for synced
        val lrcSynced = lrcLibClient.fetchSyncedOnly(cleanArtist, cleanTitle, durationMs)
            ?: if (cleanArtist != rawArtist || cleanTitle != rawTitle) {
                lrcLibClient.fetchSyncedOnly(rawArtist, rawTitle, durationMs)
            } else null

        if (lrcSynced != null && lrcSynced.isSynced && lrcSynced.lines.isNotEmpty()) {
            return@withContext lrcSynced
        }

        // 3. Check Karalyr
        val karalyr = karalyrClient.fetchLyrics(cleanArtist, cleanTitle, durationMs)
        if (karalyr != null && karalyr.isSynced && karalyr.lines.isNotEmpty()) {
            return@withContext karalyr
        }

        null
    }

    fun getSpDcCookie(): String? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences("dashlyrics_prefs", Context.MODE_PRIVATE)
        return prefs.getString("spotify_sp_dc", null)?.trim()
    }

    fun hasSpotifyAuth(): Boolean {
        return !getSpDcCookie().isNullOrBlank()
    }

    fun saveSpDcCookie(cookie: String) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("dashlyrics_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("spotify_sp_dc", cookie.trim()).apply()
    }

    fun clearSpDcCookie() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("dashlyrics_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("spotify_sp_dc").apply()
    }
}
