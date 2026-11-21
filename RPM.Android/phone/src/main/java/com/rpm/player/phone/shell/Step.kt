package com.rpm.player.phone.shell

import androidx.compose.runtime.saveable.Saver

/**
 * One page pushed on a tab's stack: a record by its album key, a playlist by its id, an artist by name, a
 * genre by name, the queue, or the marked songs. A step stores a name, not the data, so the page is drawn
 * from the collection on every pass. When the collection is read again, the page redraws with the new rows.
 * The iPhone app has the same `LibraryStep`.
 */
sealed interface Step {
    /** The step as text. The stack is saved as this text, and the page's saved state is kept under the same key. */
    val saved: String

    data class Record(val key: String) : Step {
        override val saved get() = RECORD + key
    }

    data class Playlist(val id: String) : Step {
        override val saved get() = PLAYLIST + id
    }

    data class Artist(val name: String) : Step {
        override val saved get() = ARTIST + name
    }

    data class Genre(val name: String) : Step {
        override val saved get() = GENRE + name
    }

    /** The queue. There is only one, so it needs no name. */
    data object Queue : Step {
        override val saved get() = QUEUE
    }

    /** The marked songs. There is only one such list, so it needs no name. */
    data object Marked : Step {
        override val saved get() = MARKED
    }

    /** The songs played lately, newest first. There is only one such list, so it needs no name. */
    data object History : Step {
        override val saved get() = HISTORY
    }

    companion object {
        private const val RECORD = "record:"
        private const val PLAYLIST = "list:"
        private const val ARTIST = "artist:"
        private const val GENRE = "genre:"
        private const val QUEUE = "queue"
        private const val MARKED = "marked"
        private const val HISTORY = "history"

        fun of(saved: String): Step? = when {
            saved.startsWith(RECORD) -> Record(saved.removePrefix(RECORD))
            saved.startsWith(PLAYLIST) -> Playlist(saved.removePrefix(PLAYLIST))
            saved.startsWith(ARTIST) -> Artist(saved.removePrefix(ARTIST))
            saved.startsWith(GENRE) -> Genre(saved.removePrefix(GENRE))
            saved == QUEUE -> Queue
            saved == MARKED -> Marked
            saved == HISTORY -> History
            else -> null
        }

        /** A whole stack, saved as the steps' text. It survives the activity being recreated. */
        val stack: Saver<List<Step>, ArrayList<String>> = Saver(
            save = { steps -> ArrayList(steps.map { it.saved }) },
            restore = { saved -> saved.mapNotNull(::of) },
        )
    }
}
