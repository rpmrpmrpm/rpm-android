package com.rpm.player.phone.art

import android.content.Context
import android.graphics.Bitmap
import com.rpm.player.core.diag.Diag
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rpm.player.core.art.ArtFrame
import com.rpm.player.core.art.ArtScene
import com.rpm.player.core.art.CropRect
import com.rpm.player.core.art.FaceBox
import com.rpm.player.core.art.SaliencyCrop
import com.rpm.player.core.library.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * An artist's photograph, cropped for a frame so that their face stays in view. The iPhone app does the
 * same in `BackdropStore.portrait` (RPMArt/BackdropStore.swift).
 *
 * Square photographs are tried first, up to three. A square is skipped if the frame would cover its main
 * face (the face is outside the window, or its eyes are under the status bar). The first square that is not
 * skipped fills the frame from its top. If every square is skipped, the first wide photograph is cropped
 * around its faces ([SaliencyCrop]). If there is no such photograph, the first skipped square is shown
 * anyway. Faces are found with ML Kit's bundled model, on a copy at most 512 pixels long. The iPhone app
 * also finds the point of attention in a picture with no face. This Android app has no model for that, so
 * a picture with no face is filled from its top.
 *
 * The last [KEEP] cuts are kept in memory for the life of the app. The photograph each artist settled on
 * for each frame is kept too, so a page opened again is cut at once. A choice made while a photograph
 * could not be read is not stored, so the next request reads that photograph again.
 */
class Portraits(private val context: Context, private val images: ImageLoader, private val artwork: Artwork) {

    private enum class Fit { FindTheFace, FillFromTop }

    private data class Cut(val url: String, val scene: ArtScene, val fit: Fit)

    private data class Ask(val artist: String, val scene: ArtScene)

    private val lock = Mutex()
    private val cuts = LinkedHashMap<Cut, Bitmap>()
    private val settled = HashMap<Ask, Pair<String, Fit>>()

