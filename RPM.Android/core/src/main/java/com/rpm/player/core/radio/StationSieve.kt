package com.rpm.player.core.radio

import java.net.URI
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Which stations the advertising filter keeps. */
enum class AdsWanted { Either, Without, With }

/**
 * The tuner's filters (RPM.Core/Radio/StationSieve.cs): a format, a genre and whether the station
 * carries advertising. Every one is applied on the TV, over what the directory listed.
 */
data class StationSieve(val format: String?, val genre: String?, val ads: AdsWanted) {
    val isOn: Boolean get() = format != null || genre != null || ads != AdsWanted.Either

    fun keeps(station: RadioStation): Boolean {
        if (format != null && !isFormat(station.codec, format)) return false
        // The admin's tags where they set some. The ads read the admin's mark, else the rule on the directory's own
        // tags (RadioStation.carriesAds).
        if (genre != null && !RadioGenres.isGenre(RadioNames.genres(station.shownTags), genre)) return false
        return when (ads) {
            AdsWanted.With -> station.carriesAds
            AdsWanted.Without -> !station.carriesAds
            AdsWanted.Either -> true
        }
    }

    fun through(stations: List<RadioStation>): List<RadioStation> = if (isOn) stations.filter(::keeps) else stations

    companion object {
        val OFF = StationSieve(format = null, genre = null, ads = AdsWanted.Either)
        val FORMATS = listOf("MP3", "AAC", "AAC+", "OGG")

        private fun isFormat(codec: String?, wanted: String): Boolean {
            if (codec.isNullOrBlank()) return false
            return codec.split(',', '/', ';', ' ').filter { it.isNotEmpty() }.any { foldCodec(it) == wanted }
        }

        private fun foldCodec(codec: String): String = when (val upper = codec.trim().uppercase(Locale.ROOT)) {
            "AACP", "AAC PLUS", "HE-AAC", "HEAAC" -> "AAC+"
            "VORBIS", "OGG VORBIS" -> "OGG"
            "MPEG", "MPEG3" -> "MP3"
            else -> upper
        }
    }
}

/** The orders the tuner's list can be in (RPM.Core/Radio/StationOrdering.cs). Every one ends in the
 * name, then the id. "Listed" is the directory's own order, most played first. */
enum class StationOrder {
    Listed, NameUp, NameDown, Votes, Quality;

    fun sort(stations: List<RadioStation>): List<RadioStation> {
        val names = Collator.getInstance().apply { strength = Collator.SECONDARY }
        val byName = Comparator<RadioStation> { a, b -> names.compare(a.display, b.display) }.thenBy { it.uuid }
        return when (this) {
            Listed -> stations
            NameUp -> stations.sortedWith(byName)
            NameDown -> stations.sortedWith(Comparator<RadioStation> { a, b -> names.compare(b.display, a.display) }.thenBy { it.uuid })
            Votes -> stations.sortedWith(compareByDescending<RadioStation> { it.votes }.then(byName))
            Quality -> stations.sortedWith(compareByDescending<RadioStation> { it.bitrateKbps }.then(byName))
        }
    }
}

/**
 * Whether a station carries advertising (RPM.Core/Radio/RadioAds.cs and RadioBroadcasters.cs). A tag
 * saying it is ad-free settles it. A tag saying it is commercial or public says it has advertising.
 * Otherwise a broadcaster known to carry advertising counts, found by its site or its name.
 */
object RadioAds {
    fun carriesAds(tags: List<String>, name: String?, homepage: String?): Boolean {
        var carries = false
        for (raw in tags) {
            val tag = fold(raw)
            if (tag.isEmpty()) continue
            if (AD_FREE.any { tag.contains(it) }) return false
            carries = carries || ADVERTISED.any { tag.contains(it) }
        }
        return carries || knownSite(homepage) || knownName(name)
    }

    private fun knownSite(homepage: String?): Boolean {
        val host = try {
            URI(homepage ?: return false).host?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        } ?: return false
        return SITES.any { host == it || host.endsWith(".$it") }
    }

    private fun knownName(name: String?): Boolean {
        val folded = plain(name.orEmpty())
        if (folded.isEmpty()) return false
        return NAMES.any { folded == it || folded.startsWith("$it ") }
    }

    /** Lower case, accents taken off, every run of punctuation or space one space. */
    private fun fold(text: String): String {
        val out = StringBuilder(text.length)
        val bare = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        for (c in bare) {
            if (c.isLetterOrDigit()) out.append(c) else if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
        }
        return out.toString().trimEnd()
    }

