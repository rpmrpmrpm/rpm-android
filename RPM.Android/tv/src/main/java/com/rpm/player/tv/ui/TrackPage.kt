package com.rpm.player.tv.ui

import com.rpm.player.core.library.listName
import com.rpm.player.core.playback.Queued
import com.rpm.player.core.playback.queueOf
import com.rpm.player.core.playback.playNext
import com.rpm.player.core.playback.addToQueue
import com.rpm.player.core.library.ListName
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.papers.Dossiers
import com.rpm.player.core.papers.AlbumSubject
import com.rpm.player.core.library.Genres
import com.rpm.player.core.library.AlbumFavourites
import com.rpm.player.core.library.AlbumRatings
import com.rpm.player.core.library.RecordFacts
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.AlbumLine
import com.rpm.player.core.library.AlbumLines
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.SongLevel
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.room.VinylHue

/** What a track page shows: an album, or a list. */
sealed interface PageOf {
    data class OfAlbum(val album: CloudAlbum) : PageOf
    data class OfList(val list: TrackList) : PageOf
}

/** What a track page asks the app to do. */
class TrackActions(
    val play: (queue: List<Queued>, start: Int) -> Unit,
    val playNext: (List<Queued>) -> Unit,
    val addToQueue: (List<Queued>) -> Unit,
    /** Puts the favourite mark on the track (true) or takes it off (false). Offered on the person's own
     * collection only. */
    val mark: (Queued, Boolean) -> Unit,
    /** Puts the track at the end of one of the person's own playlists. Offered on their own collection
     * only. */
    val fileIn: (Queued, TrackList) -> Unit,
    /** Makes a playlist with the name given, holding the track. Offered on their own collection only. */
    val fileNew: (Queued, String) -> Unit,
    /** Marks the record a favourite (true) or clears the mark (false). Offered on their own collection only. */
    val markAlbum: (CloudAlbum, Boolean) -> Unit,
    /** Gives the record a number of stars, 0 to clear them. Offered on their own collection only. */
    val rateAlbum: (CloudAlbum, Int) -> Unit,
    /** Start radio: the track plays now and the queue keeps filling with songs like whichever one is playing. */
    val startRadio: (Queued) -> Unit = {},
)

/**
 * The record page, and the list page, which is the same page: the desktop's `Record.cs` and
 * `RecordList.cs`. The sleeve, the names and the keys in a column on the left, the tracks on the right,
 * the two centred on the screen with a gutter between them, all sized by the desktop's SizeRecord.
 * The room behind is painted in the sleeve's colours.
 *
 * Under an album's keys, what the encyclopedias say about it ([AboutRecord]).
 *
 * An album's year leads the count and length, and its format and quality ("FLAC  24/96") are on the line under,
 * then its stars. Play starts at the first track; the menu key beside it opens the page's menu:
 * Shuffle, Add to queue and Play next, and on the person's own collection Favourite album and Rate
 * album, which asks for the stars in a small card. OK on a track plays from it; a held OK or the Menu key opens
 * its menu (Play, Play next, Add to Now playing). Left from any track returns to the Play key. An album's
 * page opens with the focus on its first track, a list's on its Play key. There is no back key on the page: the
 * remote's Back leaves it.
 *
 * A record its owner marked [classical] is listed as works, as on the desktop, the iPhone and the phone ([AlbumLines]):
 * a work of more than one track has a heading (its name, the composer, its length) over its movements, which are set
 * in and named by the movement alone; a piece that is a work on its own is set in the weight of a heading, with its
 * composer under it. The heading is no stop for the remote. The mark decides, never the tags.
 */
