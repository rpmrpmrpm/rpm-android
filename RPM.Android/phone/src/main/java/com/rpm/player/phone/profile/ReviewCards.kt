package com.rpm.player.phone.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.rpm.player.core.R
import com.rpm.player.core.cloud.FeedReview
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Cover
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.SleeveSquare
import com.rpm.player.phone.library.StarRating

private val MARGIN = 20.dp

/**
 * A review as a card, on a profile page and on the Feed tab. The record's title, its artist and year, and the stars
 * stand at the left, with the cover at the right; the review follows in quotation marks, beside the cover while there is
 * room and then on under it across the card, as the desktop's conductor box runs text round a picture. A card shows at
 * most [REVIEW_LINES] lines; Read full review at the foot opens the whole review.
 *
 * @param author the line with the author's photo and name above the record, on the Feed tab; null on the author's own
 *   page, where the time stands beside the stars instead.
 */
@Composable
internal fun ReviewRow(
    shown: FeedReview,
    isOwn: Boolean,
    album: CloudAlbum?,
    coverOf: (CloudAlbum) -> Cover?,
    albumCover: (suspend (artist: String, title: String) -> String?)?,
    images: ImageLoader,
    modifier: Modifier,
    onVote: (Int) -> Unit,
    onRead: () -> Unit,
    author: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.white(0x12))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onRead)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
    ) {
        author?.let {
            it()
            Spacer(Modifier.height(12.dp))
        }
        WrappedReview(shown, showTime = author == null) {
            ReviewCover(shown, album, coverOf, albumCover, images, REVIEW_COVER)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            VoteKeys(shown.ups, shown.downs, shown.myVote, enabled = !isOwn, onVote = onVote)
            Spacer(Modifier.weight(1f))
            BasicText(
                stringResource(R.string.Rpm_Profile_ReadReview),
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onRead)
                    .padding(vertical = 8.dp),
                style = system(14f, FontWeight.SemiBold).copy(color = Color.White),
            )
        }
    }
}

/**
 * The head of a review and its text, run round the cover ([cover], drawn [REVIEW_COVER] square at the top right). The
 * text is measured at the width beside the cover; every line that starts above the cover's foot stays beside it, and
 * the rest is laid out again at the full width and carries on straight under it, so the text reads as one block and no
 * line is cut in two.
 */
@Composable
private fun WrappedReview(shown: FeedReview, showTime: Boolean, cover: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = display(14f).copy(color = Ink.white(0xE6))
    val quoted = shown.body.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.Rpm_Profile_Quoted, it) }.orEmpty()
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val full = constraints.maxWidth
        val edge = REVIEW_COVER.roundToPx()
        val gap = 12.dp.roundToPx()
        val space = 8.dp.roundToPx()
        val narrow = (full - edge - gap).coerceAtLeast(1)

        val coverPlaced = subcompose("cover", cover).first().measure(Constraints.fixed(edge, edge))
        val head = subcompose("head") { ReviewHead(shown, showTime) }.first().measure(Constraints(maxWidth = narrow))

        // The lines beside the cover: those that start above its foot.
        var beside = ""
        var below = ""
        var besideLines = 0
        if (quoted.isNotEmpty()) {
            val room = edge - head.height - space
            val laid = measurer.measure(quoted, style, constraints = Constraints(maxWidth = narrow))
            while (besideLines < laid.lineCount && besideLines < REVIEW_LINES && laid.getLineTop(besideLines) < room) besideLines++
            when {
                besideLines >= laid.lineCount -> beside = quoted
                besideLines == 0 -> below = quoted
                else -> {
                    val end = laid.getLineEnd(besideLines - 1)
                    beside = quoted.substring(0, end).trimEnd()
                    below = quoted.substring(end).trimStart()
                }
            }
        }
        val besidePlaced = beside.takeIf { it.isNotEmpty() }?.let { text ->
            subcompose("beside") { BasicText(text, style = style) }.first().measure(Constraints(maxWidth = narrow))
        }
        val left = head.height + (besidePlaced?.let { space + it.height } ?: 0)
        // Straight on from the lines beside the cover; under the cover when there were none.
        val belowTop = if (besidePlaced != null) left else maxOf(left, edge + space)
        val belowPlaced = below.takeIf { it.isNotEmpty() && besideLines < REVIEW_LINES }?.let { text ->
            val lines = REVIEW_LINES - besideLines
            subcompose("below") { BasicText(text, maxLines = lines, overflow = TextOverflow.Ellipsis, style = style) }
                .first().measure(Constraints(maxWidth = full))
        }
        val height = maxOf(left, edge, belowPlaced?.let { belowTop + it.height } ?: 0)
        layout(full, height) {
            head.place(0, 0)
            besidePlaced?.place(0, head.height + space)
            coverPlaced.place(full - edge, 0)
            belowPlaced?.place(0, belowTop)
        }
    }
}

