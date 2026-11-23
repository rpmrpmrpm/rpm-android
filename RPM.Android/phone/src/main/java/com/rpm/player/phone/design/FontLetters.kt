package com.rpm.player.phone.design

import java.nio.ByteBuffer

/**
 * The characters a font file has, read from its character map (`cmap`).
 *
 * **Android has no call that answers this.** `Paint.hasGlyph` lays the text out with the system's fallback fonts, so it
 * says yes to Chinese for a font that has only Latin letters. The title face needs the real answer, to draw the letters
 * it lacks at a size of their own (see [HeaderText]).
 *
 * The Unicode subtables of format 4 (the Basic Multilingual Plane) and format 12 (all planes) are read; format 12 is
 * preferred when the font has both. A character mapped to glyph 0 (the missing-glyph box) is not counted.
 */
internal fun fontLetters(font: ByteArray): Set<Int> {
    val bytes = ByteBuffer.wrap(font)
    fun u16(at: Int) = bytes.getShort(at).toInt() and 0xFFFF

    val tables = u16(4)
    val cmap = (0 until tables).map { 12 + 16 * it }.firstOrNull { bytes.getInt(it) == CMAP_TAG }?.let { bytes.getInt(it + 8) }
        ?: return emptySet()

    var best = -1
    var bestFormat = 0
    for (i in 0 until u16(cmap + 2)) {
        val record = cmap + 4 + 8 * i
        val platform = u16(record)
        val encoding = u16(record + 2)
        val at = cmap + bytes.getInt(record + 4)
        val format = u16(at)
        val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
        if (unicode && (format == 12 || (format == 4 && bestFormat != 12))) {
            best = at
            bestFormat = format
        }
    }

    val letters = HashSet<Int>()
    when (bestFormat) {
        4 -> {
            val twiceSegments = u16(best + 6)
            val ends = best + 14
            val starts = ends + twiceSegments + 2
            val deltas = starts + twiceSegments
            val ranges = deltas + twiceSegments
            for (segment in 0 until twiceSegments / 2) {
                val end = u16(ends + 2 * segment)
                val start = u16(starts + 2 * segment)
                val delta = bytes.getShort(deltas + 2 * segment).toInt()
                val rangeAt = ranges + 2 * segment
                val range = u16(rangeAt)
                for (code in start..end) {
                    if (code == 0xFFFF) continue
                    val glyph = if (range == 0) {
                        (code + delta) and 0xFFFF
                    } else {
                        val listed = u16(rangeAt + range + 2 * (code - start))
                        if (listed == 0) 0 else (listed + delta) and 0xFFFF
                    }
                    if (glyph != 0) letters += code
                }
            }
        }
        12 -> {
            val groups = bytes.getInt(best + 12)
            for (group in 0 until groups) {
                val record = best + 16 + 12 * group
                val start = bytes.getInt(record)
                val end = bytes.getInt(record + 4)
                val firstGlyph = bytes.getInt(record + 8)
                for (code in start..end) if (firstGlyph + (code - start) != 0) letters += code
            }
        }
    }
    return letters
}

/** `cmap` as a big-endian tag. */
private const val CMAP_TAG = 0x636D6170
