package com.rpm.player.phone.library

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.cloud.Review
import com.rpm.player.core.cloud.ReviewSort
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.RecordSpinner
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** The colour of a lit star. */
private val GOLD = Color(0xFFF2C14E)

/** A whole star, and a half star (its left half lit). A half star is its own glyph: a half-lit glyph stays readable at
 * eleven points, which a partly transparent one does not. */
private const val WHOLE = ""
private const val HALF = ""

/** The vote keys' chevrons. */
private const val UP = ""
private const val DOWN = ""

/**
 * Five stars, in halves. It is the iPhone's `StarRating`. The same control shows a record's average, a review's verdict,
 * and takes the reader's own rating, which it does only when [onPick] is given: a tap picks that star, and a long press
 * picks the half below it, because a thumb covers the whole star and cannot point at half of one.
 */
@Composable
fun StarRating(value: Double, size: Float, onPick: ((Double) -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    val outOf = stringResource(R.string.Rpm_Album_RatingOutOf, NumberFormat.getInstance().format(value))
    Row(
        Modifier.semantics(mergeDescendants = onPick == null) { contentDescription = outOf },
        horizontalArrangement = Arrangement.spacedBy(if (onPick == null) 1.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (at in 0 until 5) {
            val lit = value - at
            val star = Modifier
                .defaultMinSize(minWidth = (size * if (onPick == null) 1.25f else 1.9f).dp, minHeight = (if (onPick == null) size else size * 1.9f).dp)
                .then(
                    if (onPick == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(at) {
                            detectTapGestures(
                                onTap = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(at + 1.0) },
                                onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(at + 0.5) },
                            )
                        }
                    },
                )
            Box(star, contentAlignment = Alignment.Center) {
                BasicText(
                    if (lit >= 0.75) WHOLE else if (lit >= 0.25) HALF else WHOLE,
                    style = icon(size).copy(color = if (lit >= 0.25) GOLD else Ink.white(0x44)),
                )
            }
        }
    }
}

/**
 * What everybody else made of the record, under its songs and Similar albums. It is the iPhone's `ReviewsWall`. It
 * loads after the songs and fills in under them: nothing here is needed to play the record, so nothing here holds it
 * up. The rows are rows of the page's own list, not a list of their own, so one finger scrolls one thing.
 *
 * @param me the signed-in account, whose own review takes no votes.
 */
/** [onAuthor] opens the page of a review's author. */
fun LazyListScope.reviewsWall(wall: ReviewWall, me: String?, onRate: (Double) -> Unit, onWrite: () -> Unit, onSort: (ReviewSort) -> Unit, onVote: (Review, Int) -> Unit, onMore: () -> Unit, onAuthor: (Review) -> Unit = {}) {
    item(key = "reviews:head", contentType = "reviews:head") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
            Heading(wall, onSort)
            Mine(wall.myStars, onRate, onWrite)
            val trouble = wall.trouble
            if (trouble != null) {
                BasicText(said(trouble), style = display(12f).copy(color = Ink.white(0x99)))
            } else if (wall.reviews.isEmpty() && !wall.reading) {
                BasicText(stringResource(R.string.Album_NoReviews), style = display(13f).copy(color = Ink.white(0x88)))
            }
        }
    }
    items(wall.reviews, key = { "review:" + it.userId }, contentType = { "review" }) { review ->
        ReviewRow(review, mine = review.userId == me, onVote, onAuthor, Modifier.padding(horizontal = 20.dp).padding(top = 14.dp))
    }
    item(key = "reviews:foot", contentType = "reviews:foot") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 28.dp)) {
            // One key that adds the next batch underneath, as a phone shows pages, not a row of page numbers.
            if (wall.hasMore) {
                GlassButton(onClick = onMore, height = 40.dp, radius = 10.dp, size = 13f) { style ->
                    BasicText(stringResource(R.string.Rpm_Album_MoreReviews), style = style)
                }
            }
        }
    }
}

