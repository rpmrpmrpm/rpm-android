package com.rpm.player.core.library

/**
 * Which of the app's genres a tag means. It follows the desktop's `Genres.Match` and the iOS app's
 * `Genres.match`: the same shelves and styles (generated from `scripts/subgenres.json` into
 * [SubgenreTable]), the same aliases and the same order of lookup, so a record is on the same genre in
 * every app.
 */
object Genres {
    /** The shelf somebody picks for a record that fits none of the others. Never a match. */
    const val UNKNOWN = "Other / Unknown"

    /**
     * The shelf this tag means, or null when it means none of them.
     *
     * One walk down the tag's keys ([GenreText.keys]), asking the styles and then the shelves about each
     * key, and the first answer of either kind wins. A style carries its own shelf, so "Deep House" is a
     * House record. Walking the styles first for the whole tag and then the shelves would file "Pop Rap"
     * under Pop, because the shelf table answers to its first word.
     */
    fun match(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        for (key in GenreText.keys(tag)) {
            styleKeys[key]?.let { return it }
            shelfKeys[key]?.let { return it }
        }
        return null
    }

    /**
     * The styles this tag names, each by its name in [SubgenreTable]: "Trip Hop", "Baroque". A tag may list
     * several ("classical, romantic classical, classical period"), so each entry is read on its own. Empty
     * when it names none, which is most tags. The desktop's song radio reads a record's styles the same way.
     */
    fun styles(tag: String?): Set<String> {
        if (tag.isNullOrBlank()) return emptySet()
        val found = LinkedHashSet<String>()
        for (entry in tag.split(',', ';', '|')) {
            if (entry.isBlank()) continue
            for (key in GenreText.keys(entry)) {
                val name = styleNames[key] ?: continue
                found.add(name)
                break
            }
        }
        return found
    }

    /** A genre as it is said in a sentence: the first half of a slashed label, lower cased, and "R&B" kept
     * as it is. The desktop's `Genres.Spoken`. Not translated, like the genre names. */
    fun spoken(genre: String): String {
        val head = genre.split('/').first().trim()
        return if (head.equals("R&B", ignoreCase = true)) "R&B" else head.lowercase()
    }

    /** The genres that stand next to one another, head first. The desktop's `Genres.Families` and the
     * phone's; change all three together. */
    private val families: List<List<String>> = listOf(
        listOf("Rock", "Indie", "Punk", "Metal", "Noise"),
        listOf("Pop", "Indie", "Disco", "Club"),
        listOf("R&B", "Soul", "Funk", "Disco"),
        listOf("Hip-Hop / Rap", "Rap", "Trap", "Old School Hip Hop"),
        listOf("Electronic / Dance", "House", "Techno", "Club"),
        listOf("Folk", "Country", "Blues"),
        listOf("Jazz", "Blues", "Avant-Garde"),
        listOf("Classical", "Neo-Classical", "Opera"),
        listOf("Soundtrack", "Video Game", "Musical"),
        listOf("Avant-Garde", "Noise"),
    )

    /** The genres this one stands next to: the heads of every family it is in first, then the others in
     * those families, each once. The desktop's `Genres.Related`. Empty for a genre in no family. */
    fun related(genre: String): List<String> {
        val related = ArrayList<String>()
        for (heads in listOf(true, false)) {
            for (family in families) {
                val at = family.indexOfFirst { it.equals(genre, ignoreCase = true) }
                if (at < 0) continue
                family.forEachIndexed { i, name ->
                    if (i != at && heads == (i == 0) && related.none { it.equals(name, ignoreCase = true) }) related.add(name)
                }
            }
        }
        return related
    }

