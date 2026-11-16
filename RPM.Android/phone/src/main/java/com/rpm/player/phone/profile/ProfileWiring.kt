package com.rpm.player.phone.profile

import com.rpm.player.core.Services
import com.rpm.player.core.cloud.setCounts
import com.rpm.player.core.library.CloudAlbum
import java.text.Normalizer

/** A name made comparable: trimmed, lower case, without accents. A reviewed album is matched to the reader's own by it. */
internal fun plain(text: String?): String =
    Normalizer.normalize(text.orEmpty().trim().lowercase(), Normalizer.Form.NFD).replace(ACCENTS, "")

private val ACCENTS = Regex("\\p{Mn}+")

/** The counts last sent in this run of the app, so an unchanged collection is not sent again. */
private var countsSent: Pair<Int, Int>? = null

/**
 * Sends the size of the reader's own collection, for the counts on their page, when it changed since the last send.
 * A failure is not kept, so it is sent again the next time the collection is read.
 */
internal suspend fun reportCounts(services: Services, albums: List<CloudAlbum>) {
    if (albums.isEmpty()) return
    val counts = albums.size to albums.sumOf { it.tracks.size }
    if (counts == countsSent) return
    runCatching { services.community.setCounts(counts.first, counts.second) }.onSuccess { countsSent = counts }
}
