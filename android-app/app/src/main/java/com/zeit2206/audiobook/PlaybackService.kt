package com.zeit2206.audiobook

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Audiobook playback lives here, not in the activity, so it keeps going with the
 * screen locked. Media3 posts the media notification and lock-screen controls itself.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    // Once a minute while playing: memory + what's playing, to see what led up to a stop or a kill.
    private val heartbeat = object : Runnable {
        override fun run() {
            session?.player?.let { p ->
                Diag.log("play", "playing ${p.currentMediaItem?.mediaMetadata?.title} at ${p.currentPosition / 1000}s · ${Diag.memory()}")
            }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        // Tapping the notification / lock-screen player brings the app back (same screen if it's still running).
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        Diag.log("service", "created")
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // reason tells a user pause from audio focus loss, headphones unplugged, end of media…
                val why = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user/app"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost (call/other app)"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones unplugged"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote/notification"
                    else -> "reason $reason"
                }
                Diag.log("play", (if (playWhenReady) "play" else "pause") + " · $why · ${player.currentMediaItem?.mediaMetadata?.title}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(heartbeat)
                if (isPlaying) handler.postDelayed(heartbeat, 60_000)
            }
            override fun onPlayerError(error: PlaybackException) {
                Diag.log("error", "${error.errorCodeName}: ${error.message} · ${player.currentMediaItem?.mediaId} · ${Diag.memory()}")
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.log("service", "task removed (app swiped away)")
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        Diag.log("service", "destroyed")
        handler.removeCallbacks(heartbeat)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
