package com.rpm.player.phone.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.rpm.player.phone.R

/**
 * The short sound rpm plays as it opens: the desktop's opening sound (`AppSound.PlayOpen`), at the same seven tenths of
 * full volume. It plays once per start of the app, as the opening screen fades away and home fades in, and only when:
 *
 * - the setting is on ([com.rpm.player.phone.PhoneSettings.openingSound]);
 * - no other music plays: a sound over somebody else's music is noise;
 * - the phone rings out loud: on silent or vibrate the person asked for quiet.
 *
 * It stops, fading over a fifth of a second, as soon as rpm's own music starts ([stop]), as the desktop's stops when a
 * track starts. It holds no audio focus, so it never pauses another app.
 */
object OpeningSound {
    /** Whether it played in this process already. A new activity (a turn of the phone) does not play it again. */
    @Volatile
    private var played = false

    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    /** Plays the sound if it may. Main thread. */
    fun play(context: Context, wanted: Boolean) {
        if (played) return
        played = true
        if (!wanted) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (audio.isMusicActive || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val made = runCatching { MediaPlayer.create(context.applicationContext, R.raw.rpm_open, attributes, audio.generateAudioSessionId()) }.getOrNull() ?: return
        made.setVolume(VOLUME, VOLUME)
        made.setOnCompletionListener { release(it) }
        player = made
        runCatching { made.start() }.onFailure { release(made) }
    }

    /** Stops the sound if it still plays, with a short fade. Main thread. */
    fun stop() {
        val playing = player ?: return
        player = null
        var step = 0
        val fade = object : Runnable {
            override fun run() {
                step++
                val left = VOLUME * (1f - step.toFloat() / FADE_STEPS)
                if (left <= 0f) {
                    release(playing)
                    return
                }
                runCatching { playing.setVolume(left, left) }
                main.postDelayed(this, FADE_MS / FADE_STEPS)
            }
        }
        main.post(fade)
    }

    private fun release(it: MediaPlayer) {
        if (player === it) player = null
        runCatching { it.stop() }
        it.release()
    }

    /** The desktop's `Softer`: the opening sound at seven tenths of full volume. */
    private const val VOLUME = 0.7f
    private const val FADE_MS = 200L
    private const val FADE_STEPS = 8
}
