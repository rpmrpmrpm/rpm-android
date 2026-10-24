package com.rpm.player.phone.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.AlbumLine
import com.rpm.player.core.library.AlbumLines
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.SongLevel
import com.rpm.player.core.library.Cover
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.iconOutline
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.downloads.DownloadButton
import com.rpm.player.phone.downloads.DownloadKey
import com.rpm.player.phone.ui.ArtGround
import com.rpm.player.phone.home.AlbumRow
import com.rpm.player.phone.design.system
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.AudioFormat
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.SimilarAlbums
import com.rpm.player.core.papers.AlbumSubject
import com.rpm.player.core.papers.Dossiers
import com.rpm.player.core.papers.EncyclopediaArticle
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The row at the top of a page with no header, where its back key floats (`RPMLayout.backRow` on iPhone). */
internal val BACK_ROW = 44.dp

/** The share of the page's width that the cover takes. This keeps the title and the keys above the fold. */
internal const val COVER_FRACTION = 0.62f

/**
 * One record. It matches the iPhone's `AlbumPage` (RPMUI/AlbumPage.swift). The page uses its own background:
 * the cover, blurred until it is not recognisable. So each record's page has its own colours. The header holds
 * the cover, the title, the artist, how much record there is, and the keys. Under it are the songs. The song
 * that is playing is marked in the margin.
 *
 * Under the songs are the records on the same shelf to play next (Similar albums), and the reviews wall: what
 * everybody else made of the record, with the reader's own stars and review. The header carries the wall's average
 * once it has answered, the record's format, and what Wikipedia says about it. The artist line opens the artist's
 * page; on a record two acts made, it opens a menu with both. A record its owner marked classical lists its works
 * with their movements under them.
 *
 * A song's long press opens the song menu ([LocalSongMenu]), and a song swipes as on iPhone ([SongSwipe]).
 *
 * @param playing the id of the track playing, which the mark in the margin follows.
 * @param onPlay plays the record from the song at that index: a tap on a row.
 * @param artists the artist pages the artist line opens, in credit order. Empty leaves the line as plain text.
 * @param classical whether the shelf's owner marked the record classical. No default: a record is classical
 * because somebody said so.
 * @param wall the record's reviews, or null where there is no wall to show.
 * @param me the signed-in account, whose own review takes no votes.
 * @param onPlayAll the Play key: the whole record, from the top. A key and a row differ in what "Play now, keep
 * queue" adds when the queue was changed: the whole record for the key, the one song for a row.
 * @param marked whether the record is a favourite. The mark on the **record**, which is not a mark on its songs.
 * @param onMark puts the mark on the record or takes it off, or null where the page shows no heart.
 * @param download the download key, or null where there is none to offer: a borrowed shelf.
 * @param canPlay whether a song can be played now. Offline, a song with no copy on the phone is shown and not offered,
 * and with none at all the play and shuffle keys are dimmed.
 */
