package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import me.timschneeberger.rootlessjamesdsp.analysis.TrackBoundaryEvent
import me.timschneeberger.rootlessjamesdsp.analysis.TrackBoundaryReason
import me.timschneeberger.rootlessjamesdsp.analysis.TrackIdentity
import me.timschneeberger.rootlessjamesdsp.service.NotificationListenerService
import timber.log.Timber

class MediaSessionTrackObserver(
    private val context: Context,
    private val onTrackBoundary: (TrackBoundaryEvent) -> Unit
) {
    private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, NotificationListenerService::class.java)
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        refreshControllers()
    }
    private var lastIdentity: TrackIdentity? = null
    private var started = false

    fun startIfPermitted() {
        if (started) return

        if (!isNotificationListenerEnabled()) {
            Timber.i("Tonality metadata tracking disabled; notification listener not enabled")
            return
        }

        started = true
        refreshControllers()
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
        }
        catch (ex: SecurityException) {
            Timber.w(ex, "Failed to observe active media sessions")
            started = false
        }
    }

    fun stop() {
        callbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        callbacks.clear()

        if (started) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        started = false
    }

    private fun refreshControllers() {
        val controllers = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        }
        catch (ex: SecurityException) {
            Timber.w(ex, "Failed to query active media sessions")
            return
        }

        val activeControllers = controllers.toSet()
        callbacks.keys.filterNot { it in activeControllers }.forEach { controller ->
            callbacks.remove(controller)?.let(controller::unregisterCallback)
        }

        controllers.forEach { controller ->
            if (callbacks.containsKey(controller)) return@forEach

            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    maybeEmitBoundary(metadata.toTrackIdentity(controller.packageName), TrackBoundaryReason.MetadataChanged)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING && state.position in 0L..1_500L) {
                        maybeEmitBoundary(
                            controller.metadata.toTrackIdentity(controller.packageName),
                            TrackBoundaryReason.PlaybackRestarted
                        )
                    }
                }
            }

            controller.registerCallback(callback)
            callbacks[controller] = callback
            maybeEmitBoundary(controller.metadata.toTrackIdentity(controller.packageName), TrackBoundaryReason.MetadataChanged)
        }
    }

    private fun maybeEmitBoundary(next: TrackIdentity?, reason: TrackBoundaryReason) {
        if (next == null || !hasUsefulIdentity(next)) return

        val changed = next.mediaId != lastIdentity?.mediaId ||
            next.title != lastIdentity?.title ||
            next.artist != lastIdentity?.artist ||
            next.album != lastIdentity?.album ||
            next.durationMs != lastIdentity?.durationMs ||
            next.packageName != lastIdentity?.packageName

        if (!changed) return

        lastIdentity = next
        onTrackBoundary(
            TrackBoundaryEvent(
                timestampNs = System.nanoTime(),
                identity = next,
                confidence = 0.95f,
                reason = reason
            )
        )
    }

    private fun hasUsefulIdentity(identity: TrackIdentity): Boolean {
        return identity.mediaId != null || identity.title != null || identity.artist != null || identity.album != null
    }

    private fun MediaMetadata?.toTrackIdentity(packageName: String?): TrackIdentity? {
        if (this == null) return null

        return TrackIdentity(
            packageName = packageName,
            mediaId = getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
        )
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it.packageName == context.packageName && it.className == listenerComponent.className }
    }
}
