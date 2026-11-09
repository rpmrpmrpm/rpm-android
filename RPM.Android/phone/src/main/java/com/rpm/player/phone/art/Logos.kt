package com.rpm.player.phone.art

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.rpm.player.core.art.Lettering
import com.rpm.player.core.art.Plane
import com.rpm.player.core.library.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Artists' logos, cut to their lettering and reduced to the pixels they are drawn at. The iPhone app
 * does the same in `BackdropStore.logo` (RPMArt/BackdropStore.swift). The cut and the reduction are in [Lettering].
 *
 * A logo is read at its own size, trimmed once and kept. Only the last [KEEP] are kept, because one is
 * about a megabyte. A page drawn again at another size reduces the kept mark instead of reading the
 * logo again.
 */
class Logos(private val context: Context, private val images: ImageLoader, private val artwork: Artwork) {
    private val lock = Mutex()
    private val marks = LinkedHashMap<String, Plane>()

    /** The logos as last fitted, by artist and size, so a page shown again has its logo from its first frame ([held]). */
    private val fitted = LinkedHashMap<Fitted, Bitmap>()

    private data class Fitted(val artist: String, val cap: Int, val room: Int)

    /**
     * The logo [logo] last gave for [artist] at this size, if it is kept. Null when it is not, or when the store is busy
     * this moment; [logo] then answers as usual. It never waits, so the main thread may ask.
     */
    fun held(artist: String, cap: Int, room: Int): Bitmap? {
        if (!lock.tryLock()) return null
        return try {
            fitted[Fitted(artist.lowercase(), cap, room)]
        } finally {
            lock.unlock()
        }
    }

    /**
     * The artist's logo, at most [cap] pixels tall and [room] pixels wide, drawn at one image pixel per
     * screen pixel. Returns null if the catalogues have no logo for the artist, or if it cannot be read.
     */
    suspend fun logo(artist: String, album: String, cap: Int, room: Int): Bitmap? {
        if (artist.isBlank() || cap <= 0) return null
        val url = artwork.of(artist, album)?.logo?.takeIf { it.isNotBlank() } ?: return null
        val mark = lock.withLock { marks[url] } ?: run {
            val read = read(url) ?: return null
            val trimmed = withContext(Dispatchers.Default) { Lettering.trimmed(read) }
            lock.withLock {
                marks.remove(url)
                marks[url] = trimmed
                while (marks.size > KEEP) marks.remove(marks.keys.first())
            }
            trimmed
        }
        val drawn = withContext(Dispatchers.Default) {
            bitmap(Lettering.fitted(mark, Lettering.height(mark.width, mark.height, cap, room)))
        }
        lock.withLock {
            val key = Fitted(artist.lowercase(), cap, room)
            fitted.remove(key)
            fitted[key] = drawn
            while (fitted.size > KEEP) fitted.remove(fitted.keys.first())
        }
        return drawn
    }

    /** The logo at its own size, as premultiplied RGBA. */
    private suspend fun read(url: String): Plane? {
        val request = ImageRequest.Builder(context).data(url).size(Size.ORIGINAL).allowHardware(false).build()
        val picture = (images.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) {
            val plain = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
            // Android bitmaps store premultiplied pixels, red first. This is the layout `Plane` expects.
            val bytes = ByteArray(plain.width * plain.height * 4)
            plain.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            Plane(plain.width, plain.height, bytes)
        }
    }

    private fun bitmap(plane: Plane): Bitmap =
        Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(plane.bytes))
        }

    private companion object {
        const val KEEP = 4
    }
}
