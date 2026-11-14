package com.rpm.player.core.cloud

import androidx.annotation.StringRes
import com.rpm.player.core.R
import kotlinx.serialization.Serializable

/**
 * rpm's shared entry for one artist: the id of their wall, and the name rpm keeps for them. It is the iPhone's
 * `RPMArtist` (RPMKit/Wire/CommunityArtists.swift).
 *
 * The entry is created by the first person who writes on the wall, not by reading it. So most artists have no entry,
 * and an artist page usually reads an empty wall.
 */
@Serializable
data class CommunityArtist(
    val artistId: String,
    val name: String = "",
    /** Milliseconds since 1970, like every stamp on the community routes. */
    val createdAt: Long = 0,
)

/**
 * One line on an artist's wall: something a listener wrote, or a line the server wrote when a listener reviewed or
 * rated one of the artist's records. It is the iPhone's `RPMWallEntry`.
 *
 * Read the kind through [isPost] and [isReview]. A kind that a newer server sends and this build does not know is
 * neither, so it is not drawn as a post with no words.
 */
@Serializable
data class WallEntry(
    val artistId: String = "",
    val entryId: String,
    /** "post" or "review". */
    val kind: String = "",
    val userId: String = "",
    val authorName: String = "",
    val hasAvatar: Boolean = false,
    /** What a listener wrote, on a post. Empty on a review line: the record page is where the review itself is read. */
    val body: String = "",
    /** Milliseconds since 1970. */
    val createdAt: Long = 0,
    /** The record a review line is about, as the album entry spells it. Null on a post. */
    val albumId: String? = null,
    val albumTitle: String? = null,
    /** Halves of a star, doubled: 1 to 10. Zero on a post. */
    val rating: Int = 0,
    /** Whether the review behind a review line has words in it. It decides between "wrote a review" and "rated". */
    val hasBody: Boolean = false,
    /** As on [Review.authorDeleted]. */
    val authorDeleted: Boolean = false,
) {
    val isPost: Boolean get() = kind.equals("post", ignoreCase = true)

    val isReview: Boolean get() = kind.equals("review", ignoreCase = true)

    /** The rating in stars, halves included. Zero on a post. */
    val stars: Double get() = rating / 2.0

    /**
     * The string of a review line's sentence, which takes the record's title, or null for a post, whose line is the
     * author's own words. It is a resource and not the text, so it is read without a context.
     */
    @get:StringRes
    val lineKey: Int?
        get() = if (!isReview) null else if (hasBody) R.string.Artists_WallWroteReview else R.string.Artists_WallRated
}

/**
 * What a report is about. Each case carries what the server needs to open the report: the album for the three things
 * on a record page, the artist for a line on an artist's wall. The server refuses a report without them, so the type
 * does not allow one. It is the iPhone's `RPMReportTarget`.
 */
sealed interface ReportTarget {
    data class Album(val albumId: String) : ReportTarget

    data class Review(val albumId: String, val authorId: String) : ReportTarget

    data class Comment(val albumId: String, val commentId: String) : ReportTarget

    data class WallPost(val artistId: String, val entryId: String) : ReportTarget
}
