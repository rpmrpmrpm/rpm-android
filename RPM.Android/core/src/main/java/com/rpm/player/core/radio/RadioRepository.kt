package com.rpm.player.core.radio

import com.rpm.player.core.net.AppKey
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The essential stations as last read: the stations, which country they are for (null when that
 * country had none and the world's most popular stations stand in), and when they were read. */
sealed interface Essential {
    data object NotRead : Essential
    data class Read(val stations: List<RadioStation>, val countryCode: String?, val atMs: Long) : Essential
    /** Every mirror failed; the next visit asks again. */
    data object Unavailable : Essential
}

/**
 * What the rpm API says about stations: the ones an admin hid, the stamp of the logo set, and the
 * stations shown with a different title, tags or description ([edits], by lower-case uuid).
 */
data class StationRules(val hidden: Set<String>, val logoStamp: String, val edits: Map<String, StationEdit>, val cards: StationCards) {
    /** Hidden only when the station and every entry folded into it are hidden, as on the desktop. */
    fun hides(station: RadioStation): Boolean =
        station.uuid.lowercase() in hidden && station.alsoFiledAs.all { it.lowercase() in hidden }

    /** The admin's edit for [station]: its own entry's, else the first folded entry's that has one (the
     * desktop's `StationEdits.Set.Of`). The admin saves an edit under every entry of a station, so the
     * folded entries are the fallback when the admin's index grouped the entries differently. */
    fun editOf(station: RadioStation): StationEdit? {
        if (edits.isEmpty()) return null
        edits[station.uuid.lowercase()]?.let { return it }
        return station.alsoFiledAs.firstNotNullOfOrNull { edits[it.lowercase()] }
    }

    /** [station] with its edit taken from the current rules, replacing any edit it already carries. */
    fun edited(station: RadioStation): RadioStation {
        val edit = editOf(station)
        return if (edit == station.edit) station else station.copy(edit = edit)
    }

    /** [stations] without the hidden ones, carrying their edits, with the full name given where two
     * would print the same. Apply this when a row is drawn, not when an answer is stored: the rules can
     * change after the answer is stored. */
    fun shown(stations: List<RadioStation>): List<RadioStation> =
        RadioNames.distinguish(stations.filterNot(::hides).map(::edited))

    /** The uuids of the stations the admin filed under [genre] by their tags, in a stable order. A genre
     * search adds them to the directory's answer, because the directory is asked by its own tags and
     * never returns them ([RadioResults.withRetagged]). */
    fun retaggedInto(genre: String): List<String> =
        edits.filter { (_, edit) -> edit.tags?.let { RadioGenres.isGenre(RadioNames.genres(it), genre) } == true }
            .keys.sorted()

    companion object {
        val NONE = StationRules(emptySet(), "", emptyMap(), StationCards.NONE)
    }
}

/**
 * Radio's reads: the essential stations for the TV's country from Radio Browser, the rules from the
 * rpm API's `radio/state`, and the favourites. Each is kept here so the radio screen can be left and
 * opened again without asking again: the essential stations for 30 minutes, the rules for an hour.
 */
class RadioRepository(
    val browser: RadioBrowser,
    val favorites: RadioFavorites,
    private val favoritesSync: FavoritesSync,
    /** The saved stations the radio shows: the person's own, or a shared collection's owner's. */
    private val shared: SharedStations,
    val listens: StationListens,
    val listensSync: StationListensSync,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    tunerOpensOn: TunerOpensOn,
    /** The country the person chose on this device ([com.rpm.player.core.Region]), or null for the guess. */
    private val chosenCountry: () -> String?,
) {
    private val _essential = MutableStateFlow<Essential>(Essential.NotRead)
    val essential: StateFlow<Essential> = _essential.asStateFlow()

    private val _rules = MutableStateFlow(StationRules.NONE)
    val rules: StateFlow<StationRules> = _rules.asStateFlow()

    /** The saved stations the radio shows, and whether they can be changed here ([SharedStations]). */
    val saved: StateFlow<SavedStations> get() = shared.saved

    /** The tuner page's questions and answers, kept for the life of the app so the page opens as it was left. */
    val tuner = Tuner(browser, scope, if (tunerOpensOn == TunerOpensOn.ListenersCountry) listenerCountry() else null, rules)

    private val _popular = MutableStateFlow<List<RadioStation>>(emptyList())

    /** The world's most played stations, one entry per station, before the rules are applied. Empty until read. */
    val popular: StateFlow<List<RadioStation>> = _popular.asStateFlow()

    private val reading = Mutex()
    private val readingPopular = Mutex()
    private var popularAtMs = 0L
    private var rulesAskedAtMs = 0L
    private var rulesTag: String? = null

    /** The directory's answers for one genre, by genre, with when they were read ([stationsOf]). */
    private val genreAnswers = ConcurrentHashMap<String, Pair<Long, List<RadioStation>>>()

    /** Reads what is stale: the essential stations after 30 minutes or a failure, the rules after an
     * hour. Also syncs the station listens and the saved stations. Called when the radio screen opens.
     * Two calls at once read once. */
    fun refresh() {
        listensSync.pass()
        favoritesSync.pass()
        shared.read()
        scope.launch(Dispatchers.IO) {
            reading.withLock {
                val now = clock()
                readRulesIfStale(now)
                val essential = _essential.value
                if (essential !is Essential.Read || now - essential.atMs >= ESSENTIAL_GOOD_FOR_MS) readEssential(now)
            }
        }
        // Read beside the essential stations, not after them, so the local stations are not kept waiting.
        scope.launch(Dispatchers.IO) {
            readingPopular.withLock {
                val now = clock()
                if (_popular.value.isEmpty() || now - popularAtMs >= ESSENTIAL_GOOD_FOR_MS) readPopular(now)
            }
        }
    }

    /** The world's most played stations. A failure keeps what is held, and the next visit asks again. */
    private suspend fun readPopular(now: Long) {
        try {
            val found = browser.search(RadioQuery(name = null, tag = null, countryCode = null, limit = POPULAR_COUNT * 4))
            _popular.value = RadioResults.onePerStation(found)
            popularAtMs = now
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * The world's most played stations of [genre] (a name from [RadioGenres.all]), one entry per station, before the rules
     * are applied. Kept for 30 minutes. Empty for a name that is not a genre, or when the directory does not answer.
     */
    suspend fun stationsOf(genre: String): List<RadioStation> {
        val term = RadioGenres.term(genre) ?: return emptyList()
        val now = clock()
        genreAnswers[genre]?.let { (at, stations) -> if (now - at < ESSENTIAL_GOOD_FOR_MS) return stations }
        return try {
            val found = withContext(Dispatchers.IO) {
                RadioResults.onePerStation(browser.search(RadioQuery(name = null, tag = term, countryCode = null, limit = GENRE_COUNT)))
            }
            genreAnswers[genre] = now to found
            found
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Reads the rules alone, if they are an hour old. Called when the app's screens first compose, so
     * home's stations are drawn with the hidden ones left out and the admin's edits applied, even before
     * the radio screen has been opened. */
    fun refreshRules() {
        scope.launch(Dispatchers.IO) {
            reading.withLock { readRulesIfStale(clock()) }
        }
    }

    private suspend fun readRulesIfStale(now: Long) {
        if (now - rulesAskedAtMs < RULES_GOOD_FOR_MS) return
        rulesAskedAtMs = now
        readRules()
    }

    private suspend fun readEssential(now: Long) {
        val code = listenerCountry()
        _essential.value = try {
            var found = browser.search(RadioQuery(name = null, tag = null, countryCode = code, limit = ESSENTIAL_COUNT * 4))
            var forCountry = code
            if (found.isEmpty() && code != null) {
                found = browser.search(RadioQuery(name = null, tag = null, countryCode = null, limit = ESSENTIAL_COUNT * 4))
                forCountry = null
            }
            Essential.Read(RadioResults.onePerStation(found), forCountry, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Essential.Unavailable
        }
    }

    /** `radio/state` with the app key alone. A failure keeps the rules already held and still counts as
     * asked, so a dead route is asked once an hour, not on every visit. */
    private suspend fun readRules() {
        try {
            val request = Request.Builder()
                .url(Hosts.API + "radio/state")
                .header(AppKey.HEADER, AppKey.VALUE)
                .apply { rulesTag?.let { header("If-None-Match", it) } }
                .build()
            http.newCall(request).await().use { response ->
                if (response.code == 304 || !response.isSuccessful) return
                val wire = WireJson.decodeFromString(StateWire.serializer(), response.body.string())
                rulesTag = response.header("ETag")
                _rules.value = StationRules(
                    hidden = wire.hidden.map { it.lowercase() }.toSet(),
                    logoStamp = wire.logos,
                    edits = wire.edits.mapNotNull { (uuid, e) ->
                        StationEdit.of(e.name, e.tags, e.description)?.let { uuid.trim().lowercase() to it }
                    }.toMap(),
                    cards = StationCards(wire.cards.map { it.trim().lowercase() }.toSet(), wire.cardStamp),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * The country the TV is in, as two letters: the region of its time zone, else the country of its
     * language. The time zone comes first because a TV box's language is often English (United States)
     * wherever it is, while its time zone is set from the network. The desktop reads the Windows region
     * setting, which Android TV does not have. Null when neither names a country.
     */
    fun listenerCountry(): String? {
        chosenCountry()?.let { return it }
        val zone = try {
            android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id)
        } catch (_: Exception) {
            null
        }
        return listOfNotNull(zone, Locale.getDefault().country)
            .map { it.uppercase() }
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) && it != "ZZ" && it != "IV" }
    }

    @Serializable
    private data class StateWire(
        val logos: String = "",
        val hidden: List<String> = emptyList(),
        val edits: Map<String, EditWire> = emptyMap(),
        val cards: List<String> = emptyList(),
        val cardStamp: String = "",
    )

    /** One station's edit as the API sends it; null values are the directory's own. */
    @Serializable
    private data class EditWire(val name: String? = null, val tags: List<String>? = null, val description: String? = null)

    companion object {
        /** The desktop's `RadioEssentialCount`: twelve cards, asked for four times over so folding and
         * hiding still leave twelve. */
        const val ESSENTIAL_COUNT = 12

        /** The phone's radio home shows twenty of the world's most played stations; asked for four times over, as above. */
        const val POPULAR_COUNT = 20

        /** How many stations a genre's suggestions are chosen from. */
        const val GENRE_COUNT = 40
        const val ESSENTIAL_GOOD_FOR_MS = 30 * 60_000L
        const val RULES_GOOD_FOR_MS = 60 * 60_000L
    }
}
