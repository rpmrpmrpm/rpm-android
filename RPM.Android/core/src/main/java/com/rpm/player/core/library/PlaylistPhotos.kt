package com.rpm.player.core.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import com.rpm.player.core.room.Rgb
import java.io.IOException

/**
 * A generated list's photograph, shipped with the app, and the colour it is tinted with. One of the app's own folders
 * wears one too, from a folder of the assets of its own ([assets]).
 *
 * These are the desktop's own files (RPM.Desktop/Assets/PlaylistPhotos), chosen in
 * `scripts/playlist-photos.json` and copied into this app's assets by `scripts/gen_playlist_photos_tv.py`,
 * with the table of which list uses which ([PlaylistPhotoFiles]). A list with a photograph here shows it
 * instead of the drawn picture the cloud keeps for that list.
 *
 * The file is the bare photograph. The list's colour is added when it is drawn ([PlaylistPhotoFetcher]),
 * as the desktop's `GeneratedCoverService.Framed` does, so a changed palette needs no new export.
 */
data class PlaylistPhoto(val file: String, val tint: Rgb, val assets: String = PlaylistPhotos.FOLDER) : ListArt

object PlaylistPhotos {
    /** The photograph the list [kind] cut to [genre] and [decade] wears, or null where it has none: most
     * genre and decade cuts, every list about listening, and every list a person made. */
    fun of(kind: String?, genre: String?, decade: Int): PlaylistPhoto? {
        if (kind == null) return null
        val file = PlaylistPhotoFiles.BY_CUT[Cut(kind, genre, decade)] ?: return null
        val seed = ListSeed.of(kind, genre, decade) ?: return null
        return PlaylistPhoto(file, ListSeed.tint(seed))
    }

    /**
     * The photograph behind the frame of one of the app's own folders, and the colour it is washed in, or null for a
     * folder somebody made (`PlaylistPhotos.folder` on the iPhone).
     *
     * Each folder has a photograph of its own ([FolderPhotoFiles], from `scripts/folder-photos.json`, read from
     * [FOLDER_PHOTOS]), never one of the lists' photographs, so a folder does not look like one more list. It is
     * washed in the folder's colour: a mood's folder in the colour of that mood's list of the whole collection, the
     * decades in the 1980s' colour, and the folder of all the moods in the app's own blue
     * ([GeneratedListLook.appTint]), because it holds every mood.
     */
    fun folder(face: FolderFace): PlaylistPhoto? {
        val (key, tint) = when (face) {
            FolderFace.Plain -> return null
            FolderFace.Moods -> "moods" to GeneratedListLook.appTint
            FolderFace.Decades -> "decades" to ListSeed.of("Decade", null, 1980)?.let(ListSeed::tint)
            is FolderFace.Mood -> "mood:" + face.kind to ListSeed.of(face.kind, null, 0)?.let(ListSeed::tint)
        }
        val file = FolderPhotoFiles.files[key] ?: return null
        return PlaylistPhoto(file, tint ?: return null, FOLDER_PHOTOS)
    }

    /** A generated list, keyed on the three fields the computer keys it on. */
    data class Cut(val kind: String, val genre: String?, val decade: Int)

    /** Where the lists' files are in the app's assets. */
    const val FOLDER = "playlist-photos"

    /** Where the folders' files are in the app's assets ([folder]). */
    const val FOLDER_PHOTOS = "folder-photos"
}

/**
 * Reads a [PlaylistPhoto] out of the assets and tints it: the photograph filling a square, then the
 * list's tint laid over it at [TINT_STRENGTH] with the `color` blend mode, which keeps the photograph's
 * light and shade and takes its hue and saturation from the tint. It uses the same figures as the desktop's
 * `GeneratedCoverService.Framed` and the iOS app's `PlaylistPhotos.washed`, so a list shows one picture in
 * every app.
 *
 * Decoded at the size asked for, at most the shipped [PlaylistPhotoFiles.EDGE] ([FolderPhotoFiles.SHIPPED_EDGE] for a
 * folder's): a home card is half that. Runs on the image loader's dispatcher.
 */
class PlaylistPhotoFetcher(private val photo: PlaylistPhoto, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val asked = maxOf(pixels(options.size.width), pixels(options.size.height))
        val shipped = if (photo.assets == PlaylistPhotos.FOLDER_PHOTOS) FolderPhotoFiles.SHIPPED_EDGE else PlaylistPhotoFiles.EDGE
        val edge = if (asked <= 0) shipped else minOf(asked, shipped)
        val bytes = options.context.assets.open(photo.assets + "/" + photo.file).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("cannot decode ${photo.file}")
        return ImageFetchResult(image = washed(decoded, edge).asImage(), isSampled = edge < shipped, dataSource = DataSource.DISK)
    }

    private fun washed(picture: Bitmap, edge: Int): Bitmap {
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        // Filled, centred: the shipped files are square, so this crops nothing from them.
        val side = minOf(picture.width, picture.height)
        val from = Rect((picture.width - side) / 2, (picture.height - side) / 2, (picture.width + side) / 2, (picture.height + side) / 2)
        canvas.drawBitmap(picture, from, Rect(0, 0, edge, edge), Paint(Paint.FILTER_BITMAP_FLAG))
        picture.recycle()
        // The blend mode is API 29; on 28 the photograph is drawn without the tint.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wash = Paint().apply {
                color = android.graphics.Color.argb(TINT_STRENGTH, photo.tint.r, photo.tint.g, photo.tint.b)
                blendMode = BlendMode.COLOR
            }
            canvas.drawRect(0f, 0f, edge.toFloat(), edge.toFloat(), wash)
        }
        return out
    }

    private fun pixels(dimension: Dimension): Int = (dimension as? Dimension.Pixels)?.px ?: 0

    class Factory : Fetcher.Factory<PlaylistPhoto> {
        override fun create(data: PlaylistPhoto, options: Options, imageLoader: ImageLoader): Fetcher = PlaylistPhotoFetcher(data, options)
    }

    private companion object {
        /** How much of the list's colour is laid over the photograph (GeneratedCoverService.TintStrength). */
        const val TINT_STRENGTH = 0.72f
    }
}

/** The image loader's key of a [PlaylistPhoto]: the assets folder, the file and the colour it is washed in, so a
 * list's photograph and a folder's are never taken for each other, and one photograph washed in two colours is two
 * pictures. */
class PlaylistPhotoKeyer : Keyer<PlaylistPhoto> {
    override fun key(data: PlaylistPhoto, options: Options): String = of(data)

    companion object {
        fun of(data: PlaylistPhoto): String =
            "photo:" + data.assets + "/" + data.file + ":" + data.tint.r + "," + data.tint.g + "," + data.tint.b
    }
}