@Composable
fun TrackPage(page: PageOf, collection: Collection, images: ImageLoader, dossiers: Dossiers, favourites: AlbumFavourites, ratings: AlbumRatings, actions: TrackActions, classical: Boolean = false) {
    val queue: List<Queued> = remember(page, collection) {
        when (page) {
            is PageOf.OfAlbum -> queueOf(collection.shelf, page.album, collection.cover(page.album), collection.disc(page.album))
            is PageOf.OfList -> page.list.tracks.map { track ->
                val album = collection.albumOf[track.rpmId]
                Queued(collection.shelf, track, album?.album ?: track.album.orEmpty(), album?.artist.orEmpty(), album?.let(collection::cover), album?.let(collection::disc))
            }
        }
    }
    val picture: Any? = when (page) {
        is PageOf.OfAlbum -> collection.cover(page.album)
        is PageOf.OfList -> collection.cover(page.list)
    }
    // A list's record is drawn under its own picture and has no disc of its own.
    val disc = when (page) {
        is PageOf.OfAlbum -> collection.disc(page.album)
        is PageOf.OfList -> null
    }
    val reading = rememberCoverReading(picture, images)
    val playKey = remember { FocusRequester() }
    val menuKey = remember { FocusRequester() }
    val marks by favourites.marks.collectAsState()
    val rated by ratings.changed.collectAsState()
    val album = (page as? PageOf.OfAlbum)?.album
    val stars = remember(album, rated) { album?.let(ratings::of) ?: 0 }
    // The record's lines, by the index of the track each stands on: its song line, and the heading of a work that
    // opens there. The list keeps one item per track, so the focus and the rows' requesters are as on any record.
    val classicalLines = remember(album, classical) {
        val songs = HashMap<Int, AlbumLine.Song>()
        val works = HashMap<Int, AlbumLine.Work>()
        if (album != null && classical) {
            var work: AlbumLine.Work? = null
            for (line in AlbumLines.of(album.tracks, classical = true)) {
                when (line) {
                    is AlbumLine.Work -> work = line
                    is AlbumLine.Song -> {
                        songs[line.index] = line
                        work?.let { works[line.index] = it }
                        work = null
                    }
                    is AlbumLine.Disc -> Unit
                }
            }
        }
        songs to works
    }
    // One requester per track, so the move into the list can name a row.
    val rows = remember(queue) { List(queue.size) { FocusRequester() } }
    // The track that last had the focus, where Right from the keys goes back to. Track 1 until one has.
    var lastTrack by remember(page) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val menu = rememberRowMenu()
    // The disc slides out of the sleeve as the page opens: 420 ms, easing out (Record.cs).
    val out = remember(page) { Animatable(0f) }
    LaunchedEffect(page) { out.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = recordSizes(maxWidth, maxHeight)
        val box = maxHeight - TOP - BOTTOM

        Row(
            Modifier
                .offset(x = size.left, y = TOP)
                .height(box),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The page's whole height, its lines centred in it, so the encyclopedia's slot at the foot can be
            // given what is left and no more.
            Column(
                Modifier.width(size.column).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(size.edge)) {
                    RecordArt(
                        cover = picture,
                        disc = disc,
                        images = images,
                        vinyl = remember(reading) { VinylHue.matrix(reading?.vinyl) },
                        look = SleeveLook.Bare(6.s),
                        out = out.value,
                        // Still, as the desktop's record page is (PegasusChrome.Record): only a quarter of the
                        // disc shows past the sleeve and the label is under it, so a turn could not be seen,
                        // and it would redraw the screen sixty times a second while the page stands still (about a
                        // fifth of a core on a low-end TV box).
                        turning = false,
                        turnSeconds = RECORD_TURN_SECONDS,
                    )
                    // A list's sleeve is its framed picture (the desktop's ListCoverDoor).
                    if (page is PageOf.OfList) {
                        ListPicture(page.list, collection, images, drift = false, keepFrame = false, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.s)))
                    }
                }
                Text(
                    when (page) {
                        is PageOf.OfAlbum -> albumTitle(page.album)
                        is PageOf.OfList -> listName(page.list.name)
                    },
                    style = ui(24.ts, FontWeight.Bold),
                    color = Ink.Words,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 22.s),
                )
                val line = when (page) {
                    is PageOf.OfAlbum -> albumArtist(page.album)
                    is PageOf.OfList -> page.list.folder.joinToString(" / ").ifBlank { null }
                }
                if (line != null) {
                    Text(line, style = ui(PAGE_TEXT), color = Color(0xD9FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.s))
                }
                // The year, the count and the length as separate texts, so no language is forced into one word order.
                Row(Modifier.padding(top = 12.s), horizontalArrangement = Arrangement.spacedBy(6.s)) {
                    val year = remember(album) { album?.let { RecordFacts.year(it.tracks) } }
                    if (year != null) {
                        Text(year.toString(), style = ui(PAGE_TEXT), color = COUNT)
                        Text("·", style = ui(PAGE_TEXT), color = COUNT)
                    }
                    Text(stringResource(R.string.Rpm_Album_Tracks, queue.size.toString()), style = ui(PAGE_TEXT), color = COUNT)
                    Text("·", style = ui(PAGE_TEXT), color = COUNT)
                    Text(clockOf(queue.sumOf { (it.track.tags?.durationSeconds ?: 0.0) * 1000 }.toLong()), style = ui(PAGE_TEXT), color = COUNT)
                }
                if (album != null) {
                    val format = remember(album) { RecordFacts.format(album.tracks) }
                    if (format != null) {
                        Row(Modifier.padding(top = 4.s), horizontalArrangement = Arrangement.spacedBy(12.s)) {
                            Text(format.format, style = ui(PAGE_TEXT, FontWeight.SemiBold), color = COUNT)
                            format.quality?.let { Text(it, style = ui(PAGE_TEXT), color = COUNT) }
                        }
                    }
                    // The stars given on this TV (Rate album in the menu sets them).
                    Row(Modifier.padding(top = 14.s), horizontalArrangement = Arrangement.spacedBy(10.s)) {
                        repeat(AlbumRatings.MOST) { i ->
                            val lit = i < stars
                            Glyph(Fa.STAR, 22.ts, if (lit) Ink.Words else Color(0x44FFFFFF), regular = !lit)
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 20.s).fillMaxWidth().height(54.s),
                    horizontalArrangement = Arrangement.spacedBy(10.s),
                ) {
                    PageKey(
                        onClick = { actions.play(queue, 0) },
                        modifier = Modifier.focusRequester(playKey).weight(1f).fillMaxSize(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.s)) {
                            Glyph(Fa.PLAY, 17.ts, Ink.Words)
                            Text(
                                stringResource(if (page is PageOf.OfAlbum) R.string.AlbumGrid_PlayAll else R.string.Playlists_Play),
                                style = ui(PAGE_TEXT, FontWeight.SemiBold),
                                color = Ink.Words,
                            )
                        }
                    }
                    // Right from the last key enters the tracks on the one last focused there, track 1 the first
                    // time, as on the desktop. Named here rather than left to the remote's search, which takes the
                    // row level with the key: track 9 of 9 on Daniəl, and a row in the middle of a long list,
                    // which scrolls it. A focusRestorer on the list does not catch that
                    // entry. Worked out at the move, from the rows composed then; a row scrolled away is left to
                    // the search.
                    val intoTracks = Modifier.focusProperties {
                        right = if (list.layoutInfo.visibleItemsInfo.any { it.index == lastTrack }) rows[lastTrack] else FocusRequester.Default
                    }
                    val pageMenuTitle = when (page) {
                        is PageOf.OfAlbum -> albumTitle(page.album)
                        is PageOf.OfList -> listName(page.list.name)
                    }
                    // No glow: the key is small enough for [deskFocus]'s icon-key orb, which stood out hard beside the
                    // play key. It shows the focus as the play key does, by its grow and its full brightness.
                    androidx.compose.runtime.CompositionLocalProvider(LocalGlowStrength provides 0f) {
                        PageKey(
                            onClick = {
                                menu.open(
                                    title = pageMenuTitle,
                                    choices = pageChoices(page, queue, collection, marks.isFavouriteOf(album), stars, actions),
                                    returnTo = menuKey,
                                )
                            },
                            modifier = Modifier.then(intoTracks).focusRequester(menuKey).width(64.s).fillMaxSize(),
                        ) {
                            Glyph(Fa.BARS, 17.ts, Ink.Words)
                        }
                    }
                }
                if (page is PageOf.OfAlbum) {
                    // Given what room the column has left under the keys, and no more: the column is centred
                    // in the page's height and does not scroll.
                    AboutRecord(page.album, dossiers, Modifier.weight(1f, fill = false).fillMaxWidth())
                }
            }

            Spacer(Modifier.width(size.gutter))

            LazyColumn(
                state = list,
                // Entering the tracks from the left: see the menu key above.
                modifier = Modifier
                    .width(size.tracks)
                    .heightIn(max = box)
                    .scrollFades(list),
                contentPadding = PaddingValues(bottom = 20.px),
                verticalArrangement = Arrangement.spacedBy(4.s),
            ) {
                val discs = (page as? PageOf.OfAlbum)?.album?.tracks?.map { disc(it) }?.distinct()?.size ?: 1
                itemsIndexed(queue, key = { i, q -> "$i:${q.track.rpmId}" }) { i, q ->
                    // A disc's heading over its first track, where the album has more than one disc.
                    if (discs > 1 && (i == 0 || disc(queue[i - 1].track) != disc(q.track))) {
                        DiscHeading(disc(q.track))
                    }
                    classicalLines.second[i]?.let { WorkHeading(it) }
                    val line = classicalLines.first[i]
                    val self = rows[i]
                    TrackRow(
                        number = if (page is PageOf.OfAlbum) q.track.tags?.trackNumber?.takeIf { it > 0 } ?: (i + 1) else i + 1,
                        q = q,
                        inList = page is PageOf.OfList,
                        title = line?.title ?: q.track.title.orEmpty(),
                        subtitle = line?.subtitle,
                        level = line?.level ?: SongLevel.Plain,
                        onClick = { actions.play(queue, i) },
                        onMenu = {
                            menu.open(
                                title = q.track.title.orEmpty(),
                                choices = trackChoices(q, collection, actions, play = { actions.play(queue, i) }),
                                returnTo = self,
                                // Lined up with the title, past the row's padding and the number's column.
                                indent = TRACK_TITLE_INDENT,
                            )
                        },
                        modifier = Modifier.focusProperties { left = playKey }
                            .focusRequester(self)
                            .onFocusChanged { if (it.isFocused) lastTrack = i },
                    )
                }
            }
        }
    }

    RowMenu(menu)

    // A frame later: the list composes its rows when it is first measured, after this effect would start.
    LaunchedEffect(page) {
        withFrameNanos { }
        val first = if (page is PageOf.OfAlbum) rows.firstOrNull() else null
        val took = first != null && runCatching { first.requestFocus() }.getOrDefault(false)
        if (!took) runCatching { playKey.requestFocus() }
    }
}