@Composable
fun AlbumPage(
    album: CloudAlbum,
    cover: Cover?,
    images: ImageLoader,
    playing: String?,
    bottomRoom: Dp,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onBack: () -> Unit,
    collection: Collection,
    dossiers: Dossiers,
    artists: List<ArtistWallItem>,
    onArtist: (ArtistWallItem) -> Unit,
    onOpenAlbum: (CloudAlbum) -> Unit,
    classical: Boolean,
    wall: ReviewWall?,
    me: String?,
    onPlayAll: () -> Unit = { onPlay(0) },
    marked: Boolean = false,
    onMark: ((Boolean) -> Unit)? = null,
    download: DownloadKey? = null,
    canPlay: (CloudTrack) -> Boolean = { true },
) {
    BackHandler(onBack = onBack)
    val page = rememberBackdrop()
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding() + BACK_ROW
    val lines = remember(album, classical) { AlbumLines.of(album.tracks, classical) }
    // What Wikipedia says about the record. A record opened before has its text on the first frame.
    val subject = remember(album.key) { AlbumSubject(album.artist, album.album, album.tracks.size) }
    var article by remember(subject) { mutableStateOf(dossiers.cached(subject)?.article) }
    LaunchedEffect(subject) { dossiers.read(subject) {}?.let { article = it.article } }
    var reading by remember(subject) { mutableStateOf(false) }
    BackHandler(enabled = reading) { reading = false }
    var writing by remember(album.key) { mutableStateOf(false) }
    // The Similar albums row, worked out off the main thread: it walks every track on the shelf. It is worked out
    // again for another record or a shelf with records added or gone, and kept with the record it was made for.
    val shelf = collection.albums
    val similar by produceState<Pair<String, List<CloudAlbum>>?>(null, album.key, shelf.size) {
        value = album.key to withContext(Dispatchers.Default) { SimilarAlbums.row(album, shelf) }
    }
    // The wall is read when the page opens, not when it is scrolled to: a wall that appears only when looked for
    // is not found.
    if (wall != null) LaunchedEffect(wall) { wall.read() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = (maxWidth.value * COVER_FRACTION).coerceIn(160f, 360f).roundToInt().dp
        Box(Modifier.fillMaxSize().backdropSource(page)) {
            ArtGround(cover, images)
            // The list scrolls up behind the status bar. Content that scrolls up there fades into the background.
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().dissolvesAtTop(top) { list.scrolled() }) {
                item(key = "header") {
                    Header(
                        album, cover, images, edge, top, onPlayAll, onShuffle,
                        marked = marked,
                        onMark = onMark,
                        artists = artists,
                        onArtist = onArtist,
                        rating = wall?.takeIf { it.ratingCount > 0 }?.let { it.average to it.ratingCount },
                        article = article,
                        onRead = { reading = true },
                        download = download,
                        anyPlayable = album.tracks.any(canPlay),
                    )
                }
                items(lines, key = { it.key }, contentType = { it::class }) { line ->
                    when (line) {
                        is AlbumLine.Disc -> DiscHeading(line.number)
                        is AlbumLine.Work -> WorkHeading(line)
                        is AlbumLine.Song -> SongSwipe(line.track) { SongRow(line, playing = line.track.rpmId == playing, enabled = canPlay(line.track)) { onPlay(line.index) } }
                    }
                }
                similar?.takeIf { it.first == album.key && it.second.isNotEmpty() }?.let { (_, row) ->
                    item(key = "similar") {
                        Box(Modifier.padding(top = 28.dp)) {
                            AlbumRow(stringResource(R.string.Album_SimilarAlbums), row, collection, images, onOpenAlbum)
                        }
                    }
                }
                if (wall != null) {
                    reviewsWall(
                        wall,
                        me,
                        onRate = { stars -> scope.launch { wall.write(stars, wall.mine?.body.orEmpty()) } },
                        onWrite = { writing = true },
                        onSort = { order -> scope.launch { wall.sorted(order) } },
                        onVote = { review, value -> scope.launch { wall.vote(review, value) } },
                        onMore = { scope.launch { wall.more() } },
                    )
                }
                item(key = "foot") { Spacer(Modifier.height(40.dp + bottomRoom)) }
            }
        }
        // The back key floats over the page. It does not scroll with the page. It sits in the row kept free under the
        // status bar.
        Box(Modifier.padding(top = top - BACK_ROW).height(BACK_ROW).padding(start = 20.dp), contentAlignment = Alignment.Center) {
            BackKey(page, onBack)
        }
        // Both sheets cover the tab bar and the mini player, as the iPhone's do.
        article?.takeIf { reading }?.let {
            OverChrome { AboutSheet(stringResource(R.string.TrackInfo_AboutAlbum), album.album, it, page, bottomRoom, onClose = { reading = false }) }
        }
        if (writing && wall != null) OverChrome {
            WriteReviewSheet(
                album.album,
                wall.myStars,
                wall.mine?.body.orEmpty(),
                page,
                bottomRoom,
                onSave = { stars, words -> wall.write(stars, words) },
                onDelete = if (wall.mine == null) null else ({ wall.deleteMine() }),
                onClose = { writing = false },
            )
        }
    }
}

