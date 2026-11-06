package com.autolyrics.app.service

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import com.autolyrics.app.data.FetchedLyricsResult
import com.autolyrics.app.data.LrcParser
import com.autolyrics.app.data.LyricsRepository
import com.autolyrics.app.data.LyricsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PlaybackSyncEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lyricsRepository = LyricsRepository()

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    @Volatile
    private var lastRecordedPos: Long = 0L

    @Volatile
    private var lastUpdateTime: Long = 0L

    @Volatile
    private var playbackSpeed: Float = 1.0f

    @Volatile
    private var currentDurationMs: Long = 0L

    @Volatile
    private var activeController: MediaController? = null

    private var fetchJob: Job? = null
    private var upgradeJob: Job? = null
    private var tickerJob: Job? = null
    private var lastFetchedKey: String = ""

    fun initialize(context: Context) {
        lyricsRepository = LyricsRepository(context.applicationContext)
    }

    fun getLyricsRepository(): LyricsRepository = lyricsRepository

    fun attachMediaController(controller: MediaController?) {
        activeController = controller
    }

    fun onMetadataUpdated(
        title: String,
        artist: String,
        durationMs: Long,
        spotifyTrackId: String? = null
    ) {
        val cleanTitle = LrcParser.sanitizeTitle(title)
        val cleanArtist = LrcParser.sanitizeArtist(artist)

        if (cleanTitle.isEmpty() && cleanArtist.isEmpty()) {
            return
        }

        currentDurationMs = durationMs
        val trackKey = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"

        if (trackKey == lastFetchedKey && (_state.value.lines.isNotEmpty() || _state.value.isLoading)) {
            return
        }

        lastFetchedKey = trackKey
        fetchJob?.cancel()
        upgradeJob?.cancel()

        _state.update {
            it.copy(
                trackTitle = cleanTitle,
                artistName = cleanArtist,
                lines = emptyList(),
                activeIndex = -1,
                isLoading = true,
                isSynced = true,
                statusMessage = null,
                source = null
            )
        }

        fetchJob = scope.launch {
            val result: FetchedLyricsResult = lyricsRepository.fetchLyricsCascade(
                rawArtist = cleanArtist,
                rawTitle = cleanTitle,
                durationMs = durationMs,
                spotifyTrackId = spotifyTrackId
            )

            val initialPos = computeCurrentPositionMs()
            val initialActiveIndex = if (result.isSynced && result.lines.isNotEmpty()) {
                result.lines.indexOfLast { it.timeMs <= initialPos }.coerceAtLeast(0)
            } else if (result.lines.isNotEmpty()) {
                0
            } else {
                -1
            }

            _state.update { current ->
                current.copy(
                    lines = result.lines,
                    activeIndex = initialActiveIndex,
                    isLoading = false,
                    isSynced = result.isSynced,
                    statusMessage = result.statusMessage,
                    source = result.source
                )
            }

            // If only unsynced lyrics were initially available, keep looking for a synced version in the background
            if (!result.isSynced && result.lines.isNotEmpty()) {
                upgradeJob = scope.launch {
                    val syncedUpgrade = lyricsRepository.searchForSyncedUpgrade(
                        rawArtist = cleanArtist,
                        rawTitle = cleanTitle,
                        durationMs = durationMs,
                        spotifyTrackId = spotifyTrackId
                    )
                    if (syncedUpgrade != null && syncedUpgrade.isSynced && syncedUpgrade.lines.isNotEmpty()) {
                        val current = _state.value
                        if (current.trackTitle == cleanTitle && current.artistName == cleanArtist) {
                            val currentPos = computeCurrentPositionMs()
                            val newIndex = syncedUpgrade.lines.indexOfLast { it.timeMs <= currentPos }.coerceAtLeast(0)
                            _state.update {
                                it.copy(
                                    lines = syncedUpgrade.lines,
                                    activeIndex = newIndex,
                                    isSynced = true,
                                    statusMessage = null,
                                    source = syncedUpgrade.source
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun retryFetch() {
        val current = _state.value
        if (current.trackTitle.isNotEmpty()) {
            lastFetchedKey = ""
            onMetadataUpdated(
                title = current.trackTitle,
                artist = current.artistName,
                durationMs = currentDurationMs
            )
        }
    }

    fun onPlaybackStateUpdated(
        playbackStateCode: Int,
        positionMs: Long,
        speed: Float,
        lastPositionUpdateTimeMs: Long
    ) {
        val isPlayingNow = playbackStateCode == PlaybackState.STATE_PLAYING
        lastRecordedPos = positionMs.coerceAtLeast(0L)
        playbackSpeed = if (speed > 0f) speed else 1.0f
        lastUpdateTime = if (lastPositionUpdateTimeMs > 0L) {
            lastPositionUpdateTimeMs
        } else {
            SystemClock.elapsedRealtime()
        }

        val currentPos = computeCurrentPositionMs()
        _state.update { current ->
            val newIndex = if (current.isSynced && current.lines.isNotEmpty()) {
                current.lines.indexOfLast { it.timeMs <= currentPos }
            } else if (current.lines.isNotEmpty()) {
                current.activeIndex.coerceAtLeast(0)
            } else {
                -1
            }
            current.copy(
                isPlaying = isPlayingNow,
                activeIndex = newIndex
            )
        }

        if (isPlayingNow) {
            startTicker()
        } else {
            stopTicker()
        }
    }

    fun onSessionCleared() {
        stopTicker()
        activeController = null
        _state.update { current ->
            current.copy(isPlaying = false)
        }
    }

    fun togglePlayPause() {
        val controller = activeController ?: return
        val pbState = controller.playbackState?.state
        if (pbState == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipToNext() {
        activeController?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun seekRelative(offsetMs: Long) {
        val controller = activeController ?: return
        val currentPos = computeCurrentPositionMs()
        val maxDuration = if (currentDurationMs > 0) currentDurationMs else Long.MAX_VALUE
        val targetPos = (currentPos + offsetMs).coerceIn(0L, maxDuration)

        lastRecordedPos = targetPos
        lastUpdateTime = SystemClock.elapsedRealtime()

        val lines = _state.value.lines
        if (lines.isNotEmpty()) {
            val newIndex = lines.indexOfLast { it.timeMs <= targetPos }.coerceAtLeast(0)
            _state.update { it.copy(activeIndex = newIndex) }
        }

        controller.transportControls.seekTo(targetPos)
    }

    fun seekForward30s() {
        seekRelative(30_000L)
    }

    fun seekBackward30s() {
        seekRelative(-30_000L)
    }

    fun seekTo(targetPosMs: Long) {
        val controller = activeController ?: return
        val maxDuration = if (currentDurationMs > 0) currentDurationMs else Long.MAX_VALUE
        val targetPos = targetPosMs.coerceIn(0L, maxDuration)

        lastRecordedPos = targetPos
        lastUpdateTime = SystemClock.elapsedRealtime()

        val lines = _state.value.lines
        if (lines.isNotEmpty()) {
            val newIndex = lines.indexOfLast { it.timeMs <= targetPos }.coerceAtLeast(0)
            _state.update { it.copy(activeIndex = newIndex) }
        }

        controller.transportControls.seekTo(targetPos)
    }

    fun getCurrentPositionMs(): Long = computeCurrentPositionMs()

    fun getCurrentDurationMs(): Long = currentDurationMs

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && _state.value.isPlaying) {
                val currentSnapshot = _state.value
                if (currentSnapshot.lines.isNotEmpty()) {
                    val currentPosMs = computeCurrentPositionMs()
                    val calculatedIndex = currentSnapshot.lines.indexOfLast { it.timeMs <= currentPosMs }
                    if (calculatedIndex != currentSnapshot.activeIndex && calculatedIndex != -1) {
                        _state.update { it.copy(activeIndex = calculatedIndex) }
                    }
                }
                delay(50L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun computeCurrentPositionMs(): Long {
        if (!_state.value.isPlaying) {
            return lastRecordedPos
        }
        val elapsed = (SystemClock.elapsedRealtime() - lastUpdateTime).coerceAtLeast(0L)
        return lastRecordedPos + (elapsed * playbackSpeed).toLong()
    }
}