    /** What else the catalogues call these. Copied from the desktop's `Genres.Aliases`, which is written by
     * hand there too, as it is in the phone's Genres.swift; change all three together. */
    private val aliases: List<Pair<String, String>> = listOf(
        "rocknroll" to "Rock", "rockandroll" to "Rock",
        "rb" to "R&B", "rnb" to "R&B", "randb" to "R&B", "rhythmandblues" to "R&B",
        "funksoul" to "Funk", "motown" to "Soul",
        "electronica" to "Electronic / Dance", "edm" to "Electronic / Dance",
        "hiphoprap" to "Hip-Hop / Rap",
        "worldmusic" to "World", "ethnic" to "World",
        "gospel" to "Religious", "christian" to "Religious", "spiritual" to "Religious",
        "ost" to "Soundtrack", "filmscore" to "Soundtrack", "stagescreen" to "Soundtrack",
        "spoken" to "Spoken Word", "audiobook" to "Spoken Word",
        "ska" to "Reggae",
        "international" to "World", "score" to "Soundtrack",
        "game" to "Video Game", "vgm" to "Video Game",
        "videogamesoundtrack" to "Video Game", "gamesoundtrack" to "Video Game",
        "rbsoul" to "R&B",
        "worldinternational" to "World",
        "gospelreligious" to "Religious",
        "soundtrackscore" to "Soundtrack",
    )

    /** Every spelling that leads to a shelf: the label, each half of a slashed one (three letters or more,
     * so "R&B" does not give a key "r"), and the aliases. First one wins. */
    private val shelfKeys: Map<String, String> by lazy {
        val keys = HashMap<String, String>()
        for (genre in SubgenreTable.shelves) {
            if (genre == UNKNOWN) continue
            for (key in GenreText.keys(genre, GenreText.SEPARATORS)) {
                if (key.length >= 3) keys.putIfAbsent(key, genre)
            }
        }
        for ((alias, genre) in aliases) keys.putIfAbsent(alias, genre)
        keys
    }

    /** Every style's folded name, and the shelf it hangs off. A name shared by several shelves or spelled
     * like a blocked word gets no key. First one wins. */
    private val styleKeys: Map<String, String> by lazy {
        val keys = HashMap<String, String>()
        for ((name, genre) in SubgenreTable.styles) {
            val key = GenreText.fold(name)
            if (key.isEmpty() || key in SubgenreTable.sharedKeys || key in SubgenreTable.blockedKeys) continue
            keys.putIfAbsent(key, genre)
        }
        keys
    }

    /** The same keys as [styleKeys], to the style's own name instead of its shelf. */
    private val styleNames: Map<String, String> by lazy {
        val keys = HashMap<String, String>()
        for ((name, _) in SubgenreTable.styles) {
            val key = GenreText.fold(name)
            if (key.isEmpty() || key in SubgenreTable.sharedKeys || key in SubgenreTable.blockedKeys) continue
            keys.putIfAbsent(key, name)
        }
        keys
    }
}

/** How a tag is folded and cut into the keys a lookup tries. The desktop's `GenreText`. */
internal object GenreText {
    /** What a shelf label is cut on. */
    val SEPARATORS: Set<Char> = setOf('/', ',', '&', ';', '|')

    /** What a tag is cut into words on when it is looked up. */
    private val WORD_BREAKS: Set<Char> = setOf('/', ',', '&', ';', '|', ' ', '-', '_')

    /**
     * Letters and digits only, lower cased and without accents: "Hip-Hop", "hip hop" and "HipHop" are one
     * key, and so are "Opéra Comique" and "Opera Comique". Decomposing splits "é" into "e" and a mark, and the
     * mark is not a letter.
     */
    fun fold(value: String): String = buildString {
        for (c in java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)) if (c.isLetterOrDigit()) append(c)
    }.lowercase()

    /** The whole label, then its parts cut on [breaks], left to right. */
    fun keys(value: String, breaks: Set<Char>): List<String> {
        val keys = mutableListOf(fold(value))
        if (value.any { it in breaks }) keys += parts(value, breaks)
        return keys
    }

    /** The whole tag, then each phrase (cut on the separators), then each word. Whole before phrase before
     * word, because the more of the tag a key uses the more it knows: "cloud rap, trap" offers "cloud rap"
     * whole before it is cut to "cloud" and "rap". */
    fun keys(value: String): List<String> {
        val keys = mutableListOf(fold(value))
        if (value.any { it in SEPARATORS }) keys += parts(value, SEPARATORS)
        if (value.any { it in WORD_BREAKS }) keys += parts(value, WORD_BREAKS)
        return keys
    }

    private fun parts(value: String, breaks: Set<Char>): List<String> =
        value.split(*breaks.toCharArray()).map(::fold).filter { it.isNotEmpty() }
}
