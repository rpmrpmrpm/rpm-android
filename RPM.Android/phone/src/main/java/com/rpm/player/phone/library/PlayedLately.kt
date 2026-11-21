package com.rpm.player.phone.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.Collection
import com.rpm.player.core.playback.ListeningHistory

/**
 * The songs played lately, newest first: the playing history at the top of the playlists view. Each song once, at the
 * last time it was played on any device, as the desktop's Recently played list orders them.
 *
 * Two sources are joined. The library's play totals ([Collection.plays]) carry every device's last play, as the cloud
 * had them when the library was read. This phone's listens since then ([ListeningHistory]) are not in them yet, so they
 * are laid over the top: a song heard a minute ago is first at once, without a new read.
 *
 * Only the person's own collection: a collection another account shares has none of this person's plays.
 */
object PlayedLately {
    /** How many songs the history holds. Older plays fall off the end. */
    const val MOST = 100

    fun of(collection: Collection, listens: List<ListeningHistory.Listen>): List<CloudTrack> {
        if (collection.shelf != Shelf.Own) return emptyList()
        val last = HashMap<String, Long>(collection.plays.size + listens.size)
        for ((id, plays) in collection.plays) if (plays.lastPlayedAt > 0) last[id] = plays.lastPlayedAt
        for (listen in listens) {
            if (listen.ref.shelf != Shelf.Own) continue
            val id = listen.ref.rpmId
            if (listen.at > (last[id] ?: 0)) last[id] = listen.at
        }
        return last.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .asSequence()
            .mapNotNull { collection.tracksById[it.key] }
            .take(MOST)
            .toList()
    }
}
