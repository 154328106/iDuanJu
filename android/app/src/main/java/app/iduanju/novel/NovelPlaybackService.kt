package app.iduanju.novel

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Hosts [NovelPlayback]'s session so an audiobook plays on in the background with notification and lock-screen controls. */
@androidx.annotation.OptIn(UnstableApi::class)
class NovelPlaybackService : MediaSessionService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = NovelPlayback.session ?: run { stopSelf(); return START_NOT_STICKY }
        if (session !in sessions) addSession(session)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = NovelPlayback.session

    // Changing chapters pauses for a moment; leaving the foreground then would let the system stop a screen-off playback.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) =
        super.onUpdateNotification(session, startInForegroundRequired || NovelPlayback.changing)
}