/** The record's title, its artist and year, and the stars; on the author's own page, the time beside the stars. */
@Composable
private fun ReviewHead(shown: FeedReview, showTime: Boolean) {
    Column {
        BasicText(shown.albumTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = system(17f, FontWeight.SemiBold).copy(color = Color.White))
        BasicText(
            if (shown.albumYear > 0) "${shown.albumArtist}, ${shown.albumYear}" else shown.albumArtist,
            Modifier.padding(top = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = display(13f).copy(color = Ink.white(0x99)),
        )
        if (shown.rating > 0 || showTime) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shown.rating > 0) StarRating(shown.rating / 2.0, 15f)
                if (shown.rating > 0 && showTime) Spacer(Modifier.width(10.dp))
                if (showTime) BasicText(written(shown.createdAt), maxLines = 1, style = display(11f).copy(color = Ink.white(0x77)))
            }
        }
    }
}

/** The record's cover: the reader's own copy's, else the catalogue's, else the record glyph. */
@Composable
internal fun ReviewCover(
    shown: FeedReview,
    album: CloudAlbum?,
    coverOf: (CloudAlbum) -> Cover?,
    albumCover: (suspend (artist: String, title: String) -> String?)?,
    images: ImageLoader,
    edge: Dp,
) {
    if (album != null) {
        SleeveSquare(coverOf(album), edge, images, radius = 10.dp, glyphSize = 28f)
        return
    }
    val found by produceState<String?>(null, shown.albumArtist, shown.albumTitle) {
        value = albumCover?.invoke(shown.albumArtist, shown.albumTitle)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(edge).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(Glyph.record, style = icon(28f).copy(color = Ink.white(0x3A)))
        found?.let { AsyncImage(model = rememberPicture(it, edge), contentDescription = null, imageLoader = images, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** The author's photo and name over a review on the Feed tab, with when it was written. Opens their page. */
@Composable
internal fun ReviewAuthor(shown: FeedReview, images: ImageLoader, onPerson: () -> Unit) {
    val name = if (shown.authorDeleted) stringResource(R.string.Community_DeletedUser) else shown.authorName
    Row(
        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = !shown.authorDeleted, onClick = onPerson),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonPicture(if (shown.authorDeleted) null else rememberFace(shown.userId, shown.hasAvatar), name, 32.dp, images)
        Byline(name, shown.createdAt, gold = shown.authorSupporter)
    }
}

/**
 * A whole review, risen over the page from Read full review: the cover beside the record's title, artist, year and
 * stars, then every line of the review, the votes, and Open album when the record is in the reader's collection.
 *
 * @param ground what the page under it is drawn on ([PageGroundView]); empty for the app's standard background.
 */
@Composable
internal fun ReviewPage(
    review: FeedReview,
    isOwn: Boolean,
    album: CloudAlbum?,
    coverOf: (CloudAlbum) -> Cover?,
    albumCover: (suspend (artist: String, title: String) -> String?)?,
    images: ImageLoader,
    ground: String,
    onVote: (Int) -> Unit,
    onOpenAlbum: (CloudAlbum) -> Unit,
    onClose: () -> Unit,
    author: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        PageGroundView(ground)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Spacer(Modifier.weight(1f))
                CloseKey(onClose)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MARGIN),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                author?.invoke()
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    ReviewCover(review, album, coverOf, albumCover, images, 140.dp)
                    Box(Modifier.weight(1f)) { ReviewHead(review, showTime = true) }
                }
                if (review.body.isNotBlank()) {
                    BasicText(stringResource(R.string.Rpm_Profile_Quoted, review.body.trim()), style = display(15f).copy(color = Ink.white(0xEE)))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VoteKeys(review.ups, review.downs, review.myVote, enabled = !isOwn, onVote = onVote)
                    Spacer(Modifier.weight(1f))
                    if (album != null) SmallKey(stringResource(R.string.Rpm_Profile_OpenAlbum), Weight.Ghost) { onOpenAlbum(album) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
