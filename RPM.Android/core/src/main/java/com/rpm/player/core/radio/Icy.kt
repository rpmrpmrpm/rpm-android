package com.rpm.player.core.radio

/**
 * What a station announces, made printable. These are ports of `IcyFields`, `IcyTitle` and `RadioAds`
 * in RPM.Core. Media3 reads the `StreamTitle` out of the stream. These functions decide how much of it to show.
 */
object Icy {

    /** What to print for an announcement: an ad break, the artist and title, or a line of text. */
    sealed interface Said {
        data object AdBreak : Said
        data class Song(val artist: String?, val title: String) : Said
    }

    /**
     * The announcement as it should read under [station]'s name, or null when there is nothing to
     * print: blank, the end of an ad break, or only the station's own name.
     */
    fun said(announced: String?, station: String): Said? {
        val spoken = Fields.read(announced?.let(::repaired)) ?: return null
        if (isBreakOver(spoken)) return null
        if (isAdBreak(spoken)) return Said.AdBreak
        val text = withoutStation(spoken, station)?.takeIf { it.isNotBlank() } ?: return null
        if (text.equals(station.trim(), ignoreCase = true)) return null
        val (artist, title) = split(text)
        return Said.Song(artist, title ?: text)
    }

    /**
     * The announcement with a common station fault undone: text the station had already turned into UTF-8 once, read
     * as Windows-1252 and turned into UTF-8 again, so "TIËSTO" arrives as "TIÃ‹STO". The iPhone's
     * `StationSaid.repaired` does the same.
     *
     * Only text with the fault's mark ("Ã", "Â" or "â€") is tried, and only a result that is whole UTF-8 is kept, so
     * correct text such as "Beyoncé" or "Ã la carte" is never changed.
     */
    fun repaired(text: String): String {
        if (!text.contains('Ã') && !text.contains('Â') && !text.contains("â€")) return text
        val bytes = try {
            WINDOWS_1252.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text))
        } catch (failed: java.nio.charset.CharacterCodingException) {
            return text
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(bytes).toString()
        } catch (failed: java.nio.charset.CharacterCodingException) {
            text
        }
    }

    private val WINDOWS_1252: java.nio.charset.Charset = java.nio.charset.Charset.forName("windows-1252")

    private val SEPARATORS = listOf(" - ", " – ", " — ", " -- ")

    /** Splits artist and title on the earliest separator with spaces around it, so "Jay-Z" survives and
     * "A - B - C" gives artist "A" and title "B - C". There is no artist when the text does not look like a pair. */
    fun split(announced: String?): Pair<String?, String?> {
        val text = announced?.trim()
        if (text.isNullOrEmpty()) return null to null
        var at = -1
        var width = 0
        for (separator in SEPARATORS) {
            val found = text.indexOf(separator)
            if (found > 0 && (at < 0 || found < at)) {
                at = found
                width = separator.length
            }
        }
        if (at < 0) return null to text
        val artist = text.substring(0, at).trim()
        val title = text.substring(at + width).trim()
        if (artist.isEmpty() || title.isEmpty()) return null to text
        return artist to title
    }

    /** The announcement without a leading station name and separator ("Station Name - Now playing"),
     * or whole when taking it off would leave nothing. */
    fun withoutStation(announced: String?, station: String?): String? {
        val text = announced?.trim()
        if (text.isNullOrEmpty() || station.isNullOrBlank()) return text
        val name = station.trim()
        if (!text.startsWith(name, ignoreCase = true)) return text
        val rest = text.substring(name.length)
        for (separator in SEPARATORS) {
            if (rest.startsWith(separator)) return rest.substring(separator.length).trim().ifEmpty { text }
        }
        return text
    }

    fun isAdBreak(announced: String?): Boolean = fold(announced.orEmpty()).let { it.isNotEmpty() && it in BREAKS }

    fun isBreakOver(announced: String?): Boolean = fold(announced.orEmpty()).let { it.isNotEmpty() && it in OVERS }

    private fun fold(text: String): String {
        val folded = StringBuilder(text.length)
        for (c in text) {
            val letter = deaccent(c.lowercaseChar())
            if (letter.isLetterOrDigit()) {
                folded.append(letter)
            } else if (folded.isNotEmpty() && folded.last() != ' ') {
                folded.append(' ')
            }
        }
        return folded.toString().trimEnd()
    }

    private fun deaccent(c: Char): Char = when (c) {
        'é', 'è', 'ê', 'ë' -> 'e'
        'à', 'â', 'á', 'ä' -> 'a'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        else -> c
    }

    private val BREAKS = setOf(
        "ad", "ads", "advert", "adverts", "advertisement", "advertisements", "advertising",
        "ad break", "adbreak", "commercial", "commercials", "commercial break",
        "reclame", "reklame", "reclames", "werbung", "werbepause",
        "publicite", "publicidad", "publicidade", "pubblicita", "anuncios", "spot publicitario",
        "spot block", "spot block start",
    )

    private val OVERS = setOf("spot block end")

    /**
     * The two field shapes that some broadcasters put in the title: Triton and iHeart's
     * `Artist - Title text="..." song_spot="M"`, and `title="..",artist="..",url=".."`. Returns the line
     * to print, with "Artist - Title" for a song. A spot of `T` is the station talking about itself, and
     * comes back as its text alone.
     */
    private object Fields {
        fun read(announced: String?): String? {
            val text = announced?.trim()
            if (text.isNullOrEmpty()) return null
            if (isListed(text)) return readListed(text)
            val fields = firstField(text)
            if (fields < 0) return head(text).ifEmpty { null }
            val head = head(text.substring(0, fields))
            val said = field(text.substring(fields), "text")?.trim().orEmpty()
            if (head.isEmpty()) return said.ifEmpty { null }
            if (said.isEmpty()) return head
            return if (isRecord(text.substring(fields))) "$head - $said" else said
        }

        private fun readListed(text: String): String? {
            val title = listed(text, "title")?.trim().orEmpty()
            val artist = listed(text, "artist")?.trim().orEmpty()
            if (title.isEmpty()) return artist.ifEmpty { null }
            if (artist.isEmpty() || !isRecord(listed(text, "url").orEmpty())) return title
            return "$artist - $title"
        }

        private fun isListed(text: String): Boolean =
            text.startsWith("title=\"", ignoreCase = true) || text.startsWith("artist=\"", ignoreCase = true)

        private fun listed(text: String, name: String): String? {
            val key = "$name=\""
            var at = text.indexOf(key, ignoreCase = true)
            while (at >= 0) {
                if (at == 0 || text[at - 1] == ',') {
                    val start = at + key.length
                    var end = start
                    while (end + 1 < text.length) {
                        if (text[end] == '"' && text[end + 1] == ',' && startsField(text, end + 2)) return text.substring(start, end)
                        end++
                    }
                    val value = text.substring(start)
                    return if (value.endsWith('"')) value.dropLast(1) else value
                }
                at = text.indexOf(key, at + 1, ignoreCase = true)
            }
            return null
        }

        private fun startsField(text: String, i: Int): Boolean {
            var end = i
            while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            return end > i && end + 1 < text.length && text[end] == '=' && text[end + 1] == '"'
        }

        private fun isRecord(fields: String): Boolean {
            val spot = field(fields, "song_spot")?.trim() ?: return true
            return spot.equals("M", ignoreCase = true) || spot.equals("F", ignoreCase = true)
        }

        private fun head(text: String): String {
            var head = text.trim()
            while (head.isNotEmpty() && head.last() in "-–—") head = head.dropLast(1).trimEnd()
            return head
        }

        private fun firstField(text: String): Int {
            for (i in text.indices) {
                if (!text[i].isLetter() || (i > 0 && !text[i - 1].isWhitespace())) continue
                var end = i
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                if (end > i && end + 1 < text.length && text[end] == '=' && text[end + 1] == '"') return i
            }
            return -1
        }

        private fun field(fields: String, name: String): String? {
            val key = "$name=\""
            var at = fields.indexOf(key, ignoreCase = true)
            while (at >= 0) {
                if (at == 0 || fields[at - 1].isWhitespace()) {
                    val start = at + key.length
                    val end = fields.indexOf('"', start)
                    return if (end < 0) fields.substring(start) else fields.substring(start, end)
                }
                at = fields.indexOf(key, at + 1, ignoreCase = true)
            }
            return null
        }
    }
}