@Composable
private fun Header(
    album: CloudAlbum,
    cover: Cover?,
    images: ImageLoader,
    edge: Dp,
    top: Dp,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    marked: Boolean,
    onMark: ((Boolean) -> Unit)?,
    artists: List<ArtistWallItem>,
    onArtist: (ArtistWallItem) -> Unit,
    rating: Pair<Double, Int>?,
    article: EncyclopediaArticle?,
    onRead: () -> Unit,
    download: DownloadKey?,
    anyPlayable: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(top))
        SleeveSquare(cover, edge, images, radius = 4.dp, glyphSize = 52f, bordered = false)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(album.album, maxLines = 3, style = displayBold(24f).copy(color = Color.White, textAlign = TextAlign.Center))
            ArtistLine(album.artist, artists, onArtist)
        }
        // How much record there is, as a chip because it describes the record above it, and what it is made of under
        // it, quieter, because that describes the file and not the music.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                count(album),
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Ink.white(0x1F))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                style = display(12f).copy(color = Ink.white(0xE6)),
            )
            val mono = stringResource(R.string.Track_Mono)
            val format = remember(album, mono) { AudioFormat.of(album.tracks, mono) }
            if (format.isNotEmpty()) {
                BasicText(format, style = display(12f).copy(color = Ink.white(0x8A), fontStyle = FontStyle.Italic))
            }
        }
        // What the wall makes of the record, once rpm has answered. A wall that never answers leaves no line at all.
        if (rating != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StarRating(rating.first, 13f)
                BasicText(
                    if (rating.second == 1) stringResource(R.string.Rpm_Album_RatingsOne) else stringResource(R.string.Rpm_Album_Ratings, rating.second),
                    style = display(12f).copy(color = Ink.white(0xAA)),
                )
            }
        }
        // Above the keys, so it is read before the record is played.
        if (article != null) AboutText(article, onOpen = onRead, Modifier.padding(top = 4.dp))
        // The play key, and beside it a shuffle key for the same record, and the mark on it.
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(onClick = onPlayAll, modifier = Modifier.weight(1f).alpha(if (anyPlayable) 1f else 0.4f), weight = Weight.Primary, enabled = anyPlayable, height = 50.dp, radius = 14.dp) { style ->
                BasicText(Glyph.play, style = icon(15f).copy(color = style.color))
                Spacer(Modifier.width(10.dp))
                BasicText(stringResource(R.string.AlbumGrid_PlayAll), style = display(17f).copy(color = style.color))
            }
            GlassButton(onClick = onShuffle, modifier = Modifier.width(56.dp).alpha(if (anyPlayable) 1f else 0.4f), enabled = anyPlayable, stretches = false, height = 50.dp, radius = 14.dp, inset = 0.dp) { style ->
                BasicText(Glyph.shuffle, style = icon(17f).copy(color = style.color))
            }
            // The mark on the record goes to the shared wall, so a record loved on the phone is loved on the computer.
            onMark?.let { mark ->
                GlassButton(onClick = { mark(!marked) }, modifier = Modifier.width(56.dp), stretches = false, height = 50.dp, radius = 14.dp, inset = 0.dp) { style ->
                    BasicText(Glyph.heart, style = (if (marked) icon(17f) else iconOutline(17f)).copy(color = style.color))
                }
            }
            if (download != null) DownloadButton(download)
        }
        // Only while it is true: the list is then in the order the server sent, and is not shown as the running order.
        if (album.tracks.none { (it.tags?.trackNumber ?: 0) > 0 }) {
            BasicText(stringResource(R.string.Rpm_Album_Unordered), style = display(11f).copy(color = Ink.white(0x77)))
        }
    }
}

/**
 * The record's artist. Pressing it opens their page; on a record two acts made, it opens a menu with both, in
 * credit order. With no page to open it is plain text.
 */
