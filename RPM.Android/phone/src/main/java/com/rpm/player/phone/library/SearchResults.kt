package com.rpm.player.phone.library

import com.rpm.player.phone.ui.ArtistMark
import com.rpm.player.phone.ui.MarkView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.Artwork
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.GenreWallItem
import com.rpm.player.core.library.SongSearch
import com.rpm.player.core.library.TrackList
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.scrolled

/**
 * What one search answered, and the words it is the answer to: the artists, records, songs, playlists and genres the
 * words name. Each playlist comes with the name it is shown by.
 */
internal class SearchAnswer(
    val asked: String,
    val records: List<CloudAlbum>,
    val songs: List<SongSearch.SongHit>,
    val artists: List<ArtistWallItem> = emptyList(),
    val playlists: List<Pair<TrackList, String>> = emptyList(),
    val genres: List<GenreWallItem> = emptyList(),
) {
    val isEmpty: Boolean get() = records.isEmpty() && songs.isEmpty() && artists.isEmpty() && playlists.isEmpty() && genres.isEmpty()

    companion object {
        val NONE = SearchAnswer("", emptyList(), emptyList())
    }
}

/** What a tap on each kind of answer opens or plays. */
internal class SearchActions(
    val onOpen: (CloudAlbum) -> Unit,
    val onPlay: (SongSearch.SongHit) -> Unit,
    val onOpenArtist: (ArtistWallItem) -> Unit,
    val onOpenList: (TrackList) -> Unit,
    val onOpenGenre: (GenreWallItem) -> Unit,
)

/**
 * The results of a library search: everything the collection holds that the words name, whichever view it lives in.
 * It matches the iPhone's `LibraryPage.results` and adds the artists, playlists and genres. Each kind has its own
 * group under its own heading, in the order artists, records, songs, playlists, genres: a record is opened and a song
 * is played, so one list could not show which action a row does.
 *
 * **A group shows its first [FIRST] answers.** One with more has "View more" at the right of its heading, which shows
 * the rest, and then "View less". The rest come in as the encyclopedia text unfolds: they fade in while everything under
 * them moves down to make room, in the same time ([UNFOLD_MS]). A new search starts every group folded.
 *
 * "No search results" is shown only when the answer is for the words now in the box. While the typing is
 * still settling, that message would be wrong for the next keystroke. Dragging the list hides the keyboard.
 */
@Composable
internal fun SearchResults(
    asked: String,
    answer: SearchAnswer,
    collection: Collection,
    images: ImageLoader,
    artwork: Artwork,
    playing: String?,
    bottomRoom: Dp,
    actions: SearchActions,
) {
    if (answer.isEmpty) {
        if (answer.asked == asked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(stringResource(R.string.Library_NoSearchResults), style = display(14f).copy(color = Ink.white(0x88)))
            }
        }
        return
    }
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) focus.clearFocus() }
    // Which groups show all their answers. Folded again for every new answer.
    val open = remember(answer) { mutableStateMapOf<String, Boolean>() }
    val headings = Headings(
        artists = stringResource(R.string.Artists_Title),
        records = stringResource(R.string.Viz_BrowseAlbums),
        songs = stringResource(R.string.Album_Tracks),
        playlists = stringResource(R.string.Playlists_Title),
        genres = stringResource(R.string.Genres_Title),
        more = stringResource(R.string.Library_SearchViewMore),
        less = stringResource(R.string.Library_SearchViewLess),
    )
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().dissolvesAtTop(30.dp) { list.scrolled() },
        contentPadding = PaddingValues(bottom = FOOT + bottomRoom),
    ) {
        group("artists", headings.artists, headings, answer.artists, open, key = { "artist:" + it.name }) { artist ->
            ArtistLine(artist, collection, images, artwork) { actions.onOpenArtist(artist) }
        }
        group("records", headings.records, headings, answer.records, open, key = { "record:" + it.key }) { album ->
            Line(album, collection, images, album.album, album.artist, mark = false, onClick = { actions.onOpen(album) }) {
                BasicText(if (album.tracks.size == 1) stringResource(R.string.Rpm_Album_TracksOne) else stringResource(R.string.Rpm_Album_Tracks, album.tracks.size), maxLines = 1, style = display(12f).copy(color = Ink.white(0x66)))
            }
        }
        group("songs", headings.songs, headings, answer.songs, open, key = { "song:" + it.track.rpmId }) { hit ->
            Line(hit.album, collection, images, hit.track.title.orEmpty(), hit.track.artist.orEmpty(), mark = hit.track.rpmId == playing, song = hit.track, onClick = { actions.onPlay(hit) })
        }
        if (answer.songs.size >= SongSearch.CEILING && open["songs"] == true) {
            item(key = "ceiling") {
                BasicText(
                    stringResource(R.string.Rpm_Library_SearchCeiling, SongSearch.CEILING),
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).animateItem(),
                    style = display(12f).copy(color = Ink.white(0x77), textAlign = TextAlign.Center),
                )
            }
        }
        group("playlists", headings.playlists, headings, answer.playlists, open, key = { "list:" + it.first.id }) { (playlist, name) ->
            PlaylistLine(playlist, name, collection, images) { actions.onOpenList(playlist) }
        }
        group("genres", headings.genres, headings, answer.genres, open, key = { "genre:" + it.name }) { genre ->
            GenreLine(genre, images) { actions.onOpenGenre(genre) }
        }
    }
}

/** The groups' headings and the words of the key that unfolds one, read once for the whole list. */
private class Headings(
    val artists: String,
    val records: String,
    val songs: String,
    val playlists: String,
    val genres: String,
    val more: String,
    val less: String,
)

/** How many answers a group shows before "View more". */
private const val FIRST = 5

