package com.tube.tv.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tube.tv.TubeApp

/**
 * Thin host for the notification / foreground state. The session and player are owned by
 * [PlaybackManager]; this service only exists while a video is loaded.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        val session = manager().session
        if (session == null) {
            stopSelf()
            return
        }
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = manager().session

    override fun onTaskRemoved(rootIntent: Intent?) {
        manager().stop()
        stopSelf()
    }

    private fun manager() = (application as TubeApp).container.playbackManager
}