@Composable
private fun ArtistLine(name: String, artists: List<ArtistWallItem>, onArtist: (ArtistWallItem) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box {
        BasicText(
            name,
            if (artists.isEmpty()) {
                Modifier
            } else {
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                    if (artists.size == 1) onArtist(artists[0]) else choosing = true
                }
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = display(16f).copy(color = Ink.white(0xD9)),
        )
        if (choosing) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
                onDismissRequest = { choosing = false },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .width(240.dp)
                        .clip(shape)
                        .background(Ink.ground.copy(alpha = 0.96f))
                        .border(1.dp, Ink.cardBorder, shape)
                        .padding(vertical = 4.dp),
                ) {
                    for (who in artists) {
                        BasicText(
                            who.name,
                            Modifier
                                .fillMaxWidth()
                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                    choosing = false
                                    onArtist(who)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = system(16f).copy(color = Color.White),
                        )
                    }
                }
            }
        }
    }
}

/** How far a movement is set in from its work, and where a work's heading starts: the row's inset, the mark, the gap. */
private val MOVEMENT_INDENT = 18.dp
private val NUMBER_EDGE = (8 + 3 + 14).dp

/** The line that opens a work: its name, who wrote it, and how long the whole piece runs. Nothing plays from it. */
@Composable
private fun WorkHeading(work: AlbumLine.Work) {
    Row(
        Modifier.fillMaxWidth().padding(start = NUMBER_EDGE, end = 20.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = displayBold(16f).copy(color = Color.White))
            work.composer?.let {
                BasicText(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0x77)))
            }
        }
        BasicText(length(work.seconds), style = display(13f).copy(color = Ink.white(0x66)))
    }
}

@Composable
private fun count(album: CloudAlbum): String {
    val songs = if (album.tracks.size == 1) stringResource(R.string.Rpm_Album_TracksOne) else stringResource(R.string.Rpm_Album_Tracks, album.tracks.size)
    val seconds = album.tracks.sumOf { it.tags?.durationSeconds ?: 0.0 }.toInt()
    if (seconds <= 0) return songs
    val hours = seconds / 3600
    val runtime = if (hours >= 1) stringResource(R.string.Stats_HoursMinutes, hours, (seconds % 3600) / 60) else stringResource(R.string.Stats_Minutes, seconds / 60)
    return "$songs · $runtime"
}

/** The heading for one disc of a set. A single-disc record never shows one. */
@Composable
private fun DiscHeading(number: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(stringResource(R.string.AlbumEdit_DiscN, number), style = kicker())
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1F)))
    }
}

/**
 * One song: the playing mark in the margin, the track number in a fixed column, the title (with the E for explicit
 * lyrics after it) over the track's own artist, and the length. The artist line is on every row, so all rows have
 * the same height. On a classical record the small line is the composer under a piece and nothing under a
 * movement, whose work has said who wrote it; a piece is set in the weight of a work's heading, and a movement is
 * set in. The row is lit while a finger is on it.
 */
@Composable
private fun SongRow(line: AlbumLine.Song, playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val track = line.track
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .background(if (pressed) Ink.white(0x24) else Color.Transparent)
            .then(if (enabled) Modifier.songPress(track, presses, onClick) else Modifier)
            .padding(start = 8.dp, end = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(percent = 50)).background(if (playing) Color.White else Color.Transparent))
        BasicText(
            track.tags?.trackNumber?.takeIf { it > 0 }?.toString().orEmpty(),
            Modifier.padding(start = if (line.level == SongLevel.Movement) MOVEMENT_INDENT else 0.dp).width(22.dp),
            style = display(14f).copy(color = Ink.white(0x66)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    line.title,
                    Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = (if (line.level == SongLevel.Piece) displayBold(16f) else display(16f)).copy(color = Color.White),
                )
                if (track.tags?.advisory == EXPLICIT) ExplicitMark()
            }
            val second = if (line.level == SongLevel.Plain) track.artist?.takeIf { it.isNotEmpty() } else line.subtitle
            second?.let {
                BasicText(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0x77)))
            }
        }
        BasicText(length(track.tags?.durationSeconds ?: 0.0), style = display(13f).copy(color = Ink.white(0x66)))
    }
}

/** A song's length as minutes and seconds. It is blank when the catalogue gives no length. */
internal fun length(seconds: Double): String {
    val whole = seconds.toInt()
    return if (whole <= 0) "" else "%d:%02d".format(whole / 60, whole % 60)
}