/** The page's columns at a screen's size (Record.cs, SizeRecord), in fixed desktop pixels. The gutter
 * between the record's column and the tracks is 0.06 of the width, at most 120, where the desktop's is
 * 0.16, at most 300: at 1080 that is about 110 pixels instead of 290, and the page, still centred, draws
 * the two halves in together. */
private class RecordSizes(val left: Dp, val column: Dp, val gutter: Dp, val tracks: Dp, val edge: Dp)

private fun recordSizes(w: Dp, h: Dp): RecordSizes {
    val available = max(320.px, w - 80.px)
    var gutter = (available * 0.06f).coerceIn(40.px, 120.px)
    val half = (available - gutter) / 2
    val column = (half * 0.86f).coerceIn(300.px, 640.px)
    var tracks = max(260.px, half - 200.px)
    // Too wide: the gutter gives way first, down to 40, then the tracks, down to 200.
    val over = column + gutter + tracks - available
    if (over > 0.px) {
        val fromGutter = min(over, gutter - 40.px)
        gutter -= fromGutter
        tracks = max(200.px, tracks - (over - fromGutter))
    }
    val left = (w - column - gutter - tracks) / 2
    val edge = max(160.px, min(column, h * 0.36f))
    return RecordSizes(left, column, gutter, tracks, edge)
}

