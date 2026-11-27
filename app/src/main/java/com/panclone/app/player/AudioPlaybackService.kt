package com.panclone.app.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioFocusRequest
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.panclone.app.R

/** Media3 原生后台播放服务：音频焦点、锁屏/通知控制和前台服务生命周期交给 MediaSession。 */
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private val focusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            val player = session?.player ?: return
            // 只支持打断暂停，不恢复播放
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                player.pause()
            }
        }
    }

    companion object {
        const val ACTION_STOP_SERVICE = "com.panclone.app.action.STOP_AUDIO_SERVICE"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            session?.player?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setWakeMode(C.WAKE_MODE_NETWORK)
            // 播完自动排下一首；队列走完停在最后一首。
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            // 音频焦点：被别的软件打断时自动暂停，焦点恢复后自动继续
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,  // 手动管理音频焦点，避免与 AudioFocusRequest 冲突
            )
        }
        session = MediaSession.Builder(this, player).build()
        val provider = DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        }
        setMediaNotificationProvider(provider)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioManager.requestAudioFocus(focusRequest)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
