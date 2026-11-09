package com.rpm.player.phone.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.core.papers.EncyclopediaArticle
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.pt

/** How many lines of an article are shown under a name before the Read more key. */
private const val ABOUT_LINES = 3

/** The width of the fade on the last shown line, before the Read more key. And the gap between the text and the key. */
private val FADE = 28.dp
private val BESIDE_KEY = 6.dp

/** The licence of Wikipedia's text. CC BY-SA requires a reuse to name the licence and link to it. */
private const val LICENCE = "https://creativecommons.org/licenses/by-sa/4.0/"

/**
 * The first paragraph of [article]: what is shown under a name, folded to [ABOUT_LINES] lines or unfolded. The rest of
 * the article is on Wikipedia ([Credit]).
 */
private fun firstParagraph(article: EncyclopediaArticle): String =
    article.extract.split("\n\n").map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/**
 * The encyclopedia text about a record, shown under its name. It matches the iPhone's `AboutText`. Only the first
 * paragraph is shown, folded to its first [ABOUT_LINES] lines: the last line fades out before a Read more key. The key
 * unfolds the paragraph in place ([unfold]): the text grows down from under its last line as the rest fades in, and a
 * Read less key in the same style folds it again. The unfolded text has Wikipedia's credit under it ([Credit]), as has
 * a paragraph short enough to show whole. The text fades in once it is laid out.
 */
@Composable
fun AboutText(article: EncyclopediaArticle, modifier: Modifier = Modifier) {
    val words = remember(article) { firstParagraph(article) }
    var open by rememberSaveable(article.url) { mutableStateOf(false) }
    var laidOut by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (laidOut) 1f else 0f, tween(300), label = "about")
    val style = display(14f).copy(color = Ink.white(0xB3), lineHeight = pt(14f * 1.2f + 7f))

    AnimatedContent(
        open,
        modifier.fillMaxWidth().alpha(shown),
        transitionSpec = { unfold() },
        contentAlignment = Alignment.TopStart,
        label = "about",
    ) { unfolded ->
        if (unfolded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    words,
                    Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { open = false },
                    style = style,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Credit(article, Modifier.weight(1f), Arrangement.Start)
                    AboutKey(R.string.Rpm_About_ReadLess) { open = false }
                }
            }
        } else {
            Folded(words, style, onOpen = { open = true }, onLaidOut = { laidOut = true }, credit = { Credit(article, Modifier.fillMaxWidth(), Arrangement.Start) })
        }
    }
}

/** The paragraph folded to [ABOUT_LINES] lines, its last line fading out before the Read more key; whole, with [credit] under it, when it fits. */
@Composable
private fun Folded(words: String, style: TextStyle, onOpen: () -> Unit, onLaidOut: () -> Unit, credit: @Composable () -> Unit) {
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var key by remember { mutableIntStateOf(0) }
    val clipped = layout?.hasVisualOverflow == true
    val fade = with(density) { FADE.toPx() }
    val gap = with(density) { BESIDE_KEY.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth()) {
            BasicText(
                words,
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = clipped, onClick = onOpen)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val text = layout ?: return@drawWithContent
                        if (!text.hasVisualOverflow) return@drawWithContent
                        // The last shown line fades out, and the space for the key is left clear.
                        val last = text.lineCount - 1
                        val top = text.getLineTop(last)
                        val bottom = text.getLineBottom(last)
                        val room = size.width - key - gap
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = room - fade, endX = room),
                            Offset(room - fade, top),
                            Size(fade, bottom - top),
                            blendMode = BlendMode.DstOut,
                        )
                        drawRect(Color.Black, Offset(room, top), Size(size.width - room, bottom - top), blendMode = BlendMode.DstOut)
                    },
                maxLines = ABOUT_LINES,
                onTextLayout = {
                    layout = it
                    onLaidOut()
                },
                style = style,
            )
            if (clipped) {
                val text = layout
                // Placed level with the middle of the last line.
                val middle = text?.let { (it.getLineTop(it.lineCount - 1) + it.getLineBottom(it.lineCount - 1)) / 2 } ?: 0f
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .onSizeChanged { key = it.width }
                        .offset { IntOffset(0, (middle - with(density) { 12.dp.toPx() }).toInt()) },
                ) {
                    AboutKey(R.string.Rpm_About_ReadMore, onOpen)
                }
            }
        }
        if (layout != null && !clipped) credit()
    }
}