/** The size of the album page's smaller text: the artist, the count and length, "Your rating", the play
 * key, the genres and every part of a track row. 19·s, the size of a song's title on home, where the
 * desktop has 11 to 15, because it is read from a couch. */
internal val PAGE_TEXT = 19.ts

/** Whether [album] is a favourite; false for a list. */
private fun AlbumFavourites.Marks.isFavouriteOf(album: CloudAlbum?): Boolean = album != null && isFavourite(album)

/**
 * The page's own menu, from the key beside Play: Shuffle album, Add to queue and Play next for
 * every page, and for a record on the person's own collection Favourite album (or Remove from favourites) and Rate
 * album, which asks for the stars with [stars] already set. A borrowed collection is not written to from here, as
 * in a track's menu.
 *
 * Each entry has the glyph of the desktop's record menu (AlbumMenuFactory): the crossed arrows, the step forward,
 * and the heart, outlined until the record is a favourite. Adding to the queue has the list of music, as Add to
 * Now playing has in the desktop's song menu (TrackMenuFactory). The desktop has no stars entry on a record. Rate
 * has the star that its card is made of.
 */
private fun pageChoices(page: PageOf, queue: List<Queued>, collection: Collection, favourite: Boolean, stars: Int, actions: TrackActions): List<MenuLine> = buildList {
    add(MenuChoice(if (page is PageOf.OfAlbum) R.string.AlbumGrid_Shuffle else R.string.AlbumMenu_Shuffle, Fa.SHUFFLE) { actions.play(queue.shuffled(), 0) })
    add(MenuChoice(R.string.Tv_Album_AddToQueue, Fa.PLAYLISTS) { actions.addToQueue(queue) })
    add(MenuChoice(R.string.AlbumMenu_PlayNext, Fa.STEP_FORWARD) { actions.playNext(queue) })
    if (page !is PageOf.OfAlbum || collection.shelf != Shelf.Own) return@buildList
    add(MenuChoice(if (favourite) R.string.Album_UnsetFavorite else R.string.AlbumMenu_Favorite, Fa.HEART, outline = !favourite) { actions.markAlbum(page.album, !favourite) })
    add(MenuStars(R.string.Tv_Album_Rate, stars, cancel = R.string.Common_Cancel, confirm = R.string.Tv_Album_RateConfirm, glyph = Fa.STAR) { picked -> actions.rateAlbum(page.album, picked) })
}

