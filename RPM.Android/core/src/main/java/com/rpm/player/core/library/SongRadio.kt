package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * One song a radio can play or start from.
 *
 * [record] is the album's key: a radio spaces records by it. [song] is the act and the title with the edition notes
 * taken off, lower cased: two tracks with the same value are one song on two records. [genre] is the shelf the
 * record is filed on, [styles] the styles its tags name, [moods] the moods the computer put it in. [live] is whether
 * it is a live recording ([SongRadio.isLive]).
 */
data class RadioSong(
    val id: String,
    val record: String,
    val artist: String,
    val song: String,
    val year: Int,
    val genre: String?,
    val styles: Set<String>,
    val moods: Set<String>,
    val offered: Boolean,
    val plays: Int,
    val live: Boolean = false,
)

/**
 * Every song in one collection that a radio can pick from or start from. Built once per collection, off the main
 * thread, and held by the radios on it, so picking reads nothing.
 */
class RadioCrate(val songs: List<RadioSong>) {
    private val byId: Map<String, RadioSong> = songs.associateBy { it.id }

    private val favourites: Set<String> = songs
        .filter { it.offered && it.plays >= FAVOURITE_LEAST_PLAYS }
        .sortedWith(compareByDescending<RadioSong> { it.plays }.thenBy { it.id })
        .take(FAVOURITE_POOL)
        .mapTo(HashSet()) { it.id }

    fun find(id: String): RadioSong? = byId[id]

    /** Whether the song is one of the [FAVOURITE_POOL] the listener plays most. */
    fun isFavourite(song: RadioSong): Boolean = song.id in favourites

