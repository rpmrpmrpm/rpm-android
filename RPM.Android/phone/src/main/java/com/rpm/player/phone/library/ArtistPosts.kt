package com.rpm.player.phone.library

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rpm.player.core.R
import com.rpm.player.core.cloud.ApiException
import com.rpm.player.core.cloud.CommunityArtist
import com.rpm.player.core.cloud.CommunityClient
import com.rpm.player.core.cloud.ReportTarget
import com.rpm.player.core.cloud.WallEntry
import com.rpm.player.core.library.ArtistCredit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What listeners wrote on one artist's wall, and what the reader is typing there: the state behind the wall at the foot
 * of the artist page. It is the iPhone's `ArtistPosts` (rpm/ArtistPosts.swift). It is kept by the shell for each artist
 * ([ArtistPostsBook]), for the reason [ReviewWalls] gives.
 *
 * **Reading never creates the artist's entry.** rpm has no entry for most artists, and reading their wall must not make
 * one. The entry is created by the first post ([post]).
 *
 * **The newest read wins, not the last one to answer.** The wall is read when the page opens, and again after every
 * post and delete. These reads can overlap: a post can be sent while the first read is still on its way. Each read
 * takes a number, and its answer is used only while no newer read has started. A read starts after its own write has
 * finished, so the newest read always sees every write that came before it.
 *
 * The state is Compose state, read by the page as it draws. Every call runs on the main thread; only the requests leave
 * it. Posts and deletes run in [scope], the app's, so leaving the page does not cut one off half sent.
 *
 * @param name the artist as the collection names them, which is also the name rpm keeps them under.
 */
class ArtistPosts(private val community: CommunityClient, private val scope: CoroutineScope, val name: String) {
    /** rpm's entry for the artist. Null while the wall is being read, and after a read that found nobody has written about them. */
    private var entry: CommunityArtist? = null

    var lines by mutableStateOf<List<WallEntry>>(emptyList())
        private set

    /** Whether the wall has been read. False while it is being read and after a failed read, so a wall that could not be read is not shown as an empty one. */
    var answered by mutableStateOf(false)
        private set

    /** Why the wall could not be read or written, or null. */
    var trouble by mutableStateOf<Said?>(null)
        private set

    /** Whether a post is on its way. */
    var sending by mutableStateOf(false)
        private set

    /** What is typed in the box. It is kept here and not in the page, so it survives the page being built again. */
    var draft by mutableStateOf("")

    /** The number of the newest read. See the class's notes. */
    private var newest = 0

    // ── reading ──

    /** Reads the wall: the artist's entry by name, then its lines. An artist with no entry has a wall with nothing on it. */
    suspend fun read() {
        newest += 1
        val ticket = newest
        try {
            val found = entry ?: community.lookupArtist(name)
            val said = found?.let { community.wall(it.artistId) }.orEmpty()
            if (ticket != newest) return
            if (found != null) entry = found
            lines = said
            answered = true
            trouble = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ticket != newest) return
            trouble = message(e)
        }
    }

    // ── writing ──

    /**
     * Sends what is in the box. The artist's entry is created here when it does not exist yet: the first person to write
     * about an artist makes the entry.
     *
     * The wall is read again afterwards rather than the new line added to the list. Other people may have written while
     * this was typed, and the list should show what the server holds.
     */
    fun post() {
        val body = draft.trim()
        if (body.isEmpty() || sending) return
        sending = true
        // The key keeps its spinner until the wall has been read again, so the new line is there when it stops.
        scope.launch {
            try {
                val sent = try {
                    val artist = entry ?: community.resolveArtist(name)
                    entry = artist
                    community.postToWall(artist.artistId, body)
                    // Only the text that was sent is removed. Anything typed while it was on its way stays.
                    if (draft.trim() == body) draft = ""
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trouble = message(e)
                    false
                }
                if (sent) read()
            } finally {
                sending = false
            }
        }
    }

    /** Removes the reader's own post. */
    fun delete(line: WallEntry) {
        val entry = entry ?: return
        scope.launch {
            try {
                community.deleteWallEntry(entry.artistId, line.entryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trouble = message(e)
                return@launch
            }
            read()
        }
    }

    /** Reports somebody else's line. Null when it was sent, or why it failed. */
    suspend fun report(line: WallEntry, note: String): Said? {
        val entry = entry ?: return Said(R.string.Artists_WallOffline)
        return try {
            community.report(ReportTarget.WallPost(entry.artistId, line.entryId), reason = "wallpost", note = note.ifEmpty { null })
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(e)
        }
    }

    /** What to tell the reader about a failure, as the iPhone's `RPMError.userMessage(refused:)` words it. */
    private fun message(e: Exception, @StringRes refused: Int = R.string.Artists_WallOffline): Said = when (e) {
        is ApiException.Unauthorized -> Said(R.string.Rpm_Error_SessionExpired)
        is ApiException.NotFound -> Said(refused)
        is ApiException.Status -> Said(R.string.Rpm_Error_Status, e.code)
        is java.io.IOException -> Said(R.string.Rpm_Net_Failed)
        else -> Said(refused)
    }
}

/**
 * One wall per artist, kept for the life of the shell. Two pages of one artist can be on two stacks at once, and each
 * shows the same wall, with the same words half typed in its box.
 */
class ArtistPostsBook(private val community: CommunityClient, private val scope: CoroutineScope) {
    private val walls = HashMap<String, ArtistPosts>()

    /** The artist's wall, or null for an artist with none: the compilations name nobody, and rpm refuses to make an entry for them. */
    fun of(name: String): ArtistPosts? =
        if (ArtistCredit.isVariousArtists(name)) null else walls.getOrPut(name) { ArtistPosts(community, scope, name) }
}
