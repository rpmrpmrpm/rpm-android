package com.rpm.player.core.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The library search's answers that are not records or songs ([SongSearch]): the artists, the playlists and the
 * genres whose names hold the words. The library's search finds every kind of thing the collection is made of, so a
 * name typed is found whichever view it lives in.
 *
 * **Names are compared folded, as the artists view's own filter compares them** ([ArtistWall.searchKey]): case, accents,
 * spaces and punctuation are left out, so "beyonce" finds Beyoncé and "acdc" finds AC/DC. Each answer keeps the order
 * it is given in.
 */
object CollectionSearch {
    /** The artists whose name holds [asked]. */
    suspend fun artists(artists: List<ArtistWallItem>, asked: String): List<ArtistWallItem> = named(artists, asked) { it.name }

    /** The genres whose name holds [asked]. */
    suspend fun genres(genres: List<GenreWallItem>, asked: String): List<GenreWallItem> = named(genres, asked) { it.name }

    /**
     * The playlists whose name holds [asked]. Each list comes with the name it is shown by, which the caller words
     * (a mood or a decade list is named in the reader's language, see `listName`), so the search finds what is on screen.
     */
    suspend fun playlists(lists: List<Pair<TrackList, String>>, asked: String): List<Pair<TrackList, String>> =
        named(lists, asked) { it.second }

    private suspend fun <T> named(things: List<T>, asked: String, name: (T) -> String): List<T> {
        val wanted = ArtistWall.searchKey(asked)
        if (wanted.isEmpty()) return emptyList()
        val found = ArrayList<T>()
        for (thing in things) {
            currentCoroutineContext().ensureActive()
            if (ArtistWall.searchKey(name(thing)).contains(wanted)) found += thing
        }
        return found
    }
}
