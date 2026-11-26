package com.rpm.player.core.radio

import android.util.LruCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.rpm.player.core.art.SharpScaled
import com.rpm.player.core.net.AppKey
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.await
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException

/**
 * A station's logo to draw: the rpm API's mark for [uuid] (a 512 px picture the logo crawl settled on,
 * WebP or SVG), else the logos the directory lists for it. [stamp] is the logo stamp from `radio/state`.
 * It only changes the address, so a changed logo is not served from a cache, and it is part of the
 * cache key here for the same reason.
 *
 * The desktop goes on to the station's homepage icons and Wikipedia when both fail. That is not ported.
 */
data class StationLogo(val uuid: String, val stamp: String, val logos: List<String>) {
    companion object {
        fun of(station: RadioStation, stamp: String): StationLogo =
            StationLogo(station.uuid, stamp, listOfNotNull(station.favicon) + station.otherLogos)
    }
}

class StationLogoFetcher(
    private val logo: StationLogo,
    private val options: Options,
    private val http: OkHttpClient,
    private val held: LruCache<StationLogo, Pair<ByteArray, String?>>,
    private val lastShown: LruCache<String, Pair<ByteArray, String?>>,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // One download per logo per run, whichever surface asks first. A station's box, its colours and its
        // badge are three requests for one logo, and each one is a different picture in the image loader's memory.
        val bytes = held.get(logo) ?: download().also {
            held.put(logo, it)
            lastShown.put(logo.uuid.lowercase(), it)
        }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes.first), options.fileSystem, SharpScaled),
            mimeType = bytes.second,
            dataSource = DataSource.NETWORK,
        )
    }

    /**
     * rpm's own logo, else the directory's. Only an answer of "no logo" from rpm (a 404, or an answer that is not a
     * picture) moves on to the directory's addresses. When rpm cannot be asked just now (no network for a moment, a
     * slow or failing server), the station's last logo this run is used, as the iPhone keeps a face's last dressing.
     * Otherwise a square that shows rpm's logo would change to the directory's older one on the next ask that fails.
     */
    private suspend fun download(): Pair<ByteArray, String?> {
        val api = Hosts.API + "radio/logo/" + logo.uuid.lowercase() + (if (logo.stamp.isNotEmpty()) "?v=" + logo.stamp else "")
        when (val own = get(api, withKey = true)) {
            is Got.Picture -> return own.body
            Got.Failed -> lastShown.get(logo.uuid.lowercase())?.let { return it }
            Got.Nothing -> Unit
        }
        return logo.logos.firstNotNullOfOrNull { (get(it, withKey = false) as? Got.Picture)?.body }
            ?: throw IOException("no logo for station ${logo.uuid}")
    }

    /** What one address answered. */
    private sealed interface Got {
        /** A picture: its body and media type. */
        class Picture(val body: Pair<ByteArray, String?>) : Got

        /** An answer that there is no picture here: a 404, or a body that is not a picture. */
        data object Nothing : Got

        /** No answer about the picture: no connection, a timeout, or a server that failed. */
        data object Failed : Got
    }

    private suspend fun get(url: String, withKey: Boolean): Got {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", RadioBrowser.USER_AGENT)
                .apply { if (withKey) header(AppKey.HEADER, AppKey.VALUE) }
                .build()
            http.newCall(request).await().use { response ->
                if (response.code == 404 || response.code == 410) return Got.Nothing
                if (!response.isSuccessful) return Got.Failed
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()
                if (type != null && !type.startsWith("image/")) return Got.Nothing
                Got.Picture(response.body.bytes() to type)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Got.Failed
        }
    }

    class Factory(private val http: OkHttpClient) : Fetcher.Factory<StationLogo> {
        /** Logos fetched this run, keyed by the logo asked for (the station, its logo stamp and its addresses),
         * up to [HELD_BYTES]. A changed logo has a new stamp, so it is fetched again. */
        private val held = object : LruCache<StationLogo, Pair<ByteArray, String?>>(HELD_BYTES) {
            override fun sizeOf(key: StationLogo, value: Pair<ByteArray, String?>): Int = value.first.size
        }

        /** The logo each station last showed this run, by lower-case uuid, whatever its stamp: what is shown when rpm
         * cannot be asked (see [StationLogoFetcher.download]). */
        private val lastShown = object : LruCache<String, Pair<ByteArray, String?>>(HELD_BYTES) {
            override fun sizeOf(key: String, value: Pair<ByteArray, String?>): Int = value.first.size
        }

        override fun create(data: StationLogo, options: Options, imageLoader: ImageLoader): Fetcher =
            StationLogoFetcher(data, options, http, held, lastShown)
    }

    private companion object {
        const val HELD_BYTES = 4 * 1024 * 1024
    }
}

class StationLogoKeyer : Keyer<StationLogo> {
    override fun key(data: StationLogo, options: Options): String = "station:" + data.uuid.lowercase() + ":" + data.stamp
}