/** Read more and Read less on a record's page: the small glass key. */
@Composable
private fun AboutKey(label: Int, onClick: () -> Unit) {
    GlassButton(onClick = onClick, stretches = false, height = 24.dp, radius = 12.dp, inset = 11.dp, size = 12f) { style ->
        BasicText(stringResource(label), style = style)
    }
}

/**
 * The encyclopedia text about an artist, centred under their name, as the iPhone's `CentredAboutText`. Only the first
 * paragraph is shown, folded to [ABOUT_LINES] lines: it stops at a whole word and "Read more" ends the last line, in bold.
 * The key is joined to the last word with spaces that do not break, so it never starts a line of its own. A tap unfolds
 * the paragraph in place ([unfold]), and "Read less" ends it in the same bold to fold it again. The unfolded text has
 * Wikipedia's credit under it ([Credit]), as has a paragraph short enough to show whole. Text that arrives after the
 * page is drawn comes in through [ArrivingAbout].
 *
 * Where to stop is measured with [rememberTextMeasurer], which lays text out exactly as [BasicText] draws it ([cut]).
 *
 * @param lead words before the text, dimmer than it, which say what it is: "About the album" on a record's page. They
 * are part of the first line and are measured with the text.
 * @param capitalKeys draws Read more and Read less in small capitals, as the record's page does.
 */
@Composable
fun CentredAboutText(article: EncyclopediaArticle, modifier: Modifier = Modifier, lead: String? = null, capitalKeys: Boolean = false) {
    val prefix = lead?.let { it + LEAD_GAP }.orEmpty()
    val words = remember(article, prefix) { prefix + firstParagraph(article) }
    val more = stringResource(R.string.Rpm_About_ReadMore).let { if (capitalKeys) it.uppercase() else it }.replace(' ', '\u00A0')
    val less = stringResource(R.string.Rpm_About_ReadLess).let { if (capitalKeys) it.uppercase() else it }.replace(' ', '\u00A0')
    val measurer = rememberTextMeasurer()
    val body = display(14f).copy(color = Ink.white(0xB3), lineHeight = pt(14f * 1.2f + 7f), textAlign = TextAlign.Center)
    val bold = (if (capitalKeys) displayBold(12f).copy(letterSpacing = pt(0.6f)) else displayBold(14f)).copy(color = Ink.white(0xF0)).toSpanStyle()
    val dim = Dimmed(prefix.length, SpanStyle(color = Ink.white(0x80)))
    var open by rememberSaveable(article.url) { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val folded = remember(words, more, width, body, bold, dim) { cut(measurer, words, more, width, body, bold, dim) }
        val fits = folded.text == words
        AnimatedContent(open && !fits, transitionSpec = { unfold() }, contentAlignment = Alignment.TopCenter, label = "about") { unfolded ->
            val toggle = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, enabled = !fits) { open = !open }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (unfolded) {
                    val whole = remember(words, less, bold, dim) {
                        buildAnnotatedString {
                            dimmed(words, dim)
                            append(KEY_GAP)
                            withStyle(bold) { append(less) }
                        }
                    }
                    BasicText(whole, toggle, style = body)
                } else {
                    BasicText(folded, toggle, maxLines = ABOUT_LINES, style = body)
                }
                if (unfolded || fits) Credit(article, Modifier.fillMaxWidth(), Arrangement.Center)
            }
        }
    }
}

/**
 * The encyclopedia text under a name, brought in the way Read more unfolds it ([unfold]) when it arrives after the page
 * is drawn: its space opens from the top, clipped, as the text fades in, and what is under it moves down with it. Text
 * that is there on the first frame (read before, and kept) is simply there.
 */