/**
 * A record's menu away from its page (Down on the album carousel): Play first, since the page's own Play key is not
 * there, then the page's menu ([pageChoices]).
 */
internal fun albumChoices(album: CloudAlbum, collection: Collection, favourites: AlbumFavourites, ratings: AlbumRatings, actions: TrackActions): List<MenuLine> {
    val queue = queueOf(collection.shelf, album, collection.cover(album), collection.disc(album))
    return listOf<MenuLine>(MenuChoice(R.string.AlbumMenu_Play, Fa.PLAY) { actions.play(queue, 0) }) +
        pageChoices(PageOf.OfAlbum(album), queue, collection, favourites.marks.value.isFavouriteOf(album), ratings.of(album), actions)
}

/**
 * A track's menu: Play, Play next and Add to Now playing, then, on the person's own collection, Add to
 * favourites or Remove from favourites, and Add to playlist, which lists their playlists in the menu's place,
 * ticked where the track is already in one. A borrowed collection
 * is not written to from here, so those two are left off rather than shown and refused, as on the phone.
 * The favourite entry says which way pressing it changes things, as the phone's and the desktop's do.
 *
 * The glyphs are the desktop's song menu's (TrackMenuFactory): play, the step forward, the list of music for
 * Now playing and for the playlists alike, the radio, the heart (outlined while it is not a favourite), and the
 * plus for a new playlist.
 */
