package com.rpm.player.phone.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rpm.player.core.cast.CastState
import com.rpm.player.core.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The speakers an equaliser curve belongs to, as on the desktop (EqProfile.Device): each set of speakers keeps its own
 * curve, because a curve is a correction for the speakers and not a taste. The phone's speaker needs bass, good
 * headphones need none, and a Bluetooth speaker needs something else again.
 *
 * @param key what the curve is stored under. Two headphones of different models have different keys.
 * @param label the speakers' name as the phone gives it, shown over the faders. Null while casting.
 */
data class EqOutput(val key: String, val label: String?) {
    val casting: Boolean get() = key == CAST_KEY

    companion object {
        const val CAST_KEY = "cast"

        /** A Cast device: one curve for all of them, as on the desktop. */
        val CAST = EqOutput(CAST_KEY, null)
    }
}

/** One output the phone has, as [EqOutputs.pick] reads it: an [AudioDeviceInfo] type, its product name and address. */
data class OutputDevice(val type: Int, val name: String, val address: String = "")

/**
 * Follows which speakers the music goes to, and tells [onOutput] each time it may have changed: a device connected or
 * disconnected, or a cast started or ended. [onOutput] is called on the main thread, also when nothing changed, and
 * once at [start].
 *
 * Android does not say which device a player's audio goes to before it plays. From Android 13 the phone answers which
 * device it would route music to. Before that, the newest kind of device wins, in the order Android routes music:
 * Bluetooth, then USB, then wired headphones, then the phone's own speaker ([pick]).
 */
class EqOutputs(
    context: Context,
    private val casting: StateFlow<CastState>,
    private val scope: CoroutineScope,
    private val onOutput: (EqOutput) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { onOutput(current()) }

    fun start() {
        audio?.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
            },
            handler,
        )
        scope.launch {
            casting.map { it is CastState.On }.distinctUntilChanged().collect { onOutput(current()) }
        }
        onOutput(current())
    }

    /** Asked again now and once more a moment later: a device is often listed before music is routed to it. */
    private fun changed() {
        handler.removeCallbacks(check)
        onOutput(current())
        handler.postDelayed(check, SETTLE_MS)
    }

    /** The speakers the music goes to now. */
    fun current(): EqOutput {
        if (casting.value is CastState.On) return EqOutput.CAST
        val audio = audio ?: return pick(emptyList(), null, Build.MODEL)
        val outputs = try {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { OutputDevice(it.type, it.productName?.toString().orEmpty(), it.address.orEmpty()) }
        } catch (e: RuntimeException) {
            Diag.w(TAG, "outputs not readable: $e")
            emptyList()
        }
        return pick(outputs, routed(audio), Build.MODEL).also { Diag.d(TAG, "music goes to ${it.key}") }
    }

    /** The device Android would route music to now (Android 13 and later), or null where it does not say. */
    private fun routed(audio: AudioManager): OutputDevice? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            audio.getAudioDevicesForAttributes(music).firstOrNull()?.let { OutputDevice(it.type, "", it.address.orEmpty()) }
        } catch (e: RuntimeException) {
            Diag.w(TAG, "route not readable: $e")
            null
        }
    }

    companion object {
        private const val TAG = "rpm.eq"
        private const val SETTLE_MS = 1500L

        /** The kinds of output, best first, in the order Android routes music to them when several are connected. */
        private val ORDER: List<Set<Int>> = listOf(
            setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST),
            setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            setOf(AudioDeviceInfo.TYPE_HEARING_AID),
            setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY),
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE,
            ),
            setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_DOCK),
        )

        /**
         * Which speakers the music goes to: the [routed] device where the phone named one, matched to [outputs] for its
         * name, otherwise the best of [outputs] by [ORDER], otherwise the phone's own speaker. [model] names a device
         * that has no name of its own.
         */
        fun pick(outputs: List<OutputDevice>, routed: OutputDevice?, model: String): EqOutput {
            val chosen = routed?.let { r ->
                outputs.firstOrNull { it.type == r.type && (r.address.isEmpty() || it.address == r.address) } ?: r
            } ?: ORDER.firstNotNullOfOrNull { kinds -> outputs.firstOrNull { it.type in kinds } }
            val device = chosen ?: OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
            val label = device.name.ifBlank { model }
            val kind = kindOf(device.type)
            // The phone has one speaker, whatever name it is given.
            return if (kind == "speaker") EqOutput(kind, label) else EqOutput("$kind:${device.name}", label)
        }

        private fun kindOf(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
            -> "bluetooth"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE,
            -> "wired"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_DOCK -> "hdmi"
            else -> "out$type"
        }
    }
}
