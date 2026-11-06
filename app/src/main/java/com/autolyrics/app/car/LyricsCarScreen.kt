package com.autolyrics.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.autolyrics.app.R
import com.autolyrics.app.data.LyricsState
import com.autolyrics.app.service.PlaybackSyncEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LyricsCarScreen(carContext: CarContext) : Screen(carContext) {

    private var currentState: LyricsState = PlaybackSyncEngine.state.value
    private var collectorJob: Job? = null

    private data class RenderTrigger(
        val activeIndex: Int,
        val trackTitle: String,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val lineCount: Int
    )

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                try {
                    androidx.car.app.CarToast.makeText(
                        carContext,
                        "⚠️ Passenger Use Only: Do not read lyrics while driving",
                        androidx.car.app.CarToast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                }

                collectorJob = owner.lifecycleScope.launch {
                    PlaybackSyncEngine.state
                        .map { state ->
                            currentState = state
                            RenderTrigger(
                                activeIndex = state.activeIndex,
                                trackTitle = state.trackTitle,
                                isPlaying = state.isPlaying,
                                isLoading = state.isLoading,
                                lineCount = state.lines.size
                            )
                        }
                        .distinctUntilChanged()
                        .collect {
                            invalidate()
                        }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                collectorJob?.cancel()
                collectorJob = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = currentState
        val lyrics = state.lines
        val activeIndex = state.activeIndex

        // 3-line sliding window as specified in Section 4.E
        val line1Text: String
        val line2Text: String
        val line3Text: String

        if (lyrics.isEmpty()) {
            line1Text = ""
            line2Text = state.statusMessage ?: if (state.trackTitle.isEmpty()) "Waiting for Spotify..." else "Searching lyrics..."
            line3Text = ""
        } else {
            line1Text = lyrics.getOrNull(activeIndex - 1)?.text ?: ""
            line2Text = "▶ ${lyrics.getOrNull(activeIndex)?.text ?: "..."}"
            line3Text = lyrics.getOrNull(activeIndex + 1)?.text ?: ""
        }

        val paneBuilder = Pane.Builder()

        if (state.isLoading) {
            paneBuilder.setLoading(true)
        } else {
            // Line 1: Previous lyric line (faded / subtitle)
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(line1Text.ifEmpty { " " })
                    .addText(if (line1Text.isNotEmpty()) "Previous" else " ")
                    .build()
            )

            // Line 2: Active lyric line (prominent)
            val centerSubtitle = when {
                state.trackTitle.isEmpty() -> "Waiting for Spotify playback..."
                lyrics.isEmpty() -> if (state.artistName.isNotEmpty()) "${state.artistName} • No lyrics found" else "No lyrics found"
                state.isPlaying -> {
                    val src = state.source?.let { " • $it" } ?: ""
                    "${state.artistName}$src • Playing"
                }
                else -> {
                    val src = state.source?.let { " • $it" } ?: ""
                    "${state.artistName}$src • Paused"
                }
            }
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(line2Text)
                    .addText(centerSubtitle)
                    .build()
            )

            // Line 3: Upcoming lyric line (faded / subtitle)
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(line3Text.ifEmpty { " " })
                    .addText(if (line3Text.isNotEmpty()) "Up Next" else " ")
                    .build()
            )

            // Quick-jump controls on the Pane (up to 2 actions allowed by PaneTemplate)
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("-30s")
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_replay_30)
                        ).build()
                    )
                    .setOnClickListener {
                        PlaybackSyncEngine.seekBackward30s()
                    }
                    .build()
            )

            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("+30s")
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_forward_30)
                        ).build()
                    )
                    .setOnClickListener {
                        PlaybackSyncEngine.seekForward30s()
                    }
                    .build()
            )
        }

        val headerTitle = if (state.trackTitle.isNotEmpty()) {
            if (state.artistName.isNotEmpty()) {
                "${state.trackTitle} — ${state.artistName}"
            } else {
                state.trackTitle
            }
        } else {
            "DashLyrics"
        }

        // ActionStrip strictly limited to 2 actions by ACTIONS_CONSTRAINTS_SIMPLE
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext,
                                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        PlaybackSyncEngine.togglePlayPause()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_refresh)
                        ).build()
                    )
                    .setOnClickListener {
                        PlaybackSyncEngine.retryFetch()
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(headerTitle)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .build()
    }
}