/** The heading, with what the wall rates the record, and two orders: the most helpful, and the newest. */
@Composable
private fun Heading(wall: ReviewWall, onSort: (ReviewSort) -> Unit) {
    val heading = if (wall.ratingCount > 0) {
        val average = NumberFormat.getInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }.format(wall.average)
        stringResource(R.string.Rpm_Album_ReviewsRated, average, wall.ratingCount)
    } else {
        stringResource(R.string.Album_ReviewsHeading)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(heading, Modifier.weight(1f).padding(end = 8.dp), style = kicker())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((order, label) in listOf(ReviewSort.Votes to R.string.Album_SortVotes, ReviewSort.Date to R.string.Album_SortDate)) {
                GlassButton(
                    onClick = { onSort(order) },
                    weight = if (wall.sort == order) Weight.Primary else Weight.Ghost,
                    stretches = false,
                    height = 30.dp,
                    radius = 15.dp,
                    inset = 12.dp,
                    size = 12f,
                ) { style -> BasicText(stringResource(label), style = style) }
            }
        }
    }
}

/** The reader's own verdict, which is also how one is given: the stars take a tap, and the key opens the sheet for words. */
@Composable
private fun Mine(stars: Double, onRate: (Double) -> Unit, onWrite: () -> Unit) {
    val card = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(card)
            .background(Ink.white(0x14))
            .border(1.dp, Ink.glassEdge, card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.Album_YourRating), Modifier.weight(1f), style = display(13f).copy(color = Ink.white(0xCC)))
            StarRating(stars, 20f, onPick = onRate)
        }
        GlassButton(onClick = onWrite, height = 40.dp, radius = 10.dp, size = 14f) { style ->
            BasicText(stringResource(if (stars > 0) R.string.Rpm_Album_EditReview else R.string.AlbumMenu_WriteReview), style = style)
        }
    }
}

/**
 * One review: the first letter of the author's name in a circle (an avatar would be one more fetch per row on a page
 * that is about the record), the name, the stars and when, the votes, and the words.
 */
@Composable
private fun ReviewRow(review: Review, mine: Boolean, onVote: (Review, Int) -> Unit, onAuthor: (Review) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.white(0x12))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Ink.white(0x26)), contentAlignment = Alignment.Center) {
                BasicText(if (review.authorDeleted) "?" else initial(review.authorName), style = system(14f, FontWeight.SemiBold).copy(color = Ink.white(0xDD)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // The name opens the author's page. A deleted account has no page.
                BasicText(
                    if (review.authorDeleted) stringResource(R.string.Community_DeletedUser) else review.authorName,
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = !review.authorDeleted, role = Role.Button) {
                        onAuthor(review)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A supporter's name is in gold, as on their page.
                    style = system(14f, FontWeight.SemiBold).copy(color = if (review.authorSupporter) Ink.gold else Color.White),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StarRating(review.rating / 2.0, 11f)
                    BasicText(written(review), style = display(11f).copy(color = Ink.white(0x77)))
                }
            }
            // Somebody else's review can be voted on, the reader's own cannot. Never both.
            if (!mine) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Vote(UP, review, 1, stringResource(R.string.Album_VoteUp), onVote)
                    BasicText(
                        review.score.toString(),
                        Modifier.defaultMinSize(minWidth = 16.dp),
                        style = display(13f).copy(color = Ink.white(0xAA), textAlign = TextAlign.Center),
                    )
                    Vote(DOWN, review, -1, stringResource(R.string.Album_VoteDown), onVote)
                }
            }
        }
        if (review.body.isNotEmpty()) {
            BasicText(review.body, style = display(14f).copy(color = Ink.white(0xE6)))
        }
    }
}

@Composable
private fun Vote(glyph: String, review: Review, value: Int, label: String, onVote: (Review, Int) -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .semantics { contentDescription = label }
            .clickable(remember { MutableInteractionSource() }, indication = null) { onVote(review, value) }
            .alpha(if (review.myVote == value) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(13f).copy(color = Ink.white(0xE6)))
    }
}

private fun initial(name: String): String {
    val first = name.firstOrNull() ?: return "?"
    return if (first.isLetterOrDigit()) first.uppercase() else "?"
}

