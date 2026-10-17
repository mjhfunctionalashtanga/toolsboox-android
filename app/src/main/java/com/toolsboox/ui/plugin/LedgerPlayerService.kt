package com.toolsboox.ui.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import timber.log.Timber

/**
 * THE LIFECYCLE ANCHOR — playback must survive leaving the app (Michael, 08-30: "apparently
 * leaving the app and coming back can stop the playback too").
 *
 * [LedgerPlayer] is a process-wide singleton, so nothing in a fragment's death was releasing the
 * MediaPlayer — what killed background listening was the PROCESS's standing: with no foreground
 * service, a backgrounded app on these ROMs (Boox power management is aggressive even by e-ink
 * standards) is freezable, and a frozen process is a silent player. This service exists exactly
 * as long as real audio does: [LedgerPlayer.startAudio] starts it foreground, [LedgerPlayer]'s
 * stop/complete path stops it. Ownership of the MediaPlayer STAYS in [LedgerPlayer] — the service
 * is the keep-alive, the notification, and the system-facing [MediaSession], not a second player.
 *
 * What riding a real MediaSession buys beyond survival: hardware/Bluetooth transport keys work,
 * the notification carries live play/pause/stop, and ROMs with a system output switcher key it
 * off the session — the 📡 chip's panel gets a real target (the AirPlay-equivalent ask).
 *
 * Audio focus per convention: transient loss pauses and remembers, gain resumes only what loss
 * paused, permanent loss pauses for good. Headphone unplug (ACTION_AUDIO_BECOMING_NOISY) pauses —
 * a podcast continuing over a speaker you just unplugged from is nobody's intent.
 */
class LedgerPlayerService : Service() {

    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (LedgerPlayer.isPlaying) LedgerPlayer.toggle()
        }
    }
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (LedgerPlayer.isPlaying) { pausedByFocusLoss = true; LedgerPlayer.toggle() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss && !LedgerPlayer.isPlaying) LedgerPlayer.toggle()
                pausedByFocusLoss = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (LedgerPlayer.isPlaying) LedgerPlayer.toggle()
                pausedByFocusLoss = false
            }
        }
    }

    /** Re-render the notification/session on every player change — registered for the service's life. */
    private val playerListener: () -> Unit = {
        if (LedgerPlayer.isActive) runCatching { startForeground(NOTIF_ID, buildNotification()) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { LedgerPlayer.toggle(); return START_STICKY }
            ACTION_STOP_PLAYBACK -> { LedgerPlayer.stop(); return START_STICKY }
        }
        ensureChannel()
        ensureSession()
        requestFocus()
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
        LedgerPlayer.addListener(playerListener)
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        LedgerPlayer.removeListener(playerListener)
        if (noisyRegistered) runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
        abandonFocus()
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        super.onDestroy()
    }

    private fun ensureSession() {
        if (session != null) { updateSession(); return }
        session = MediaSession(this, "LedgerPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!LedgerPlayer.isPlaying) LedgerPlayer.toggle() }
                override fun onPause() { if (LedgerPlayer.isPlaying) LedgerPlayer.toggle() }
                override fun onStop() { LedgerPlayer.stop() }
                override fun onSeekTo(pos: Long) { LedgerPlayer.seekTo(pos.toInt()) }
            })
            isActive = true
        }
        updateSession()
    }

    private fun updateSession() {
        val s = session ?: return
        runCatching {
            s.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, LedgerPlayer.title ?: "Ledgable")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, LedgerPlayer.subtitle ?: "")
                .apply { LedgerPlayer.image?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .putLong(MediaMetadata.METADATA_KEY_DURATION, LedgerPlayer.durationMs.toLong())
                .build())
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO)
                .setState(
                    if (LedgerPlayer.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    LedgerPlayer.positionMs.toLong(), if (LedgerPlayer.isPlaying) LedgerPlayer.speed else 0f)
                .build())
        }
    }

    private fun buildNotification(): Notification {
        updateSession()
        fun action(actionStr: String, req: Int): PendingIntent = PendingIntent.getService(
            this, req, Intent(this, LedgerPlayerService::class.java).setAction(actionStr),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(LedgerPlayer.title ?: "Now playing")
            .setContentText(LedgerPlayer.subtitle ?: "")
            .setOngoing(LedgerPlayer.isPlaying)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null,
                if (LedgerPlayer.isPlaying) "Pause" else "Play", action(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP_PLAYBACK, 2)).build())
        LedgerPlayer.image?.let { b.setLargeIcon(it) }
        session?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken)) }
        return b.build()
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        runCatching { am.requestAudioFocus(req) }.onFailure { Timber.w(it, "focus request failed") }
        focusRequest = req
    }

    private fun abandonFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL_ID = "ledger_player"
        private const val NOTIF_ID = 7431
        const val ACTION_TOGGLE = "com.toolsboox.player.TOGGLE"
        const val ACTION_STOP_PLAYBACK = "com.toolsboox.player.STOP"
        const val ACTION_STOP_SERVICE = "com.toolsboox.player.STOP_SERVICE"

        /** Start (or refresh) the anchor — called when real audio starts. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, LedgerPlayerService::class.java))
            }.onFailure { Timber.w(it, "player service start failed") }
        }

        /** The audio is gone — the anchor goes with it. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, LedgerPlayerService::class.java)
                    .setAction(ACTION_STOP_SERVICE))
            }
        }
    }
}