@Composable
fun ArrivingAbout(article: EncyclopediaArticle?, content: @Composable (EncyclopediaArticle) -> Unit) {
    // Kept, so the text is still there to draw if it ever leaves.
    var held by remember { mutableStateOf(article) }
    if (article != null) held = article
    AnimatedVisibility(
        visible = article != null,
        enter = expandVertically(tween(UNFOLD_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(UNFOLD_MS)),
        exit = shrinkVertically(tween(UNFOLD_MS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(UNFOLD_MS / 2)),
    ) {
        held?.let { content(it) }
    }
}

/**
 * Folding and unfolding: the box grows or shrinks to the new text's height, clipped as it goes, while the new text fades
 * in over the old. The lines both have stay where they are, so the rest seems to come out from under the last line.
 */
private fun AnimatedContentTransitionScope<Boolean>.unfold(): ContentTransform =
    (fadeIn(tween(UNFOLD_MS)) togetherWith fadeOut(tween(UNFOLD_MS / 2))) using
        SizeTransform(clip = true) { _, _ -> tween(UNFOLD_MS, easing = FastOutSlowInEasing) }

/** How long the text takes to fold or unfold. */
private const val UNFOLD_MS = 320

/**
 * Wikipedia's credit, under text of theirs shown whole: a link to the article and the licence it is shared under, which
 * CC BY-SA asks a reuse to name and link to.
 */
@Composable
private fun Credit(article: EncyclopediaArticle, modifier: Modifier, arrangement: Arrangement.Horizontal) {
    val links = LocalUriHandler.current
    val style = display(11f).copy(color = Ink.white(0x80))
    Row(modifier, horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            stringResource(R.string.TrackInfo_ReadOnWikipedia),
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { links.openUri(article.url) },
            style = style,
        )
        BasicText(CREDIT_GAP, style = style)
        BasicText(
            stringResource(R.string.Wikipedia_Licence),
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { links.openUri(LICENCE) },
            style = style,
        )
    }
}

/** Between the two links of the credit: a middle dot with space round it. */
private const val CREDIT_GAP = "  \u00B7  "

/** Between the lead-in words and the text: a dash with space round it. */
private const val LEAD_GAP = " \u2013 "

/** The first [length] characters of a text drawn in [style]: the lead-in words before the text. */
private data class Dimmed(val length: Int, val style: SpanStyle)

/** [text], its first [dim] characters in their own style. */
private fun AnnotatedString.Builder.dimmed(text: String, dim: Dimmed?) {
    val split = (dim?.length ?: 0).coerceAtMost(text.length)
    if (dim != null && split > 0) withStyle(dim.style) { append(text.substring(0, split)) }
    append(text.substring(split))
}

/** What stands between the last word and the key: four spaces that do not break. */
private const val KEY_GAP = "\u00A0\u00A0\u00A0\u00A0"

/** Three lines never hold this much on a phone, at any text size. Only the start of a long article is searched. */
private const val SEARCHED = 600

/**
 * All of [words] when they fit in [ABOUT_LINES] lines at [width] pixels. Otherwise the longest run of whole words that
 * fits with [key] after it, in [bold].
 */
private fun cut(
    measurer: TextMeasurer,
    words: String,
    key: String,
    width: Int,
    body: TextStyle,
    bold: SpanStyle,
    dim: Dimmed? = null,
): AnnotatedString {
    val room = Constraints(maxWidth = width.coerceAtLeast(1))
    fun lines(text: AnnotatedString) = measurer.measure(text, body, constraints = room).lineCount

    val whole = buildAnnotatedString { dimmed(words, dim) }
    if (width <= 0 || lines(whole) <= ABOUT_LINES) return whole

    val start = words.take(SEARCHED)
    // The ends of the words, found by the platform's word rules, so text with no spaces between words is cut too.
    val ends = mutableListOf<Int>()
    val breaks = java.text.BreakIterator.getWordInstance().apply { setText(start) }
    var from = breaks.first()
    var to = breaks.next()
    while (to != java.text.BreakIterator.DONE) {
        if (start.substring(from, to).any { it.isLetterOrDigit() }) ends += to
        from = to
        to = breaks.next()
    }

    fun ending(end: Int) = buildAnnotatedString {
        dimmed(start.substring(0, end), dim)
        append(KEY_GAP)
        withStyle(bold) { append(key) }
    }

    var low = 0
    var high = ends.size - 1
    var best = -1
    while (low <= high) {
        val middle = (low + high) / 2
        if (lines(ending(ends[middle])) <= ABOUT_LINES) {
            best = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return if (best >= 0) ending(ends[best]) else buildAnnotatedString { withStyle(bold) { append(key) } }
}