internal fun trackChoices(q: Queued, collection: Collection, actions: TrackActions, play: () -> Unit): List<MenuLine> = buildList {
    add(MenuChoice(R.string.AlbumMenu_Play, Fa.PLAY, act = play))
    add(MenuChoice(R.string.AlbumMenu_PlayNext, Fa.STEP_FORWARD) { actions.playNext(listOf(q)) })
    add(MenuChoice(R.string.Playlists_AddToNowPlaying, Fa.PLAYLISTS) { actions.addToQueue(listOf(q)) })
    add(MenuChoice(R.string.Track_StartMixtape, Fa.MIXTAPE) { actions.startRadio(q) })
    if (collection.shelf != Shelf.Own) return@buildList
    if (q.track.tags?.favorite == true) {
        add(MenuChoice(R.string.Track_UnsetFavorite, Fa.HEART) { actions.mark(q, false) })
    } else {
        add(MenuChoice(R.string.Track_SetFavorite, Fa.HEART, outline = true) { actions.mark(q, true) })
    }
    // New playlist first, then their playlists, so a person with none yet can still make one.
    val playlists = collection.lists.filter { !it.generated }
    add(
        MenuSub(
            R.string.Playlists_AddToPlaylist,
            buildList {
                add(MenuName(R.string.Playlists_NewPlaylist, hint = R.string.Rpm_NewPlaylist_Name, confirm = R.string.Rpm_NewPlaylist_Create, glyph = Fa.PLUS) { name -> actions.fileNew(q, name) })
                if (playlists.isNotEmpty()) add(MenuGap)
                playlists.forEach { list ->
                    val name = (list.name as? ListName.Text)?.text.orEmpty()
                    add(MenuTick(name, ticked = list.tracks.any { it.rpmId == q.track.rpmId }) { actions.fileIn(q, list) })
                }
            },
            glyph = Fa.PLAYLISTS,
        ),
    )
}

private val TOP = 108.px
private val BOTTOM = 44.px
private val COUNT = Color(0xB0FFFFFF)
private val KEY_FILL = Color(0x2BFFFFFF)
/** One turn of the label on the record page. */
private const val RECORD_TURN_SECONDS = 4f

private fun disc(track: CloudTrack): Int = track.tags?.discNumber?.takeIf { it > 0 } ?: 1

/**
 * What the encyclopedias say about a record, under its keys (PegasusChrome.About.cs): the genres the
 * catalogues file it under, as chips, and the opening paragraph of its Wikipedia article.
 *
 * The turning record ([RecordSpinner]) stands where the words will be while the catalogues are asked, and only
 * on a real wait: a record already asked about arrives with the page. Nothing is shown if the ask fails or finds nothing; the page is
 * whole without this. An answer that was waited for fades in over [ABOUT_FADE_MS] while the slot opens over
 * [ABOUT_SLIDE_MS], so the centred column glides up by half of what it gained rather than jumping (the
 * desktop's ArriveAbout and SlideRecordInfo).
 *
 * The state is keyed by the record, so a read for a record already left cannot land on this one: its
 * coroutine is cancelled with the key, and these are new holders. The request itself goes on and is kept
 * (see [Dossiers]).
 */
@Composable
private fun AboutRecord(album: CloudAlbum, dossiers: Dossiers, modifier: Modifier) {
    val subject = remember(album) { AlbumSubject(album.artist, album.album, album.tracks.size) }
    var dossier by remember(subject.id) { mutableStateOf(dossiers.cached(subject)) }
    var waiting by remember(subject.id) { mutableStateOf(false) }
    var waited by remember(subject.id) { mutableStateOf(false) }
    LaunchedEffect(subject.id) {
        if (dossier != null) return@LaunchedEffect
        dossier = dossiers.read(subject) {
            waiting = true
            waited = true
        }
        waiting = false
    }
    val genres = remember(dossier) { shelved(dossier?.genres.orEmpty()) }
    val prose = dossier?.article?.lead?.takeIf { it.isNotBlank() }

    Column(modifier.then(if (waited) Modifier.animateContentSize(tween(ABOUT_SLIDE_MS, easing = EaseOutQuad)) else Modifier)) {
        if (waiting) {
            RecordSpinner(Modifier.align(Alignment.CenterHorizontally).padding(top = ABOUT_LEAD * 2).size(24.s))
        } else if (genres.isNotEmpty() || prose != null) {
            val shown = remember(subject.id) { Animatable(if (waited) 0f else 1f) }
            LaunchedEffect(subject.id) { shown.animateTo(1f, tween(ABOUT_FADE_MS, easing = EaseOutQuad)) }
            // No heading over it. "About the album" on a page that is nothing but one album says only what the
            // reader can already see (PegasusChrome.About.cs). Centred, under the centred name and keys, where
            // the desktop starts both at the left.
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = shown.value }) {
                if (genres.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = ABOUT_LEAD),
                        horizontalArrangement = Arrangement.spacedBy(6.s, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.s),
                    ) {
                        genres.forEach { genre ->
                            Text(
                                genre,
                                style = ui(PAGE_TEXT),
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.background(Color(0x1FFFFFFF), RoundedCornerShape(16.s)).padding(horizontal = 14.s, vertical = 5.s),
                            )
                        }
                    }
                }
                if (prose != null) {
                    AboutProse(prose, Modifier.fillMaxWidth().padding(top = if (genres.isEmpty()) ABOUT_LEAD else 8.s))
                }
            }
        }
    }
}