    private fun plain(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c.isLetterOrDigit()) out.append(c.lowercaseChar()) else if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
        }
        return out.toString().trimEnd()
    }

    private val ADVERTISED = listOf(
        "commercial", "public radio", "public service", "public broadcast", "offentlich rechtlich",
        "government", "state radio",
    )
    private val AD_FREE = listOf(
        "non commercial", "noncommercial", "no ads", "no advert", "ad free", "advert free",
        "werbefrei", "sans pub",
    )
    private val SITES = listOf(
        "npo.nl", "nporadio1.nl", "nporadio2.nl", "npo3fm.nl", "nporadio4.nl", "nporadio5.nl",
        "radio1.nl", "radio2.nl", "3fm.nl", "radio4.nl", "radio5.nl", "omroep.nl", "funx.nl",
        "538.nl", "radio538.nl", "skyradio.nl", "radio10.nl", "radioveronica.nl", "talpanetwork.com",
        "qmusic.nl", "joe.nl", "joe.be", "qmusic.be", "100p.nl", "slam.nl", "sublimefm.nl",
        "arrow.nl", "grootnieuwsradio.nl", "omroepgelderland.nl",
        "vrt.be", "rtbf.be", "radio1.be", "studiobrussel.be", "nostalgie.be",
        "ard.de", "wdr.de", "ndr.de", "swr.de", "br.de", "mdr.de", "rbb-online.de", "hr.de",
        "deutschlandfunk.de", "deutschlandradio.de", "1live.de", "antenne.de", "ffh.de", "radio.de",
        "radiofrance.fr", "franceinter.fr", "franceinfo.fr", "francemusique.fr", "fip.fr",
        "nrj.fr", "rtl.fr", "europe1.fr", "rmc.fr", "skyrock.com",
        "rai.it", "radiorai.it", "rtl.it", "radiodeejay.it", "radio105.net", "virginradio.it",
        "rtve.es", "cadenaser.com", "los40.com", "cope.es", "ondacero.es",
        "bbc.co.uk", "bbc.com", "global.com", "heart.co.uk", "capitalfm.com", "classicfm.com",
        "bauermedia.co.uk", "absoluteradio.co.uk", "talksport.com",
        "rte.ie", "todayfm.com",
        "sverigesradio.se", "nrk.no", "dr.dk", "yle.fi",
        "orf.at", "hitradio-oe3.at", "srf.ch", "rsi.ch", "rts.ch",
        "npr.org", "wnyc.org", "iheart.com", "iheartmedia.com", "audacy.com", "cumulusmedia.com",
        "cbc.ca", "radio-canada.ca",
    )
    private val NAMES = listOf(
        "npo", "npo radio", "npo 3fm", "3fm", "radio 538", "538", "sky radio", "radio 10",
        "radio veronica", "veronica", "qmusic", "q music", "joe", "100 nl", "slam", "sublime",
        "arrow classic rock", "funx", "bnr", "kink",
        "vrt", "studio brussel", "rtbf", "mnm",
        "ard", "wdr", "ndr", "swr", "br", "mdr", "hr", "1live", "deutschlandfunk", "antenne bayern",
        "radio france", "france inter", "france info", "france musique", "france culture", "nrj",
        "europe 1", "rtl", "rmc", "skyrock",
        "rai", "radio deejay", "radio 105", "virgin radio",
        "rne", "cadena ser", "los 40", "cope", "onda cero",
        "bbc", "heart", "capital", "classic fm", "smooth", "absolute radio", "talksport", "lbc",
        "rte", "today fm",
        "sveriges radio", "nrk", "dr p", "yle",
        "orf", "hitradio o3", "srf", "rsi", "rts",
        "npr", "wnyc", "cbc", "ici radio canada",
    )
}

/**
 * A station's call sign, three or four letters drawn from its name (RPM.Core/Radio/StationSign.cs).
 * Noise words (RADIO, FM, LIVE...) and joiners (OF, DE, VAN...) are left out while anything else
 * remains. Then the sign is a short first word with its number ("BBC1"), else the first word, else the
 * initials of the first four words, else the first four letters.
 */
object StationSign {
    private const val MAX = 4
    private val NOISE = setOf(
        "RADIO", "RADIOS", "RADIU", "RADION", "FM", "AM", "HD", "DAB", "STATION", "STATIONS",
        "ONLINE", "LIVE", "STREAM", "STREAMING", "NET", "WEB", "DIGITAL", "CHANNEL", "THE",
    )
    private val JOINERS = setOf(
        "OF", "AND", "THE", "A", "AN", "DE", "DA", "DI", "DU", "DEL", "LA", "LE", "LES", "EL",
        "IL", "DER", "DIE", "DAS", "VAN", "VON", "EN", "ET", "Y",
    )

    fun of(name: String?): String {
        val words = words(name)
        if (words.isEmpty()) return ""
        val carriers = words.filterNot { it in JOINERS }.ifEmpty { words }
        val lead = carriers[0]
        if (carriers.size <= 2 && lead.length in 2..MAX) {
            val number = carriers.drop(1).firstOrNull { it.first().isDigit() }
            val joined = if (number == null) lead else lead + number
            if (joined.length in 3..MAX) return joined
            if (lead.length >= 3) return lead
        }
        if (carriers.size > 2) return carriers.take(MAX).map { it[0] }.joinToString("")
        val sb = StringBuilder()
        for (word in carriers) {
            sb.append(word)
            if (sb.length >= MAX) break
        }
        return sb.substring(0, minOf(MAX, sb.length))
    }

    private fun words(name: String?): List<String> {
        val text = Normalizer.normalize(name.orEmpty().trim().uppercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                // A run of digits is a word of its own.
                if (word.isNotEmpty() && c.isDigit() != word.last().isDigit()) {
                    words += word.toString()
                    word.clear()
                }
                word.append(c)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words.filterNot { it in NOISE }.ifEmpty { words }
    }
}
