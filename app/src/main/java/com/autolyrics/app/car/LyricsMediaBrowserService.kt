package com.autolyrics.app.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.autolyrics.app.R
import com.autolyrics.app.data.LyricsState
import com.autolyrics.app.service.PlaybackSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LyricsMediaBrowserService : MediaBrowserService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSession? = null
    private var collectorJob: Job? = null

    private data class SyncTrigger(
        val activeIndex: Int,
        val trackTitle: String,
        val artistName: String,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val lineCount: Int
    )

    override fun onCreate() {
        super.onCreate()

        val session = MediaSession(this, "DashLyricsCarSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    PlaybackSyncEngine.togglePlayPause()
                }

                override fun onPause() {
                    PlaybackSyncEngine.togglePlayPause()
                }

                override fun onSkipToNext() {
                    PlaybackSyncEngine.skipToNext()
                }

                override fun onSkipToPrevious() {
                    PlaybackSyncEngine.skipToPrevious()
                }

                override fun onFastForward() {
                    PlaybackSyncEngine.seekForward30s()
                }

                override fun onRewind() {
                    PlaybackSyncEngine.seekBackward30s()
                }

                override fun onSeekTo(pos: Long) {
                    PlaybackSyncEngine.seekTo(pos)
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        ACTION_REWIND_30 -> PlaybackSyncEngine.seekBackward30s()
                        ACTION_FORWARD_30 -> PlaybackSyncEngine.seekForward30s()
                    }
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when (mediaId) {
                        ACTION_PLAY_PAUSE -> PlaybackSyncEngine.togglePlayPause()
                        ACTION_NEXT -> PlaybackSyncEngine.skipToNext()
                        ACTION_PREV -> PlaybackSyncEngine.skipToPrevious()
                        ACTION_REWIND_30 -> PlaybackSyncEngine.seekBackward30s()
                        ACTION_FORWARD_30 -> PlaybackSyncEngine.seekForward30s()
                    }
                }
            })
            isActive = true
        }
        mediaSession = session
        sessionToken = session.sessionToken

        collectorJob = serviceScope.launch {
            PlaybackSyncEngine.state
                .map { state ->
                    SyncTrigger(
                        activeIndex = state.activeIndex,
                        trackTitle = state.trackTitle,
                        artistName = state.artistName,
                        isPlaying = state.isPlaying,
                        isLoading = state.isLoading,
                        lineCount = state.lines.size
                    )
                }
                .distinctUntilChanged()
                .collect {
                    val current = PlaybackSyncEngine.state.value
                    updateCarSessionAndBrowseTree(current)
                }
        }
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        val state = PlaybackSyncEngine.state.value
        val items = mutableListOf<MediaBrowser.MediaItem>()

        when (parentId) {
            ROOT_ID -> {
                items.add(
                    buildBrowsableTab(
                        mediaId = TAB_SLIDING_WINDOW_ID,
                        title = "Live 3-Line Lyrics",
                        subtitle = if (state.trackTitle.isNotEmpty()) {
                            "${state.trackTitle} — ${state.artistName}"
                        } else {
                            "Real-time synced sliding window"
                        }
                    )
                )
                items.add(
                    buildBrowsableTab(
                        mediaId = TAB_ALL_LINES_ID,
                        title = "Full Song Lyrics",
                        subtitle = "All synced lines for current track"
                    )
                )
            }

            TAB_SLIDING_WINDOW_ID -> {
                val lyrics = state.lines
                val activeIndex = state.activeIndex

                val line1 = lyrics.getOrNull(activeIndex - 1)?.text ?: ""
                val line2 = "▶ ${lyrics.getOrNull(activeIndex)?.text ?: "..."}"
                val line3 = lyrics.getOrNull(activeIndex + 1)?.text ?: ""

                // Header / Track row (tap to Play/Pause)
                val headerTitle = if (state.trackTitle.isNotEmpty()) {
                    "${state.trackTitle} — ${state.artistName}"
                } else {
                    "Waiting for Spotify playback..."
                }
                items.add(
                    buildPlayableRow(
                        mediaId = ACTION_PLAY_PAUSE,
                        title = headerTitle,
                        subtitle = if (state.isPlaying) "PLAYING • Tap to Pause" else "PAUSED • Tap to Play"
                    )
                )

                // 3-Line Sliding Window
                items.add(
                    buildPlayableRow(
                        mediaId = "line_prev",
                        title = line1.ifEmpty { "·" },
                        subtitle = "Previous"
                    )
                )
                items.add(
                    buildPlayableRow(
                        mediaId = ACTION_PLAY_PAUSE,
                        title = line2,
                        subtitle = "Current Lyric Line"
                    )
                )
                items.add(
                    buildPlayableRow(
                        mediaId = "line_next",
                        title = line3.ifEmpty { "·" },
                        subtitle = "Up Next"
                    )
                )

                // Quick Jump actions
                items.add(
                    buildPlayableRow(
                        mediaId = ACTION_REWIND_30,
                        title = "↺ Replay 30 Seconds (-30s)",
                        subtitle = "Jump backward 30s"
                    )
                )
                items.add(
                    buildPlayableRow(
                        mediaId = ACTION_FORWARD_30,
                        title = "↻ Skip 30 Seconds (+30s)",
                        subtitle = "Jump forward 30s"
                    )
                )
            }

            TAB_ALL_LINES_ID -> {
                if (state.lines.isEmpty()) {
                    items.add(
                        buildPlayableRow(
                            mediaId = ACTION_PLAY_PAUSE,
                            title = state.statusMessage ?: "Play a song on Spotify to view lyrics",
                            subtitle = state.trackTitle.ifEmpty { "DashLyrics" }
                        )
                    )
                } else {
                    state.lines.forEachIndexed { idx, line ->
                        val isCurrent = idx == state.activeIndex
                        items.add(
                            buildPlayableRow(
                                mediaId = "lyric_$idx",
                                title = if (isCurrent) "▶ ${line.text}" else line.text,
                                subtitle = if (isCurrent) "ACTIVE" else formatTime(line.timeMs)
                            )
                        )
                    }
                }
            }
        }

        result.sendResult(items)
    }

    private fun updateCarSessionAndBrowseTree(state: LyricsState) {
        val session = mediaSession ?: return
        val lyrics = state.lines
        val activeIndex = state.activeIndex

        val line1 = lyrics.getOrNull(activeIndex - 1)?.text ?: ""
        val line2 = "▶ ${lyrics.getOrNull(activeIndex)?.text ?: "..."}"
        val line3 = lyrics.getOrNull(activeIndex + 1)?.text ?: ""

        val displayTitle = when {
            lyrics.isNotEmpty() -> line2
            state.isLoading -> "Fetching lyrics..."
            state.trackTitle.isNotEmpty() -> state.trackTitle
            else -> "DashLyrics — Play Spotify"
        }

        val displayArtist = when {
            line3.isNotEmpty() -> "$line3  •  ${state.trackTitle}"
            state.trackTitle.isNotEmpty() -> "${state.trackTitle} — ${state.artistName}"
            else -> "Waiting for Spotify playback"
        }

        val durationMs = PlaybackSyncEngine.getCurrentDurationMs()
        val currentPosMs = PlaybackSyncEngine.getCurrentPositionMs()

        val lyricCardBitmap = renderLyricsBitmap(
            trackTitle = state.trackTitle,
            artistName = state.artistName,
            line1 = line1,
            line2 = lyrics.getOrNull(activeIndex)?.text
                ?: if (state.isLoading) "Loading..." else state.statusMessage ?: "Waiting for Spotify...",
            line3 = line3
        )

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, displayArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, line1)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (durationMs > 0) durationMs else -1L)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, lyricCardBitmap)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, lyricCardBitmap)
            .build()

        session.setMetadata(metadata)

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_SEEK_TO

        val rewindCustomAction = PlaybackState.CustomAction.Builder(
            ACTION_REWIND_30,
            "-30s",
            R.drawable.ic_replay_30
        ).build()

        val forwardCustomAction = PlaybackState.CustomAction.Builder(
            ACTION_FORWARD_30,
            "+30s",
            R.drawable.ic_forward_30
        ).build()

        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .addCustomAction(rewindCustomAction)
            .addCustomAction(forwardCustomAction)
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                currentPosMs,
                if (state.isPlaying) 1.0f else 0.0f,
                SystemClock.elapsedRealtime()
            )
            .build()

        session.setPlaybackState(pbState)

        notifyChildrenChanged(ROOT_ID)
        notifyChildrenChanged(TAB_SLIDING_WINDOW_ID)
        notifyChildrenChanged(TAB_ALL_LINES_ID)
    }

    private fun renderLyricsBitmap(
        trackTitle: String,
        artistName: String,
        line1: String,
        line2: String,
        line3: String
    ): Bitmap {
        val size = 640
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#121212"))

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1DB954")
            strokeWidth = 6f
        }
        canvas.drawLine(40f, 96f, size - 40f, 96f, accentPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1DB954")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerText = if (trackTitle.isNotEmpty()) "$trackTitle • $artistName" else "DashLyrics"
        canvas.drawText(
            headerText.take(36),
            40f,
            68f,
            headerPaint
        )

        val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#888888")
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val activePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        drawMultilineText(canvas, line1, mutedPaint, 40, 130, size - 80)
        drawMultilineText(canvas, "▶ $line2", activePaint, 40, 250, size - 80)
        drawMultilineText(canvas, line3, mutedPaint, 40, 420, size - 80)

        // Safety disclaimer badge rendered permanently on Android Auto screen
        val warningPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFA726")
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("⚠️ PASSENGER USE ONLY • DO NOT READ WHILE DRIVING", 40f, 608f, warningPaint)

        return bmp
    }

    private fun drawMultilineText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Int,
        y: Int,
        width: Int
    ) {
        if (text.isBlank()) return
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .build()
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildBrowsableTab(
        mediaId: String,
        title: String,
        subtitle: String
    ): MediaBrowser.MediaItem {
        val desc = MediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_BROWSABLE)
    }

    private fun buildPlayableRow(
        mediaId: String,
        title: String,
        subtitle: String
    ): MediaBrowser.MediaItem {
        val desc = MediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_PLAYABLE)
    }

    private fun formatTime(timeMs: Long): String {
        val totalSec = (timeMs / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return "%02d:%02d".format(min, sec)
    }

    companion object {
        private const val ROOT_ID = "dashlyrics_root"
        private const val TAB_SLIDING_WINDOW_ID = "tab_sliding_window"
        private const val TAB_ALL_LINES_ID = "tab_all_lines"
        private const val ACTION_PLAY_PAUSE = "action_play_pause"
        private const val ACTION_NEXT = "action_next"
        private const val ACTION_PREV = "action_prev"
        private const val ACTION_REWIND_30 = "action_rewind_30"
        private const val ACTION_FORWARD_30 = "action_forward_30"

        private const val CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
    }
}