/**
 * The article's opening paragraph in as many lines as the column has room for, at most [ABOUT_MAX_LINES],
 * cut at the last sentence that ends inside them, so the reader is not handed an ellipsis in the middle of
 * a name (the desktop's Shorten). Measured here, where the desktop has to estimate. A first sentence
 * longer than the room is left for the ellipsis to end.
 */
@Composable
private fun AboutProse(prose: String, modifier: Modifier) {
    // The alignment is in the style the cut is measured with, so the two cannot disagree about the lines.
    val style = ui(PAGE_TEXT).copy(lineHeight = ABOUT_LINE, textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val line = with(density) { ABOUT_LINE.toPx() }
        val lines = if (constraints.hasBoundedHeight) minOf(ABOUT_MAX_LINES, (constraints.maxHeight / line).toInt()) else ABOUT_MAX_LINES
        if (lines < 1) return@BoxWithConstraints
        val width = constraints.maxWidth
        val text = remember(prose, width, lines) {
            val layout = measurer.measure(prose, style, constraints = Constraints(maxWidth = width))
            if (layout.lineCount <= lines) {
                prose
            } else {
                val end = layout.getLineEnd(lines - 1, visibleEnd = true)
                val stop = (end - 1 downTo 1).firstOrNull { i -> prose[i] in ".!?" && (i + 1 == prose.length || prose[i + 1].isWhitespace()) }
                if (stop != null) prose.substring(0, stop + 1) else prose
            }
        }
        Text(text, style = style, color = Color.White.copy(alpha = 0.75f), maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

/** The catalogue's genres mapped onto the ones this app shelves by, each once, in the catalogue's order.
 * One that maps onto none is dropped rather than shown raw: a chip naming something nothing else in the
 * app knows about matches no shelf (PegasusChrome.About.cs, Shelved). */
private fun shelved(tags: List<String>): List<String> = tags.mapNotNull(Genres::match).distinct()

/** The air above the about block: the gap the count line keeps from the name above it (AboutLead). */
private val ABOUT_LEAD = 12.s

/** The article's line height: the desktop's 18 to its 12.5 text, at [PAGE_TEXT]. */
private val ABOUT_LINE = 27.4.ts

/** The most lines of the article the page shows: the desktop's AboutMaxLines. */
private const val ABOUT_MAX_LINES = 10

/** How long a waited-for answer takes to come up: the desktop's AboutFade. */
private const val ABOUT_FADE_MS = 900

/** How long the column takes to settle round the opened slot: the desktop's RecordSlideMs. */
private const val ABOUT_SLIDE_MS = 480

/**
 * The track list's ends dissolve as far as the list has scrolled at that end, up to `30·s`: nothing at
 * rest at the top, nothing at the end of the list at the bottom (PegasusChrome.RecordFade.cs).
 */
private fun Modifier.scrollFades(list: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val most = 30.s.toPx()
            val top = if (list.firstVisibleItemIndex > 0) most else minOf(most, list.firstVisibleItemScrollOffset.toFloat())
            val bottom = if (list.canScrollForward) most else 0f
            if (top <= 0f && bottom <= 0f) return@drawWithContent
            val h = size.height
            drawRect(
                Brush.verticalGradient(
                    0f to (if (top > 0f) Color.Transparent else Color.Black),
                    (top / h).coerceIn(0f, 0.5f) to Color.Black,
                    (1f - bottom / h).coerceIn(0.5f, 1f) to Color.Black,
                    1f to (if (bottom > 0f) Color.Transparent else Color.Black),
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** A key on the record page: `#2BFFFFFF`, corner 12·s, no edge. */
@Composable
internal fun PageKey(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    DeskButton(
        onClick = onClick,
        corner = 12.s,
        modifier = modifier.background(KEY_FILL, RoundedCornerShape(12.s)),
    ) { content() }
}

/** "CD n" over a disc's first track: 16·s SemiBold at 0.6 (the desktop's 11), with a hairline under it
 * (Album.cs). */
@Composable
private fun DiscHeading(disc: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 6.s, top = 12.s, end = 10.s, bottom = 2.s)) {
        Text(
            stringResource(R.string.Album_DiscHeader, disc.toString()),
            style = ui(16.ts, FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 5.s),
        )
        Box(Modifier.fillMaxWidth().height(1.px).background(Color(0x26FFFFFF)))
    }
}

/**
 * The line that opens a work on a classical record: its name ([PAGE_TEXT] SemiBold, up to two lines), the composer
 * under it when every movement names the same one (16·s at 0.5), and the whole work's length on the right, lined up
 * with the tracks' titles and lengths. Nothing plays from it, and the remote passes over it.
 */
@Composable
private fun WorkHeading(work: AlbumLine.Work) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.s + 38.s + 12.s, top = 14.s, end = 12.s, bottom = 2.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.s)) {
            Text(work.title, style = ui(PAGE_TEXT, FontWeight.SemiBold), color = Ink.Words, maxLines = 2, overflow = TextOverflow.Ellipsis)
            work.composer?.let { Text(it, style = ui(16.ts), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (work.seconds > 0) {
            Spacer(Modifier.width(12.s))
            Text(clockOf((work.seconds * 1000).toLong()), style = ui(PAGE_TEXT), color = Color.White.copy(alpha = 0.5f))
        }
    }
}

/**
 * One track (Library.cs, RecordTrack): the number (at 0.45, right-aligned in 38·s), the title with the E
 * after it, and the length (at 0.5), all [PAGE_TEXT], 12·s apart, in a row padded 12·s by 10·s with
 * corner 6·s. On a list page the number is the position and a second line says the artist and the album
 * (16·s at 0.5). The desktop has 13, 15 and 12.5. On a classical record a movement is set in by
 * [MOVEMENT_INDENT] and says the movement alone; a piece is SemiBold, with its composer under it ([subtitle]).
 */
/** How far a track's title stands in from its row's left edge: the row's padding, the number's column and the gap. */
private val TRACK_TITLE_INDENT = 12.s + 38.s + 12.s

/** How far a movement stands in under its work's heading. */
private val MOVEMENT_INDENT = 28.s

@Composable
private fun TrackRow(
    number: Int,
    q: Queued,
    inList: Boolean,
    title: String,
    subtitle: String?,
    level: SongLevel,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    DeskButton(
        onClick = onClick,
        corner = 6.s,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.fillMaxWidth().heldOkMenu(onMenu).padding(horizontal = 12.s, vertical = 10.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (level == SongLevel.Movement) Spacer(Modifier.width(MOVEMENT_INDENT))
            Text(number.toString(), style = ui(PAGE_TEXT), color = Color.White.copy(alpha = 0.45f), textAlign = TextAlign.End, modifier = Modifier.width(38.s))
            Spacer(Modifier.width(12.s))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = ui(PAGE_TEXT, if (level == SongLevel.Piece) FontWeight.SemiBold else FontWeight.Normal),
                        color = Ink.Words,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (q.track.tags?.advisory == EXPLICIT) {
                        Spacer(Modifier.width(6.s))
                        ExplicitBadge(17.s)
                    }
                }
                if (subtitle != null) {
                    Text(subtitle, style = ui(16.ts), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (inList) {
                    // Artist and album as two texts, so no language is forced into one word order.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.s)) {
                        Text(q.track.artist.orEmpty(), style = ui(16.ts), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("·", style = ui(16.ts), color = Color.White.copy(alpha = 0.5f))
                        Text(q.albumName, style = ui(16.ts), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            val seconds = q.track.tags?.durationSeconds ?: 0.0
            if (seconds > 0) {
                Spacer(Modifier.width(12.s))
                Text(clockOf((seconds * 1000).toLong()), style = ui(PAGE_TEXT), color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

/** The tags' advisory value for explicit lyrics (the desktop's ContentAdvisory.Explicit). */
const val EXPLICIT = 1
