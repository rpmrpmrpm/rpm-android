package com.rpm.player.core.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A track's full tag surface, as the uploader wrote it next to the user's own file: the iPhone's `TagSidecar`.
 *
 * **PascalCase, unlike everything else in the cloud's answers.** The sidecar is a blob the uploader wrote and the store
 * hands back untouched, not a Lambda response, so it carries .NET property names rather than the API's camelCase.
 * Read with no names given, every field would come back as its default and the sidecar would look empty.
 *
 * Only the fields a client reads are here. The blob carries thirty more, and the reader ignores what it is not asked
 * for. The cover inside the file (`Art`, base64) is left out too: nothing on Android reads it, and it is as big as the
 * cover.
 *
 * @property copyright the copyright line, as the file's tags have it. The catalogue row does not carry it, so the
 * sidecar is the only place a phone can read it.
 * @property publisher the record label, the same way.
 */
@Serializable
data class TagSidecar(
    /** Where the file belongs under the library root, forward slashes. */
    @SerialName("RelativePath") val relativePath: String = "",
    /** Lower-case extension including the dot. Says which container the blob is. */
    @SerialName("Extension") val extension: String = "",
    @SerialName("RpmId") val rpmId: String? = null,
    @SerialName("DurationSeconds") val durationSeconds: Double = 0.0,
    @SerialName("Title") val title: String? = null,
    @SerialName("Artist") val artist: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("Genre") val genre: String? = null,
    @SerialName("TrackNumber") val trackNumber: Int = 0,
    @SerialName("TrackCount") val trackCount: Int = 0,
    @SerialName("DiscNumber") val discNumber: Int = 0,
    @SerialName("DiscCount") val discCount: Int = 0,
    @SerialName("Copyright") val copyright: String? = null,
    @SerialName("Publisher") val publisher: String? = null,
    @SerialName("ArtMime") val artMime: String? = null,
)