    /**
     * The backdrop each artist was left on in the stage's deck, by artist, as its address. It is kept across launches,
     * as the iPhone's `BackdropPicks`: it is which photograph of an artist looks best, not the person's data.
     */
    private val picks by lazy { context.getSharedPreferences(PICKS, Context.MODE_PRIVATE) }

    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
    }

    /**
     * The artist's photograph cropped for [scene]. Returns null if the artist has no photograph, or none could be read.
     */
    suspend fun portrait(artist: String, album: String, scene: ArtScene): Bitmap? {
        if (artist.isBlank() || !scene.isLaidOut) return null
        val ask = Ask(artist.lowercase(), scene)
        lock.withLock { settled[ask] }?.let { (url, fit) -> return cut(url, scene, fit) }

        val said = artwork.of(artist, album) ?: return null
        var allRead = true
        var covered: String? = null
        for (square in said.thumbs.take(SQUARES_READ)) {
            val photograph = load(square)
            if (photograph == null) {
                allRead = false
                continue
            }
            val faces = faces(photograph)
            if (faces == null) allRead = false
            if (faces != null && ArtFrame.coversFace(faces.boxes, faces.width, faces.height, scene)) {
                covered = covered ?: square
                continue
            }
            val picture = filled(photograph, scene)
            remember(Cut(square, scene, Fit.FillFromTop), picture)
            if (allRead) lock.withLock { settled[ask] = square to Fit.FillFromTop }
            return picture
        }
        said.backgrounds.firstOrNull()?.let { backdrop ->
            cut(backdrop, scene, Fit.FindTheFace)?.let { picture ->
                if (allRead) lock.withLock { settled[ask] = backdrop to Fit.FindTheFace }
                return picture
            }
        }
        // Nothing else can be shown. This result is not stored, because a backdrop that fails to load now may load
        // later.
        return covered?.let { cut(it, scene, Fit.FillFromTop) }
    }

    // ── the stage's deck (`BackdropStore.count`, `backdrop(at:)`, `opening`, `remember` on iPhone) ──

    /** How many backdrops the artist has. Zero is the usual answer for an artist with none. */
    suspend fun count(artist: String, album: String): Int =
        if (artist.isBlank()) 0 else artwork.of(artist, album)?.backgrounds?.size ?: 0

    /**
     * The artist's backdrop [at], cut for [scene] around its faces. Null out of range, where it could not be read, or
     * before the frame is laid out. The squares are not part of the set: they are for the pages about the artist.
     */
    suspend fun backdrop(artist: String, album: String, at: Int, scene: ArtScene): Bitmap? {
        if (artist.isBlank() || at < 0 || !scene.isLaidOut) return null
        val url = artwork.of(artist, album)?.backgrounds?.getOrNull(at) ?: return null
        return cut(url, scene, Fit.FindTheFace)
    }

    /**
     * Which backdrop the deck opens on: the one the person last left this artist on, and the first otherwise. The
     * iPhone also judges the first few crops for the best one the first time; this app has no reader for that and
     * opens on the first.
     */
    suspend fun opening(artist: String, album: String): Int {
        if (artist.isBlank()) return 0
        val urls = artwork.of(artist, album)?.backgrounds ?: return 0
        val picked = withContext(Dispatchers.IO) { picks.getString(artist.lowercase(), null) } ?: return 0
        return urls.indexOf(picked).coerceAtLeast(0)
    }

    /** Writes down where the person left this artist: a landing by hand, never a picture fetched ahead. */
    suspend fun leftOn(artist: String, album: String, at: Int) {
        if (artist.isBlank()) return
        val url = artwork.of(artist, album)?.backgrounds?.getOrNull(at) ?: return
        withContext(Dispatchers.IO) { picks.edit().putString(artist.lowercase(), url).apply() }
    }

    private suspend fun cut(url: String, scene: ArtScene, fit: Fit): Bitmap? {
        val key = Cut(url, scene, fit)
        lock.withLock { cuts[key] }?.let { return it }
        val photograph = load(url) ?: return null
        val picture = when (fit) {
            Fit.FillFromTop -> filled(photograph, scene)
            Fit.FindTheFace -> framed(photograph, scene) ?: filled(photograph, scene)
        }
        remember(key, picture)
        return picture
    }

    private suspend fun remember(key: Cut, picture: Bitmap) = lock.withLock {
        cuts.remove(key)
        cuts[key] = picture
        while (cuts.size > KEEP) cuts.remove(cuts.keys.first())
    }

    /**
     * The photograph, decoded with its longest edge at most [READ] pixels. The bitmap is not
     * hardware-backed, so its pixels can be read.
     */
    private suspend fun load(url: String): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(READ).allowHardware(false).build()
        return (images.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /**
     * The faces found on a copy at most [WORK] pixels long, with the size of that copy. Null if the
     * detector refused the picture.
     */
    private class Faces(val boxes: List<FaceBox>, val width: Int, val height: Int)

    private suspend fun faces(photograph: Bitmap): Faces? {
        val scale = minOf(1.0, WORK.toDouble() / maxOf(photograph.width, photograph.height))
        val width = maxOf(1, (photograph.width * scale).roundToInt())
        val height = maxOf(1, (photograph.height * scale).roundToInt())
        val work = withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(photograph, width, height, true) }
        val found = detector.process(InputImage.fromBitmap(work, 0)).awaitOrNull() ?: run {
            Diag.i(TAG, "faces not read in a ${photograph.width}x${photograph.height} picture")
            return null
        }
        val boxes = found.map { face ->
            val box = face.boundingBox
            FaceBox(box.left, box.top, box.width(), box.height())
        }.filter { it.width > 0 && it.height > 0 }
        return Faces(boxes, width, height)
    }

    /** The photograph cropped around its faces for [scene]. Null if no face is found. */
    private suspend fun framed(photograph: Bitmap, scene: ArtScene): Bitmap? {
        val faces = faces(photograph)?.takeIf { it.boxes.isNotEmpty() } ?: return null
        val choice = SaliencyCrop.choose(
            SaliencyCrop.facesOnly(faces.boxes, faces.width, faces.height),
            faces.width,
            faces.height,
            scene.aspect,
            faces.boxes,
            scene,
        )
        return draw(photograph, ArtFrame.toSource(choice.rect, faces.width, photograph.width, photograph.height))
    }

    /** The photograph, filled into the frame from its top. */
    private suspend fun filled(photograph: Bitmap, scene: ArtScene): Bitmap =
        draw(photograph, ArtFrame.topWindow(photograph.width, photograph.height, scene.aspect))

    private suspend fun draw(photograph: Bitmap, window: CropRect): Bitmap = withContext(Dispatchers.Default) {
        val cut = Bitmap.createBitmap(photograph, window.x, window.y, window.width, window.height)
        val scale = minOf(1.0, WIDEST.toDouble() / window.width)
        if (scale >= 1.0) cut else Bitmap.createScaledBitmap(cut, (window.width * scale).roundToInt(), maxOf(1, (window.height * scale).roundToInt()), true)
    }

    private companion object {
        const val TAG = "rpm.portraits"

        /** The file the deck's picks are kept in. */
        const val PICKS = "rpm.backdrop.picks"

        /**
         * Sizes in pixels: the longest edge a photograph is read at, the longest edge of the copy faces are found on,
         * and the widest cut that is kept.
         */
        const val READ = 2048
        const val WORK = 512
        const val WIDEST = 1400

        /**
         * How many square photographs are checked before the wide ones. Same value as `BackdropChooser.maxCandidates`
         * on the iPhone app.
         */
        const val SQUARES_READ = 3

        /** How many cuts are kept. */
        const val KEEP = 8
    }
}

/** The task's result, or null if it failed or was cancelled. */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { waiting ->
    addOnSuccessListener { waiting.resume(it) }
    addOnFailureListener { waiting.resume(null) }
    addOnCanceledListener { waiting.resume(null) }
}
