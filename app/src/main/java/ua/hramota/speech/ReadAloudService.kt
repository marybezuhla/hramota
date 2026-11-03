package ua.hramota.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ua.hramota.R

/**
 * Keeps Hramota reading with the screen off: the controls on the lock screen and in the notifications, headphone
 * buttons, a pause when the headphones come out or a call comes in. Nothing else; reading itself is [ReadAloud].
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var foreground = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) ReadAloud.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "Hramota").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = ReadAloud.resume()
                override fun onPause() = ReadAloud.pause()
                override fun onSkipToNext() = ReadAloud.skip(1)
                override fun onSkipToPrevious() = ReadAloud.skip(-1)
                override fun onStop() = ReadAloud.stop()
            })
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.read_aloud), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        scope.launch {
            ReadAloud.state.map { Triple(it.active, it.playing, it.title) }.distinctUntilChanged().collect { (active, playing, title) ->
                if (!active) { stopSelf(); return@collect }
                if (playing) takeFocus() else dropFocus()
                session.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
                session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name)).build())
                show(playing, title)
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) ReadAloud.pause()
    }

    private fun takeFocus() {
        if (focus != null) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener).build().also { audio.requestAudioFocus(it) }
    }

    private fun dropFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    private fun action(code: Int, action: String) =
        PendingIntent.getService(this, code, Intent(this, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun show(playing: Boolean, title: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setContentTitle(title)
            .setContentText(getString(R.string.read_aloud))
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_previous), getString(R.string.read_previous), action(1, ACTION_PREVIOUS)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                getString(if (playing) R.string.read_pause else R.string.read_play), action(2, ACTION_TOGGLE)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), getString(R.string.read_next), action(3, ACTION_NEXT)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), getString(R.string.read_stop), action(4, ACTION_STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (!foreground) {
            // Android may refuse it from the background (resumed later): then the controls are only shown
            foreground = try { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); true } catch (e: Exception) { getSystemService(NotificationManager::class.java).notify(ID, n); false }
        } else getSystemService(NotificationManager::class.java).notify(ID, n)
        // Paused: it may be put away, but the controls stay until reading ends
        if (!playing) stopForeground(STOP_FOREGROUND_DETACH).also { foreground = false }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloud.toggle()
            ACTION_NEXT -> ReadAloud.skip(1)
            ACTION_PREVIOUS -> ReadAloud.skip(-1)
            ACTION_STOP -> ReadAloud.stop()
        }
        // Started to read: in the foreground at once (Android asks for it within seconds)
        if (!foreground && ReadAloud.state.value.active) show(ReadAloud.state.value.playing, ReadAloud.state.value.title)
        if (!ReadAloud.state.value.active) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        dropFocus()
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        session.release()
        getSystemService(NotificationManager::class.java).cancel(ID)
        ReadAloud.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "reading"
        private const val ID = 7
        private const val ACTION_TOGGLE = "ua.hramota.read.TOGGLE"
        private const val ACTION_NEXT = "ua.hramota.read.NEXT"
        private const val ACTION_PREVIOUS = "ua.hramota.read.PREVIOUS"
        private const val ACTION_STOP = "ua.hramota.read.STOP"

        fun show(context: Context) {
            try { context.startForegroundService(Intent(context, ReadAloudService::class.java)) } catch (_: Exception) {}
        }

        fun hide(context: Context) {
            try { context.stopService(Intent(context, ReadAloudService::class.java)) } catch (_: Exception) {}
        }
    }
}