/** When it was written, in words a person would use ("3 days ago"), and whether it was changed since. */
@Composable
private fun written(review: Review): String {
    val stamp = if (review.updatedAt > 0) review.updatedAt else review.createdAt
    if (stamp <= 0) return ""
    val said = DateUtils.getRelativeTimeSpanString(stamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return if (review.updatedAt > review.createdAt) stringResource(R.string.Rpm_Album_Edited, said) else said
}

@Composable
internal fun said(message: Said): String =
    if (message.arg != null) stringResource(message.text, message.arg) else stringResource(message.text)

/**
 * The sheet where the reader gives a record stars and words. It is the iPhone's `WriteReviewSheet`. The stars are
 * repeated here, because a reader who opens the sheet to write may not have rated the record yet, and Save must not
 * depend on a control behind the sheet. Delete is there only when there is a review to delete.
 */
@Composable
fun WriteReviewSheet(
    album: String,
    stars: Double,
    words: String,
    backdrop: Backdrop,
    bottomRoom: Dp,
    onSave: suspend (Double, String) -> Said?,
    onDelete: (suspend () -> Said?)?,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var picked by remember { mutableDoubleStateOf(stars) }
    var text by remember { mutableStateOf(words) }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<Said?>(null) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val shown by animateFloatAsState(if (opened) 1f else 0f, tween(180), label = "review sheet")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .alpha(shown)
            .background(Color.Black.copy(alpha = 0xB3 / 255f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .statusBarsPadding()
            .padding(bottom = bottomRoom)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop,
            Modifier
                .width(minOf(330.dp, maxWidth - 32.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            radius = 18.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(stringResource(R.string.Album_ReviewHeading), style = displayBold(19f).copy(color = Color.White))
                    BasicText(album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = kicker())
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StarRating(picked, 26f, onPick = { picked = it })
                }
                val well = RoundedCornerShape(10.dp)
                BasicTextField(
                    text,
                    onValueChange = { text = it },
                    textStyle = display(15f).copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp + 16.dp)
                        .clip(well)
                        .background(Color.Black.copy(alpha = 0x22 / 255f))
                        .border(1.dp, Ink.cardBorder, well)
                        .padding(13.dp),
                    decorationBox = { field ->
                        Box {
                            if (text.isEmpty()) BasicText(stringResource(R.string.Rpm_Album_ReviewPlaceholder), style = display(15f).copy(color = Ink.white(0x66)))
                            field()
                        }
                    },
                )
                trouble?.let { BasicText(said(it), style = display(12f).copy(color = Color(0xFFFFB4A0))) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(onClick = onClose, modifier = Modifier.weight(1f), height = 42.dp, radius = 10.dp, size = 14f) { style ->
                        BasicText(stringResource(R.string.Cloud_Cancel), style = style)
                    }
                    GlassButton(
                        onClick = {
                            if (busy) return@GlassButton
                            busy = true
                            trouble = null
                            focus.clearFocus()
                            scope.launch {
                                val answer = onSave(picked, text)
                                busy = false
                                if (answer == null) onClose() else trouble = answer
                            }
                        },
                        modifier = Modifier.weight(1f),
                        weight = Weight.Primary,
                        enabled = !busy,
                        height = 42.dp,
                        radius = 10.dp,
                        size = 14f,
                    ) { style ->
                        if (busy) RecordSpinner(18.dp) else BasicText(stringResource(R.string.ArtPlace_Save), style = style)
                    }
                }
                if (onDelete != null) {
                    GlassButton(
                        onClick = {
                            if (busy) return@GlassButton
                            busy = true
                            scope.launch {
                                val answer = onDelete()
                                busy = false
                                if (answer == null) onClose() else trouble = answer
                            }
                        },
                        height = 38.dp,
                        radius = 10.dp,
                        size = 13f,
                    ) { style -> BasicText(stringResource(R.string.Album_DeleteReview), style = style.copy(color = Ink.red)) }
                }
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}
