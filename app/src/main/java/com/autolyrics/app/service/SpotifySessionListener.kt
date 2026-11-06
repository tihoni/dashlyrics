package com.autolyrics.app.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autolyrics.app.data.LrcParser

class SpotifySessionListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var spotifyController: MediaController? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindToSpotifyController(controllers)
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            unregisterSpotifyController()
            PlaybackSyncEngine.onSessionCleared()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        PlaybackSyncEngine.initialize(this)
        connectMediaSessionManager()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE && spotifyController == null) {
            refreshActiveSessions()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        unregisterSpotifyController()
        PlaybackSyncEngine.onSessionCleared()
    }

    override fun onDestroy() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        unregisterSpotifyController()
        super.onDestroy()
    }

    private fun connectMediaSessionManager() {
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        mediaSessionManager = manager
        val componentName = ComponentName(this, SpotifySessionListener::class.java)

        try {
            manager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }

        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            val currentControllers = manager.getActiveSessions(componentName)
            bindToSpotifyController(currentControllers)
        } catch (_: SecurityException) {
            // Notification listener permission not yet granted
        }
    }

    private fun refreshActiveSessions() {
        val manager = mediaSessionManager ?: return
        val componentName = ComponentName(this, SpotifySessionListener::class.java)
        try {
            val currentControllers = manager.getActiveSessions(componentName)
            bindToSpotifyController(currentControllers)
        } catch (_: SecurityException) {
        }
    }

    private fun bindToSpotifyController(controllers: List<MediaController>?) {
        val matchingController = controllers?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }

        if (matchingController == null) {
            unregisterSpotifyController()
            PlaybackSyncEngine.onSessionCleared()
            return
        }

        if (spotifyController?.sessionToken == matchingController.sessionToken) {
            // Still the same session; ensure state is synced
            PlaybackSyncEngine.attachMediaController(matchingController)
            handleMetadata(matchingController.metadata)
            handlePlaybackState(matchingController.playbackState)
            return
        }

        unregisterSpotifyController()
        spotifyController = matchingController
        matchingController.registerCallback(controllerCallback)
        PlaybackSyncEngine.attachMediaController(matchingController)

        // Immediately sync current metadata and playback state
        handleMetadata(matchingController.metadata)
        handlePlaybackState(matchingController.playbackState)
    }

    private fun unregisterSpotifyController() {
        try {
            spotifyController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {
        }
        spotifyController = null
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val descTitle = metadata.description.title?.toString()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: descTitle
            ?: ""

        val subtitle = metadata.description.subtitle?.toString()
        val metaArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        // Prefer pristine subtitle from MediaDescription if available without quality bullets
        val chosenArtist = when {
            !subtitle.isNullOrBlank() && !subtitle.contains("•") -> subtitle
            metaArtist.isNotBlank() -> LrcParser.sanitizeArtist(metaArtist)
            !subtitle.isNullOrBlank() -> LrcParser.sanitizeArtist(subtitle)
            else -> ""
        }

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val trackId = metadata.description.mediaId
            ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)

        PlaybackSyncEngine.onMetadataUpdated(
            title = title,
            artist = chosenArtist,
            durationMs = duration,
            spotifyTrackId = trackId
        )
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        if (state == null) return
        PlaybackSyncEngine.onPlaybackStateUpdated(
            playbackStateCode = state.state,
            positionMs = state.position,
            speed = state.playbackSpeed,
            lastPositionUpdateTimeMs = state.lastPositionUpdateTime
        )
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
