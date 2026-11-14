package com.rpm.player.core.library

import java.text.Normalizer

/**
 * The copyright line and the record label of a record, as its files' tags give them, and the lines the album page draws
 * under its songs. The iPhone's `AlbumCredits` (RPMLibrary/AlbumCredits.swift).
 *
 * The tags are read from one song's sidecar ([com.rpm.player.core.cloud.TagSidecar]), because the track row does not
 * carry them. Both are album-level tags, and the desktop writes the same value on every song of a record. Blank tags
 * are kept as none, so two credits with nothing in them are equal.
 */
class AlbumCredits(copyright: String?, label: String?) {
    val copyright: String? = cleaned(copyright)
    val label: String? = cleaned(label)

    /**
     * The lines under the songs, copyright first. Empty when the tags have neither.
     *
     * **The label is left out where the copyright line already names it**, because "℗ 1998 Warp Records Limited"
     * over "Warp Records" says the same thing twice.
     *
     * The copyright line is drawn as the tag has it. A tag that does not start with a copyright or sound recording mark
     * gets "©", so the line reads as a copyright and not as a second label.
     */
    val lines: List<String> = buildList {
        this@AlbumCredits.copyright?.let { add(marked(it)) }
        val named = this@AlbumCredits.label
        if (named != null && this@AlbumCredits.copyright?.let { names(it, named) } != true) add(named)
    }

    /** Whether the record has anything to show. */
    val isEmpty: Boolean get() = lines.isEmpty()

    override fun equals(other: Any?): Boolean = other is AlbumCredits && other.copyright == copyright && other.label == label

    override fun hashCode(): Int = 31 * (copyright?.hashCode() ?: 0) + (label?.hashCode() ?: 0)

    override fun toString(): String = "AlbumCredits(copyright=$copyright, label=$label)"

    private companion object {
        /** The marks a copyright tag can start with: the copyright and sound recording signs, and the ASCII forms some
         *  taggers write instead. */
        val MARKS = listOf("©", "℗", "(c)", "(p)", "copyright")

        val ACCENTS = Regex("\\p{Mn}+")

        fun cleaned(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

        fun marked(copyright: String): String {
            val lower = copyright.lowercase()
            return if (MARKS.any { lower.startsWith(it) }) copyright else "© $copyright"
        }

        /** Whether [copyright] contains [label], ignoring case and accents. */
        fun names(copyright: String, label: String): Boolean = folded(copyright).contains(folded(label))

        fun folded(text: String): String = ACCENTS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
    }
}