/** How long a group takes to unfold or fold: the encyclopedia text's time (`UNFOLD_MS` in About.kt). */
private const val UNFOLD_MS = 320

/**
 * One group of answers: its heading, its first [FIRST] rows, and the rest while it is open. Nothing at all for a kind
 * with no answers. The rows past the first fade in and out, and the rows and groups under them move in the same time,
 * so the group seems to open downward from its last row.
 */
private fun <T> LazyListScope.group(
    id: String,
    title: String,
    headings: Headings,
    answers: List<T>,
    open: MutableMap<String, Boolean>,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    if (answers.isEmpty()) return
    val unfolded = open[id] == true
    val folds = answers.size > FIRST
    item(key = "heading:$id") {
        Heading(
            title,
            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = MOVE),
            toggle = if (folds) (if (unfolded) headings.less else headings.more) else null,
            onToggle = { open[id] = !unfolded },
        )
    }
    val shown = if (unfolded || !folds) answers else answers.take(FIRST)
    items(shown, key = key) { answer ->
        Box(Modifier.animateItem(fadeInSpec = FADE, fadeOutSpec = FADE_OUT, placementSpec = MOVE)) { row(answer) }
    }
}

private val MOVE = tween<androidx.compose.ui.unit.IntOffset>(UNFOLD_MS, easing = FastOutSlowInEasing)
private val FADE = tween<Float>(UNFOLD_MS)
private val FADE_OUT = tween<Float>(UNFOLD_MS / 2)

/**
 * A heading for one group of results. It is in upper case, so it does not look like a row. [toggle] is the words of the
 * key at its right that shows the rest of the group or folds it again; null for a group that shows all it has.
 */
@Composable
private fun Heading(said: String, modifier: Modifier, toggle: String?, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(said.uppercase(), Modifier.weight(1f), style = kicker())
        if (toggle != null) {
            BasicText(
                toggle,
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle),
                style = display(12f).copy(color = Ink.white(0xCC)),
            )
        }
    }
}

/**
 * One row: the playing mark in the margin (empty on a record's row, so both kinds of row start their covers on
 * the same line), a 40 dp cover, two text lines, and whatever goes at the end.
 */
@Composable
private fun Line(
    album: CloudAlbum,
    collection: Collection,
    images: ImageLoader,
    title: String,
    line: String,
    mark: Boolean,
    onClick: () -> Unit,
    song: CloudTrack? = null,
    end: @Composable () -> Unit = {},
) {
    Entry(title, line, mark, onClick, song, end) {
        SleeveSquare(collection.cover(album), 40.dp, images, radius = 6.dp, glyphSize = 15f)
    }
}

/**
 * An artist found: their photograph where there is one, in a circle, their name, and how many records of theirs are
 * here. An artist with no photograph shows one of their own records, as their box on the artists wall stands on one.
 */
@Composable
private fun ArtistLine(artist: ArtistWallItem, collection: Collection, images: ImageLoader, artwork: Artwork, onClick: () -> Unit) {
    val photo = artistBackdrop(artist, collection, artwork)
    val sleeve = remember(artist, collection) { artist.own.firstNotNullOfOrNull { collection.album(it) }?.let { collection.cover(it) } }
    // Round, so an artist reads apart from the records, playlists and genres around them, which are square.
    Entry(artist.name, albumCount(artist.own.size), mark = false, onClick = onClick) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0x22 / 255f)), contentAlignment = Alignment.Center) {
            when {
                photo != null -> AsyncImage(
                    model = rememberPicture(photo, 40.dp),
                    contentDescription = null,
                    imageLoader = images,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                sleeve != null -> SleeveSquare(sleeve, 40.dp, images, radius = 20.dp, glyphSize = 15f, bordered = false)
                else -> MarkView(ArtistMark, Ink.white(0x3A), Modifier.size(15.dp))
            }
        }
    }
}

/** A playlist found: its square, its name, and how many songs it holds. */
@Composable
private fun PlaylistLine(playlist: TrackList, name: String, collection: Collection, images: ImageLoader, onClick: () -> Unit) {
    val songs = playlist.tracks.size
    Entry(name, stringResource(R.string.Home_Songs, songs), mark = false, onClick = onClick) {
        PlaylistSleeve(collection.cover(playlist), Glyph.playlists, edge = 40.dp, corner = 6.dp, mark = 15f, images, FramedList.of(playlist, name))
    }
}

/** A genre found: its photograph, its name, and how many records it holds. */
@Composable
private fun GenreLine(genre: GenreWallItem, images: ImageLoader, onClick: () -> Unit) {
    Entry(genre.name, albumCount(genre.albums.size), mark = false, onClick = onClick) {
        GenrePicture(genre.name, images, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
    }
}

@Composable
private fun albumCount(count: Int): String =
    if (count == 1) stringResource(R.string.Stats_AlbumsOne) else stringResource(R.string.Stats_Albums, count)

/**
 * The shape every row of the results shares: the playing mark in the margin, a 40 dp [picture], two text lines, and
 * whatever goes at the [end].
 */
@Composable
private fun Entry(
    title: String,
    line: String,
    mark: Boolean,
    onClick: () -> Unit,
    song: CloudTrack? = null,
    end: @Composable () -> Unit = {},
    picture: @Composable () -> Unit,
) {
    val presses = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            // A song found has the song menu on a long press; the other answers have none, as on iPhone.
            .then(if (song != null) Modifier.songPress(song, presses, onClick) else Modifier.clickable(presses, indication = null, onClick = onClick))
            .padding(start = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(50)).background(if (mark) Color.White else Color.Transparent))
        Box(Modifier.padding(start = 9.dp)) { picture() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
            BasicText(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0x88)))
        }
        end()
    }
}
