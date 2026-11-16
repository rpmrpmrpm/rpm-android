package com.rpm.player.core.cast

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.rpm.player.core.library.Cover
import com.rpm.player.core.playback.EXTRA_COVER_KEYS
import com.rpm.player.core.playback.StationItems
import com.rpm.player.core.playback.TrackRef
import com.rpm.player.core.radio.StationLogo
import coil3.request.transformations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player that the media session holds. It wraps the service's ExoPlayer. While casting, it reports that
 * its device is remote and that its volume is the room's volume. The system then sends the remote's volume
 * keys to [setDeviceVolume], [increaseDeviceVolume] and [decreaseDeviceVolume] while this session is the one
 * playing, and they change the speaker's volume and not the TV's. When not casting, everything is the
 * ExoPlayer's own behaviour.
 *
 * Main thread only, as the player is. [changed] is called when casting starts or ends or the room's level
 * changes, and it tells the session's listeners.
 */
@OptIn(UnstableApi::class)
class CastPlayer(player: Player, private val casting: Casting) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    private val remote: Boolean get() = casting.state.value is CastState.On

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    /**
     * A stop from any controller (the launcher's now playing card, another app, the app's own station key) stops
     * the music, releases its stream and leaves the player paused. Media3's own stop leaves the player idle with
     * play still requested. The session then reports neither playing nor paused, and the next prepare starts the
     * music again by itself. The queue is kept, so Play resumes it.
     */
    override fun stop() {
        super.pause()
        super.stop()
    }

    override fun getDeviceInfo(): DeviceInfo = if (remote) ROOM else super.getDeviceInfo()

    override fun getDeviceVolume(): Int = if (remote) casting.roomVolume.value ?: 0 else super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (remote) false else super.isDeviceMuted()

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (remote) casting.setRoomVolume(volume) else super.setDeviceVolume(volume, flags)
    }

    @Deprecated("Deprecated in Player")
    override fun setDeviceVolume(volume: Int) {
        if (remote) casting.setRoomVolume(volume) else @Suppress("DEPRECATION") super.setDeviceVolume(volume)
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (remote) casting.stepRoomVolume(up = true) else super.increaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Player")
    override fun increaseDeviceVolume() {
        if (remote) casting.stepRoomVolume(up = true) else @Suppress("DEPRECATION") super.increaseDeviceVolume()
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (remote) casting.stepRoomVolume(up = false) else super.decreaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Player")
    override fun decreaseDeviceVolume() {
        if (remote) casting.stepRoomVolume(up = false) else @Suppress("DEPRECATION") super.decreaseDeviceVolume()
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (!remote) super.setDeviceMuted(muted, flags)
    }

    override fun getAvailableCommands(): Player.Commands {
        val own = super.getAvailableCommands()
        if (!remote) return own
        return own.buildUpon()
            .addAll(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    /** Tells the session that the device, its volume or the available commands changed. */
    fun changed() {
        val info = deviceInfo
        val volume = deviceVolume
        val commands = availableCommands
        val events = Player.Events(
            FlagSet.Builder()
                .addAll(Player.EVENT_DEVICE_INFO_CHANGED, Player.EVENT_DEVICE_VOLUME_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                .build(),
        )
        for (listener in listeners) {
            listener.onDeviceInfoChanged(info)
            listener.onDeviceVolumeChanged(volume, false)
            listener.onAvailableCommandsChanged(commands)
            listener.onEvents(this, events)
        }
    }

    private companion object {
        val ROOM: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()
    }
}

/**
 * Reads a record's cover, or a station's logo, through the app's own image loader, as a JPEG for the receiver and
 * for the session (the launcher's now playing card). These are the same pictures that the player draws, with the same
 * keys, so a cover that is already drawn comes from Coil's cache.
 *
 * A station's logo is dressed as the player's sleeve dresses it ([com.rpm.player.core.radio.StationBadge] on the
 * sleeve's dark, held the sleeve's inset off the edges), so the home screen shows the station as the app does: a
 * bare logo there was a mark on black, or a wide lockup stretched to the card.
 */
class CoilCastCovers(private val context: Context, private val images: ImageLoader) : CastCovers {
    override suspend fun jpegOf(item: MediaItem): ByteArray? {
        val data: Any = pictureOf(item) ?: return null
        val request = ImageRequest.Builder(context).data(data).size(EDGE).allowHardware(false).apply {
            if (data is StationLogo) transformations(com.rpm.player.core.radio.StationBadge(data.uuid, SLEEVE_INSET, EDGE))
        }.build()
        val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) {
            // A dressed logo leaves the sleeve's dark round a picture that does not fill it; JPEG has no clear.
            val flat = if (data !is StationLogo) bitmap else Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(SLEEVE_DARK)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
            ByteArrayOutputStream().use { out ->
                flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        }
    }

    override suspend fun jpegOfPicture(address: String): ByteArray? {
        val request = ImageRequest.Builder(context).data(address).size(EDGE).allowHardware(false).build()
        val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        }
    }

    private fun pictureOf(item: MediaItem): Any? {
        val extras = item.mediaMetadata.extras
        StationItems.uuidOf(item.mediaId)?.let { uuid ->
            val logos = extras?.getStringArray(StationItems.EXTRA_LOGOS)?.toList().orEmpty()
            return StationLogo(uuid, extras?.getString(StationItems.EXTRA_LOGO_STAMP).orEmpty(), logos)
        }
        val ref = TrackRef.parse(item.mediaId) ?: return null
        val keys = extras?.getStringArray(EXTRA_COVER_KEYS)?.takeIf { it.isNotEmpty() } ?: return null
        return Cover(ref.shelf, keys.toList(), stamp = 0)
    }

    private companion object {
        /** The size of the picture sent to the receiver. Its cover fills about half the height of a 1080p screen. */
        const val EDGE = 600
        const val QUALITY = 85

        /** The player's sleeve: its dark (#0A0A0A) and how far a logo that does not fill it is held off the edges, 34 px
         * of a 410 px sleeve at 1080p (the TV's StationArt). */
        const val SLEEVE_DARK = 0xFF0A0A0A.toInt()
        const val SLEEVE_INSET = 34f / 410f
    }
}