    companion object {
        /** How many plays make a favourite. Once or twice is a song that was tried. */
        const val FAVOURITE_LEAST_PLAYS = 3

        /** How many of the most played songs count as favourites. */
        const val FAVOURITE_POOL = 150

        /** A track shorter than this is an interlude: it can start a radio but is never picked. The desktop's
         * `Moods.LeastSeconds`. */
        const val LEAST_SECONDS = 60.0

        val EMPTY = RadioCrate(emptyList())

        /**
         * The crate for [collection]. A record's shelf is the one most of its tracks' tags name, as the desktop
         * files a record by its majority, so one oddly tagged track does not move it.
         */
        fun of(collection: Collection): RadioCrate {
            val shelfOf = HashMap<String, String?>()
            for (album in collection.albums) {
                shelfOf[album.key] = album.tracks.mapNotNull { Genres.match(it.tags?.genre) }
                    .groupingBy { it }.eachCount()
                    .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
            }
            val stylesOf = HashMap<String, Set<String>>()
            val songs = collection.tracksById.values.map { track ->
                val record = collection.albumOf[track.rpmId]?.key ?: track.rpmId
                val tag = track.tags?.genre
                val styles = if (tag == null) emptySet() else stylesOf.getOrPut(tag) { Genres.styles(tag) }
                RadioSong(
                    id = track.rpmId,
                    record = record,
                    artist = (track.tags?.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist).orEmpty().trim(),
                    song = songOf(track),
                    year = track.tags?.year ?: 0,
                    genre = shelfOf[record] ?: Genres.match(tag),
                    styles = styles,
                    moods = collection.moodsById[track.rpmId].orEmpty(),
                    offered = (track.tags?.durationSeconds ?: 0.0) >= LEAST_SECONDS,
                    plays = collection.plays[track.rpmId]?.count ?: 0,
                    live = SongRadio.isLive(track.title, track.album, track.tags?.releaseType),
                )
            }
            return RadioCrate(songs)
        }

        /** Notes about which recording this is: "(2011 Remaster)", "[Live]", "(feat. …)". */
        private val ASIDE = Regex("""\s*[(\[][^)\]]*\b(remaster\w*|live|edit|version|mix|mono|stereo|deluxe|bonus|demo|feat\.?|ft\.?|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

        /** The value two copies of one song share. An untitled track keeps its own id, so it is never taken for
         * another untitled one. */
        internal fun songOf(track: CloudTrack): String {
            val title = (track.title ?: "").replace(ASIDE, "").trim()
            return if (title.isEmpty()) track.rpmId else (track.artist ?: "").trim().lowercase() + "\n" + title.lowercase()
        }
    }
}

/**
 * Picks the songs a song radio plays next, as the desktop's `SongRadio` does: songs close to the one playing in mood,
 * era and genre, down to the style (trip hop is not electro), with the song the radio started from counting for half
 * as much again so a long session does not drift. Every [FAVOURITE_EVERY]th song is one the listener plays most,
 * when one fits as well as the closest few percent of the collection; one that does not fit is not forced in.
 *
 * The phone has less to go on than the computer. It has no mood readings and no tempo, only which of the
 * computer's mood lists a song is in, and no styles but the ones a song's own genre tag names. So the mood term is
 * the lists alone, and tempo is not asked.
 *
 * Pure: the crate, the queue and the history are handed in, and the answer is a list of rpmIds.
 */
object SongRadio {
    /** How many songs a radio keeps queued after the one playing. */
    const val AHEAD = 10

    /** Every this many songs the radio queues, one is a favourite where one fits. Counted from the song the radio
     * started from. */
    const val FAVOURITE_EVERY = 5

    internal const val ORIGIN_WEIGHT = 0.5
    internal const val SHORTLIST = 6
    internal const val ARTIST_GAP = 5
    internal const val RECORD_GAP = 12
    internal const val ERA_SPAN = 15.0

    /** What a term gives when either song lacks the fact it compares: halfway, so an unknown is never taken for a
     * match. */
    internal const val UNKNOWN = 0.5
    internal const val RELATED_GENRE = 0.5
    internal const val KIN_STYLE = 0.3
    internal const val OTHER_STYLE = 0.6
    /** As far as kin: a matching style comes clearly before one nobody has filed. */
    internal const val ONE_STYLE = KIN_STYLE

    /** How many songs must be left, beyond the ones a pick asks for, before songs whose style clashes with the song
     * playing are left out. */
    internal const val CLASH_SPARE = SHORTLIST

    /** What a live recording adds to its score when the radio did not start from a live song: about a decade of
     * era. A live song still comes when nothing in the studio is near. The desktop's `LiveApart`. */
    internal const val LIVE_APART = 0.3

    /** The mood term when one song is in a mood list and the other in none: nearer apart than together. */
    internal const val ONE_IN_A_MOOD = 0.75

    /** How close a favourite must be: among this share of what the radio could play now, never fewer than a
     * shortlist. */
    internal const val FAVOURITE_FIT = 0.05

    /**
     * Styles that sit next to one another. The desktop's `SongRadio.StyleFamilies`; change both together. Every shelf
     * has its families, because two styles that share none clash, and a song whose style clashes with the one playing
     * is left out ([withoutClashes]).
     */
    internal val STYLE_FAMILIES: List<List<String>> = listOf(
        // Electronic music for sitting down.
        listOf("Downtempo", "Trip Hop", "Chillout", "Chillwave", "Lo-fi Beats", "Ambient", "New Age", "Folktronica",
            "Vaporwave", "Ambient Techno", "Ambient Classical"),
        // For listening rather than dancing, but not to sleep to.
        listOf("IDM", "Leftfield", "Electronica", "Progressive Electronic", "Downtempo", "Trip Hop"),
        // For a club.
        listOf("Electro", "Electro House", "Trance", "Psytrance", "Eurodance", "Italo Dance", "Hardstyle", "Future Bass",
            "Alternative Dance", "Deep House", "Tech House", "Progressive House", "Future House", "French House",
            "Garage House", "Tribal House", "Acid House", "Chicago House", "Afro House", "Euro House",
            "Detroit Techno", "Minimal Techno", "Acid Techno", "Hard Techno", "Industrial Techno"),
        // Breaks and bass.
        listOf("Drum and Bass", "Jungle", "Breakbeat", "Dubstep", "Post-Dubstep", "UK Garage", "Speed Garage", "Future Bass"),
        // The dark end.
        listOf("Synthwave", "Dark Wave", "Ethereal Wave", "Minimal Wave", "Witch House", "Industrial", "Post-Industrial",
            "Dark Ambient"),
        // Pop for dancing, and the dance music it shares a floor with.
        listOf("Dance-Pop", "Electropop", "Europop", "Synth-Pop", "Teen Pop", "Bubblegum Pop", "Hyperpop", "K-Pop", "J-Pop",
            "Eurodance", "Italo Dance", "Hi-NRG", "Italo-Disco"),
        // Pop to sit back to.
        listOf("Ballad", "Easy Listening", "Adult Contemporary", "Traditional Pop", "Sophisti-Pop", "Soft Rock",
            "Chanson Française"),
        // Pop for listening closely.
        listOf("Art Pop", "Progressive Pop", "Psychedelic Pop", "Baroque Pop", "Alternative Pop", "Sophisti-Pop"),
        // ── Rock ──
        // Classic and hard rock: big riffs, big choruses.
        listOf("Classic Rock", "Hard Rock", "Arena Rock", "AOR", "Blues Rock", "Southern Rock", "Glam Rock", "Heartland Rock",
            "Pub Rock", "Roots Rock", "Glam Metal", "Heavy Metal",
            // Folk sits beside it: the same records, the same years, often the same players.
            "Folk Rock", "Contemporary Folk", "Folk Pop", "Folk Revival", "Traditional Folk", "Alternative Folk",
            "Indie Folk", "Singer-Songwriter"),
        // Soft, acoustic and rootsy.
        listOf("Soft Rock", "Acoustic Rock", "Folk Rock", "Country Rock", "Piano Rock", "Singer-Songwriter", "Heartland Rock",
            "Roots Rock", "AOR", "Americana", "Folk Pop", "Contemporary Folk", "Folk Revival", "Traditional Folk",
            "Celtic Folk", "Alternative Folk", "Indie Folk"),
        // Rock with a pop song in it.
        listOf("Pop Rock", "Power Pop", "Soft Rock", "Piano Rock", "AOR", "Britpop", "Jangle Pop", "Indie Pop", "Pop Punk"),
        // Alternative and grunge.
        listOf("Alternative Rock", "Grunge", "Post-Grunge", "Indie Rock", "Britpop", "Garage Rock Revival", "Noise Rock",
            "Alternative Metal", "Shoegaze", "Post-Punk Revival", "Emo", "Alternative Punk"),
        // Progressive, psychedelic and art rock.
        listOf("Progressive Rock", "Psychedelic Rock", "Art Rock", "Space Rock", "Krautrock", "Experimental Rock", "Post-Rock",
            "Symphonic Rock", "Acid Rock", "Neo-Psychedelia", "Progressive Metal", "Avant-Prog", "Art Pop",
            "Progressive Pop"),
        // The eighties' new wave, with its synthesisers.
        listOf("New Wave", "New Romantic", "Synth-Pop", "Post-Punk", "Gothic Rock", "Dance-Rock", "Dark Wave", "Minimal Wave",
            "Sophisti-Pop"),
        // Rock and roll and the garage it grew up in.
        listOf("Rock and Roll", "Rockabilly", "Surf Rock", "Merseybeat", "Garage Rock", "Doo-Wop", "Proto-Punk"),
        // Heavy, with machines or a rapper in it.
        listOf("Industrial Rock", "Industrial Metal", "Nu Metal", "Rap Rock", "Alternative Metal", "Industrial", "Electropunk",
            "Funk Rock"),

        // ── Metal ──
        listOf("Heavy Metal", "Glam Metal", "Power Metal", "Speed Metal", "Thrash Metal", "Pop Metal", "Symphonic Metal",
            "Gothic Metal", "Folk Metal"),
        listOf("Death Metal", "Black Metal", "Deathcore", "Metalcore", "Thrash Metal", "Speed Metal", "Djent"),
        listOf("Doom Metal", "Sludge Metal", "Post-Metal", "Drone", "Post-Rock"),
        listOf("Nu Metal", "Alternative Metal", "Industrial Metal", "Rap Rock", "Metalcore", "Post-Grunge", "Djent"),

        // ── Punk ──
        listOf("Punk Rock", "Pop Punk", "Skate Punk", "Ska Punk", "Street Punk", "Hardcore Punk", "Garage Punk", "Proto-Punk",
            "Riot Grrrl", "Crust Punk", "Powerviolence"),
        listOf("Post-Punk", "Post-Hardcore", "Emo", "Screamo", "Alternative Punk"),
        listOf("Folk Punk", "Anti-Folk"),

        // ── Indie ──
        listOf("Indie Pop", "Bedroom Pop", "Dream Pop", "Jangle Pop", "Chamber Pop", "Lo-fi", "Indie Folk", "Indietronica",
            "Alternative Pop"),
        listOf("Indie Rock", "Shoegaze", "Slowcore", "Neo-Psychedelia", "Post-Punk Revival", "Garage Rock Revival", "Lo-fi",
            "Dream Pop"),

        // ── Hip-hop and rap ──
        // Thoughtful, sampled, jazzy.
        listOf("Conscious Hip Hop", "Alternative Hip Hop", "Jazz Rap", "Political Hip Hop", "Boom Bap", "Golden Age Hip Hop",
            "East Coast Hip Hop", "Turntablism", "Experimental Hip Hop", "Contemporary R&B"),
        // Hard, from the streets of one coast or another.
        listOf("Hardcore Hip Hop", "Gangsta Rap", "East Coast Hip Hop", "West Coast Hip Hop", "G-Funk", "Southern Hip Hop",
            "Crunk", "Drill", "Grime"),
        // Melodic, sung half the time, on trap drums.
        listOf("Pop Rap", "Cloud Rap", "Emo Rap", "Trap Soul", "Plugg", "Rage", "Phonk", "Southern Hip Hop", "Trap Latino"),
        listOf("Experimental Hip Hop", "Industrial Hip Hop", "Alternative Hip Hop", "Cloud Rap"),
        listOf("Miami Bass", "Crunk", "Jersey Club"),

        // ── R&B, soul and funk ──
        // R&B now.
        listOf("Contemporary R&B", "Alternative R&B", "Trap Soul", "Neo-Soul", "New Jack Swing", "Quiet Storm", "Pop Soul"),
        // Smooth, late at night.
        listOf("Neo-Soul", "Smooth Soul", "Quiet Storm", "Philly Soul", "Pop Soul", "Blue-Eyed Soul", "Soul Jazz", "Acid Jazz",
            "Alternative R&B", "Singer-Songwriter", "Contemporary R&B"),
        // The soul of the sixties and seventies.
        listOf("Motown", "Northern Soul", "Southern Soul", "Deep Soul", "Philly Soul", "Rhythm and Blues", "Doo-Wop",
            "Blue-Eyed Soul", "Psychedelic Soul", "Gospel Soul", "Pop Soul", "Contemporary R&B"),
        // Funk, and what it became in the eighties.
        listOf("P-Funk", "Boogie", "Synth Funk", "Minneapolis Sound", "Go-Go", "Jazz-Funk", "Psychedelic Soul", "Funk Rock",
            "Electro-Disco", "Post-Disco", "New Jack Swing", "Contemporary R&B"),

        // ── Disco and the club ──
        listOf("Euro-Disco", "Italo-Disco", "Hi-NRG", "Electro-Disco", "Nu Disco", "Post-Disco", "Boogie", "French House",
            "Eurodance"),
        listOf("Big Room", "Future Rave", "Electro House", "Progressive House", "Trance"),
        listOf("Electroclash", "Electro", "Electropunk", "Synth-Pop", "Electropop"),
        listOf("Deconstructed Club", "Jersey Club", "Hyperpop", "Bubblegum Bass", "Glitch"),

        // ── Jazz and blues ──
        // Sung, swung and easy.
        listOf("Vocal Jazz", "Swing", "Big Band", "Jazz Pop", "Smooth Jazz", "Traditional Pop", "Easy Listening", "Cool Jazz",
            "Bossa Nova"),
        listOf("Bebop", "Hard Bop", "Cool Jazz", "Modal Jazz", "Soul Jazz", "Latin Jazz"),
        listOf("Free Jazz", "Avant-Garde Jazz", "Free Improvisation", "Modal Jazz"),
        listOf("Jazz Fusion", "Jazz-Funk", "Acid Jazz", "Soul Jazz", "Smooth Jazz", "Ethio-Jazz"),
        listOf("Swing", "Big Band", "Gypsy Jazz", "Ragtime", "Jump Blues"),
        listOf("Chicago Blues", "Electric Blues", "Texas Blues", "British Blues", "Blues Rock", "Jump Blues"),
        listOf("Delta Blues", "Country Blues", "Piedmont Blues", "Gospel Blues", "Desert Blues"),
        listOf("Punk Blues", "Garage Rock", "Garage Rock Revival", "Blues Rock"),

        // ── Country and folk ──
        listOf("Country Pop", "Bro-Country", "Country Rock", "Americana", "Folk Pop"),
        listOf("Honky Tonk", "Bakersfield Sound", "Outlaw Country", "Western Swing", "Bluegrass", "Americana",
            "Alternative Country", "Gothic Country", "Southern Rock"),
        listOf("Singer-Songwriter", "Contemporary Folk", "Folk Pop", "Indie Folk", "Alternative Folk", "Folk Rock", "Americana",
            "Acoustic Rock", "Anti-Folk", "Freak Folk", "Chamber Pop"),
        listOf("Traditional Folk", "Celtic Folk", "Celtic", "Folk Revival", "Neo Folk", "Bluegrass"),

        // ── Reggae, Latin and the world ──
        listOf("Roots Reggae", "Rocksteady", "Lovers Rock", "Dub", "Nyabinghi", "Ska", "Two-Tone", "Ska Punk"),
        listOf("Dancehall", "Ragga", "Reggae Fusion", "Reggaeton", "Afrobeats"),
        listOf("Salsa", "Merengue", "Bachata", "Cumbia", "Son Cubano"),
        listOf("Bossa Nova", "Samba", "MPB", "Tango", "Latin Jazz"),
        listOf("Reggaeton", "Trap Latino", "Latin Pop", "Dancehall"),
        listOf("Mariachi", "Banda", "Norteño", "Vallenato", "Cumbia"),
        listOf("Latin Rock", "Latin Pop"),
        listOf("Afrobeat", "Highlife", "Soukous", "Afrobeats", "Ethio-Jazz", "Desert Blues"),
        listOf("Flamenco", "Fado", "Tango"),
        listOf("Klezmer", "Balkan Brass"),
        listOf("Bhangra", "Filmi"),

        // ── Sacred ──
        listOf("Gospel", "Traditional Gospel", "Contemporary Gospel", "Southern Gospel", "Gospel Soul", "Gospel Blues",
            "Spirituals", "Hymns", "Worship", "Contemporary Christian"),
        listOf("Gregorian Chant", "Sacred Choral", "Hymns", "Medieval", "Early Music"),
        listOf("Christian Rock", "Christian Hip-Hop", "Contemporary Christian", "Worship"),

        // ── The screen and the stage ──
        listOf("Film Score", "Orchestral Score", "Television Score", "Trailer Music", "Video Game Score", "Anime Soundtrack",
            "Synth Score"),
        listOf("Broadway", "Film Musical", "Show Tunes", "West End", "Original Cast Recording"),
        listOf("Synth Score", "Synthwave", "Chiptune"),

        // ── Out at the edges ──
        listOf("Electroacoustic", "Musique Concrete", "Sound Collage", "Spectral Music", "Fluxus", "Field Recording", "Drone"),
        listOf("Harsh Noise", "Japanoise", "Noisecore", "Power Electronics", "Noise Rock"),
        listOf("Audiobook", "Podcast", "Interview", "Storytelling", "Poetry", "Comedy"),

        // The classical periods, each with its neighbours.
        listOf("Medieval", "Renaissance", "Early Music", "Baroque"),
        listOf("Baroque", "Classical Period"),
        listOf("Classical Period", "Romantic Classical"),
        listOf("Romantic Classical", "Impressionist"),
        listOf("Impressionist", "Contemporary Classical", "Modern Classical", "Minimalism", "Post-Minimalism",
            "Ambient Classical"),
    )

    private val familiesOf: Map<String, Set<Int>> = buildMap<String, MutableSet<Int>> {
        STYLE_FAMILIES.forEachIndexed { at, family -> for (style in family) getOrPut(style.lowercase()) { HashSet() }.add(at) }
    }

    /** A classical record's year is when it was recorded, not when the music is from, so two of them are not
     * compared by era. */
    private val CLASSICAL = setOf("classical", "neo-classical", "opera")

    /**
     * The next songs, in the order they should be queued.
     *
     * [seed] is the song playing, [origin] the one the radio started from, [inQueue] every song queued now,
     * [history] every song this radio has queued, oldest first, the origin included. Empty when neither the seed nor
     * the origin is in the crate.
     */
    fun pick(
        crate: RadioCrate,
        seed: String,
        origin: String,
        inQueue: Set<String>,
        history: List<String>,
        count: Int,
        random: Random,
    ): List<String> {
        val playing = crate.find(seed) ?: crate.find(origin) ?: return emptyList()
        if (count <= 0) return emptyList()
        val start = crate.find(origin) ?: playing

        var ranked = ranked(crate, playing, start, blocked(crate, inQueue, history, seed, origin))
        if (ranked.size < count && history.size > 1) {
            // Nearly everything has been queued: the older half of the history may come back.
            ranked = ranked(crate, playing, start, blocked(crate, inQueue, history.drop(history.size / 2), seed, origin))
        }
        ranked = withoutClashes(ranked, playing, count)

        val recent = history.takeLast(RECORD_GAP).mapNotNullTo(ArrayList()) { crate.find(it) }
        recent.add(playing)

        val picked = ArrayList<String>(count)
        val takenIds = HashSet<String>()
        val takenSongs = HashSet<String>()
        val shortlist = ArrayList<RadioSong>(SHORTLIST)
        val fitDepth = min(ranked.size, max(SHORTLIST, ceil(ranked.size * FAVOURITE_FIT).toInt()))

        fun free(song: RadioSong) = song.id !in takenIds && song.song !in takenSongs

        while (picked.size < count) {
            shortlist.clear()
            val slot = history.size + picked.size
            if (slot > 0 && slot % FAVOURITE_EVERY == 0) {
                for (i in 0 until fitDepth) {
                    val song = ranked[i]
                    if (crate.isFavourite(song) && free(song) && spaced(song, recent)) shortlist.add(song)
                    if (shortlist.size == SHORTLIST) break
                }
            }
            if (shortlist.isEmpty()) fill(ranked, shortlist) { free(it) && spaced(it, recent) }
            // Nothing passes the spacing rules: spacing gives way rather than the radio going quiet.
            if (shortlist.isEmpty()) fill(ranked, shortlist) { free(it) }
            if (shortlist.isEmpty()) break

            val pick = shortlist[random.nextInt(shortlist.size)]
            picked.add(pick.id)
            takenIds.add(pick.id)
            takenSongs.add(pick.song)
            recent.add(pick)
        }
        return picked
    }

    private inline fun fill(ranked: List<RadioSong>, into: MutableList<RadioSong>, ok: (RadioSong) -> Boolean) {
        for (song in ranked) {
            if (!ok(song)) continue
            into.add(song)
            if (into.size == SHORTLIST) return
        }
    }

    /** How far apart two songs are in mood, era and genre, from 0 to 1. */
    internal fun apart(song: RadioSong, to: RadioSong, related: Set<String> = relatedTo(to.genre)): Double =
        (moodApart(song, to) + eraApart(song, to) + genreApart(song, to, related)) / 3

    /** What a pick is ranked by: the distance to the song playing plus half the distance to the origin, and
     * [LIVE_APART] on a live recording when the radio did not start from one. */
    internal fun score(song: RadioSong, playing: RadioSong, origin: RadioSong): Double =
        Scorer(playing, origin).score(song)

    /** [score] with the related shelves worked out once per pick rather than once per song. */
    private class Scorer(val playing: RadioSong, val origin: RadioSong) {
        private val playingRelated = relatedTo(playing.genre)
        private val originRelated = relatedTo(origin.genre)
        fun score(song: RadioSong): Double =
            apart(song, playing, playingRelated) + ORIGIN_WEIGHT * apart(song, origin, originRelated) +
                if (song.live && !origin.live) LIVE_APART else 0.0
    }

    /** The shelves next to this one, lower cased. */
    private fun relatedTo(genre: String?): Set<String> =
        if (genre == null) emptySet() else Genres.related(genre).mapTo(HashSet()) { it.lowercase() }

    internal fun moodApart(a: RadioSong, b: RadioSong): Double = when {
        a.moods.isEmpty() && b.moods.isEmpty() -> UNKNOWN
        a.moods.isEmpty() || b.moods.isEmpty() -> ONE_IN_A_MOOD
        a.moods.any { it in b.moods } -> 0.0
        else -> 1.0
    }

    internal fun eraApart(a: RadioSong, b: RadioSong): Double {
        if (a.genre?.lowercase() in CLASSICAL && b.genre?.lowercase() in CLASSICAL) return 0.0
        return if (a.year > 0 && b.year > 0) min(abs(a.year - b.year) / ERA_SPAN, 1.0) else UNKNOWN
    }

    /** The shelves first, then the styles within them. See the desktop's `SongRadio.GenreApart`. */
    internal fun genreApart(song: RadioSong, to: RadioSong, related: Set<String> = relatedTo(to.genre)): Double {
        val styles = styleApart(song.styles, to.styles)
        val genre = song.genre
        val other = to.genre
        if (genre == null || other == null) return if (styles != null && styles <= KIN_STYLE) styles else UNKNOWN
        if (genre.equals(other, ignoreCase = true)) {
            return styles ?: if (song.styles.isEmpty() && to.styles.isEmpty()) 0.0 else ONE_STYLE
        }
        val shelves = if (genre.lowercase() in related) RELATED_GENRE else 1.0
        return if (styles != null && styles <= KIN_STYLE) min(shelves, styles) else shelves
    }

    /** 0 for a style in common, [KIN_STYLE] for kin, [OTHER_STYLE] for neither, null when either has none. */
    internal fun styleApart(a: Set<String>, b: Set<String>): Double? {
        if (a.isEmpty() || b.isEmpty()) return null
        if (a.any { x -> b.any { it.equals(x, ignoreCase = true) } }) return 0.0
        for (x in a) {
            val ofX = familiesOf[x.lowercase()] ?: continue
            for (y in b) if (familiesOf[y.lowercase()]?.any { it in ofX } == true) return KIN_STYLE
        }
        return OTHER_STYLE
    }

    /**
     * [ranked] without the songs whose style clashes with the song playing, while enough are left: a ballad does not
     * follow dance-pop because it is from the same year. Songs that share a style or a kin one stay, and so do songs
     * with no style. A song playing with no style leaves the ranking as it is. The desktop's `WithoutClashes`.
     */
    internal fun withoutClashes(ranked: List<RadioSong>, playing: RadioSong, count: Int): List<RadioSong> {
        if (playing.styles.isEmpty()) return ranked
        val kept = ranked.filter { styleApart(it.styles, playing.styles) != OTHER_STYLE }
        return if (kept.size >= count + CLASH_SPARE) kept else ranked
    }

    /**
     * Whether a song is a live recording: its record is filed as a live one, or its title or its album says so. A
     * title says so as "(Live)", "[Live at Wembley]" or " - Unplugged"; an album the same ways, or by starting with
     * the word ("Live", "Live at the BBC") or holding "Unplugged". "Live Forever" is not live, and a "(Live Mix)" is a
     * mix. The desktop's `SongRadio.IsLive`; change both together.
     */
    fun isLive(title: String?, album: String?, releaseType: String?): Boolean {
        if (ReleaseType.fromStored(releaseType) == ReleaseType.Live) return true
        if (saysLive(title)) return true
        return !album.isNullOrBlank() && (saysLive(album) || LIVE_ALBUM.containsMatchIn(album))
    }

    private fun saysLive(text: String?): Boolean =
        !text.isNullOrBlank() && LIVE_NOTE.containsMatchIn(text) && !LIVE_MIX.containsMatchIn(text)

    private val LIVE_NOTE = Regex(
        """[(\[][^)\]]*\b(?:live|unplugged)\b[^)\]]*[)\]]|\s[-\u2013\u2014]\s*(?:live|unplugged)\b""",
        RegexOption.IGNORE_CASE)
    private val LIVE_MIX = Regex("""\blive\s+(?:mix|edit|dub)\b""", RegexOption.IGNORE_CASE)
    private val LIVE_ALBUM = Regex(
        """^\s*live\s*(?:$|[:\-\u2013\u2014(\[]|(?:at|in|from|on|aus|\u00e0|en)\b)|\bunplugged\b""",
        RegexOption.IGNORE_CASE)

    /** Whether the act and the record have been away long enough. An empty credit names nobody. */
    private fun spaced(song: RadioSong, recent: List<RadioSong>): Boolean {
        var i = recent.size - 1
        while (i >= 0 && i >= recent.size - RECORD_GAP) {
            val other = recent[i]
            if (other.record == song.record) return false
            if (i >= recent.size - ARTIST_GAP && song.artist.isNotEmpty() && other.artist.equals(song.artist, ignoreCase = true)) return false
            i--
        }
        return true
    }

    private class Blocked(val ids: Set<String>, val songs: Set<String>)

    private fun blocked(crate: RadioCrate, inQueue: Set<String>, history: List<String>, seed: String, origin: String): Blocked {
        val ids = HashSet(inQueue).apply { addAll(history); add(seed); add(origin) }
        return Blocked(ids, ids.mapNotNullTo(HashSet()) { crate.find(it)?.song })
    }

    /** Every song that may be picked, closest first, the id breaking a tie so one crate always ranks the same way. */
    private fun ranked(crate: RadioCrate, playing: RadioSong, origin: RadioSong, blocked: Blocked): List<RadioSong> {
        val scorer = Scorer(playing, origin)
        return crate.songs.asSequence()
            .filter { it.offered && it.id !in blocked.ids && it.song !in blocked.songs }
            .map { it to scorer.score(it) }
            .sortedWith(compareBy<Pair<RadioSong, Double>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .toList()
    }
}
