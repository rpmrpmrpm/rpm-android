package com.rpm.player.tv.ui

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.ImageLoader
import coil3.request.Disposable
import com.rpm.player.core.R
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.RecordFacts
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import androidx.compose.animation.core.Easing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The album wall as a carousel, the other way to show it: the covers stand along an arc on a black ground
 * under a soft light, the one in front facing the reader and largest, the rest turned toward it and tilted along the arc as it falls away to both sides. Under it a
 * card names the record in front: its title, its artist, and its year, track count, length and format, centred.
 *
 * It never ends: Right after the last record is the first. Left and Right move it one record, over [TICK_MS], so the
 * next record comes round to the front and the one that was there goes back into the arc; a press while it moves
 * adds one more step to where it is going. A held key turns it at an even [HOLD_RATE] records a second, reached
 * from the first tick's speed, and letting go glides it to rest on the next record at the speed it had: a tick per
 * key repeat started each step again from standing, so the arc lagged further and further behind the presses and
 * then raced through the lag in one tick when the key came up. The covers are stacked by how near the front they are, so the front one
 * is over every other. OK opens the record in front, Up opens the letter wheel and Down the record's menu. Which record is in front is kept for as long as the wall is
 * ([state]), so Back from a record comes back to it.
 */
@Composable
fun AlbumCarousel(
    albums: List<CloudAlbum>,
    order: WallOrder,
    collection: Collection,
    images: ImageLoader,
    backdrop: Backdrop,
    mini: MiniPlayerState,
    state: CarouselState,
    focus: FocusRequester,
    up: FocusRequester,
    onOpen: (CloudAlbum) -> Unit,
    onMenu: (CloudAlbum) -> Unit,
) {
    val count = albums.size
    if (count == 0) return
    val scope = rememberCoroutineScope()
    // Where the carousel stands, in records: whole numbers are a record in front, between them it is moving.
    val at = remember(state) { Animatable(state.front.toFloat()) }
    // Where it is going: the presses so far, which a press during a move adds to.
    var going by remember(state) { mutableIntStateOf(state.front) }
    // The turn while a key is held, frame by frame, and its speed in records a second.
    var holding by remember { mutableStateOf<Job?>(null) }
    var holdSpeed by remember { mutableFloatStateOf(0f) }
    fun stopHolding() {
        holding?.cancel()
        holding = null
    }
    fun step(by: Int) {
        stopHolding()
        going += by
        state.front = going
        scope.launch { at.animateTo(going.toFloat(), tween(TICK_MS, easing = FastOutSlowInEasing)) }
    }
    // The key's repeats: one turn at an even speed, which the repeats only keep going.
    fun hold(by: Int) {
        if (holding?.isActive == true) return
        holding = scope.launch {
            holdSpeed = abs(at.velocity)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = (now - last) / 1e9f
                last = now
                holdSpeed = (holdSpeed + HOLD_RATE / HOLD_RAMP_S * seconds).coerceAtMost(HOLD_RATE)
                at.snapTo(at.value + by * holdSpeed * seconds)
                // The record coming to the front, so its covers are asked for ahead as on a press.
                val next = if (by > 0) ceil(at.value).toInt() else floor(at.value).toInt()
                if (next != going) {
                    going = next
                    state.front = next
                }
            }
        }
    }
    // Let go: on to the next record past a short run-out, slowing evenly from the speed it was turning at.
    fun release(by: Int) {
        if (holding == null) return
        stopHolding()
        val speed = holdSpeed.coerceAtLeast(1f)
        val from = at.value
        going = if (by > 0) ceil(from + speed * RUN_OUT_S).toInt() else floor(from - speed * RUN_OUT_S).toInt()
        state.front = going
        // An ease that slows evenly starts at twice its mean speed, so it takes twice the distance over the speed.
        val ms = (2000f * abs(going - from) / speed).roundToInt().coerceAtLeast(1)
        scope.launch { at.animateTo(going.toFloat(), tween(ms, easing = EvenSlowing)) }
    }
    fun albumAt(slot: Int): CloudAlbum = albums[((slot % count) + count) % count]

    // The letters the wall runs through in this order, each with its first record; none for an order that is not
    // by name, which has no letter wheel.
    val letters = remember(albums, order) { lettersOf(albums, order) }
    var lettersOpen by remember { mutableStateOf(false) }
    // Shown once the covers have been taken for the blur, so the blur is never of another moment.
    var blurred by remember { mutableStateOf(false) }
    val wheel = remember { FocusRequester() }
    var wheelHeld by remember { mutableStateOf(false) }
    // From Up until the wheel holds the focus: taking the blur costs a moment on the Amiko, and Left and Right pressed
    // then move the wheel, not the covers.
    var opening by remember { mutableStateOf(false) }
    val wheelAt = remember(letters) { Animatable(0f) }
    var wheelGoing by remember(letters) { mutableIntStateOf(0) }

    fun openLetters() {
        stopHolding()
        val front = albumAt(going)
        val at = letters.indexOfFirst { it.letter == initialOf(front, order) }.coerceAtLeast(0)
        wheelGoing = at
        wheelHeld = false
        opening = true
        scope.launch {
            wheelAt.snapTo(at.toFloat())
            withFrameNanos { }
            blurred = backdrop.capture()
            lettersOpen = true
            withFrameNanos { }
            runCatching { wheel.requestFocus() }
            opening = false
            if (wheelGoing != at) wheelAt.animateTo(wheelGoing.toFloat(), tween(TICK_MS, easing = FastOutSlowInEasing))
        }
    }

    fun closeLetters(goTo: FocusRequester?) {
        // Forgotten on every close: kept, the next wheel's first report (no focus yet) closed it as it opened.
        wheelHeld = false
        lettersOpen = false
        blurred = false
        goTo?.let { runCatching { it.requestFocus() } }
    }

    // The covers jump to the first record of the letter in front, by the shorter way round, without a slide.
    fun jumpToLetter() {
        val first = letters[((wheelGoing % letters.size) + letters.size) % letters.size].first
        var delta = (((first - going) % count) + count) % count
        if (delta > count / 2) delta -= count
        going += delta
        state.front = going
        scope.launch { at.snapTo(going.toFloat()) }
        closeLetters(focus)
    }

    // The letters cover the mini player while they are up, as the search does.
    // Read once per opening: the dispose must uncover what this effect covered, not ask the state again, which is
    // already false by then and left the mini player covered for good.
    val covering = lettersOpen
    DisposableEffect(covering) {
        if (covering) mini.cover()
        onDispose { if (covering) mini.uncover() }
    }
    BackHandler(enabled = lettersOpen && LocalOnTop.current) { closeLetters(focus) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusProperties { this.up = up }
            .onPreviewKeyEvent { event ->
                // Keys for the letter wheel, which is inside this box, are the wheel's.
                if (lettersOpen) return@onPreviewKeyEvent false
                val key = event.nativeKeyEvent
                if (opening) {
                    if (key.action == KeyEvent.ACTION_DOWN) when (key.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> wheelGoing -= 1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> wheelGoing += 1
                    }
                    return@onPreviewKeyEvent true
                }
                when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val by = if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        when {
                            key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 -> step(by)
                            key.action == KeyEvent.ACTION_DOWN -> hold(by)
                            key.action == KeyEvent.ACTION_UP -> release(by)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> if (letters.isEmpty()) false else { if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) openLetters(); true }
                    // Down opens the record's menu, as a held OK does on a row: there is nothing under the covers to go to.
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onMenu(albumAt(going)); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (key.action == KeyEvent.ACTION_UP) onOpen(albumAt(going))
                        true
                    }
                    else -> false
                }
            }
            .remembersFocus()
            .focusable(),
    ) {
        val wide = maxWidth
        val high = maxHeight
        val edge = (high * CARD_SHARE)
        val middleY = high * ARC_MIDDLE
        val radius = wide * ARC_RADIUS

        // The covers within [AHEAD] records of where the carousel is going, decoded into memory at the size a cover
        // asks for, nearest first, so a cover coming round is drawn whole on its first frame ([CoverImage]). A cover
        // that falls out of reach has its request cancelled if it is not in yet.
        val context = LocalContext.current
        val edgePx = with(LocalDensity.current) { edge.roundToPx() }
        LaunchedEffect(albums, edgePx) {
            val asked = HashMap<Any, Disposable>()
            try {
                snapshotFlow { going }.collect { centre ->
                    val wanted = coversAround(collection, albums, centre)
                    asked.keys.filter { it !in wanted }.forEach { asked.remove(it)?.dispose() }
                    for (model in wanted) {
                        if (model !in asked) asked[model] = images.enqueue(aheadRequest(context, model, edgePx, edgePx))
                    }
                }
            } finally {
                asked.values.forEach { it.dispose() }
            }
        }

        // The ground: black, a soft light behind the front cover, and the curved floor the covers stand on.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black)
            val light = Offset(size.width / 2, middleY.toPx() - edge.toPx() * 0.1f)
            drawCircle(
                Brush.radialGradient(0f to Color(0x66FFFFFF), 0.35f to Color(0x24FFFFFF), 1f to Color.Transparent, center = light, radius = size.width * 0.32f),
                radius = size.width * 0.32f,
                center = light,
            )
            // The floor: a wide ellipse whose top edge runs under the covers and falls away to both sides, lit
            // faintly along that edge.
            val floorTop = middleY.toPx() + edge.toPx() * 0.42f
            // A circle of the arc's own radius, so the floor's edge runs under the covers' feet as they fall away.
            val floorWide = radius.toPx() * 2
            val floorHigh = floorWide
            val floor = Offset((size.width - floorWide) / 2, floorTop)
            drawOval(Brush.verticalGradient(0f to Color(0xFF2A2A2A), 0.04f to Color(0xFF0C0C0C), 0.3f to Color.Black, startY = floorTop, endY = floorTop + floorHigh), floor, Size(floorWide, floorHigh))
        }

        val position = at.value
        val base = floor(position).toInt()
        for (j in -SLOTS..SLOTS + 1) {
            val slot = base + j
            val k = slot - position
            if (abs(k) > SLOTS + 0.5f) continue
            key(slot) {
                val album = albumAt(slot)
                val angle = k * STEP_DEGREES
                val radians = angle * PI.toFloat() / 180f
                val near = minOf(abs(k), 1f)
                val scale = 1f - near * 0.16f - maxOf(abs(k) - 1f, 0f) * 0.035f
                val fade = (SLOTS + 0.5f - abs(k)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        // Above the ground (drawn at 0), the front cover highest.
                        .zIndex(SLOTS + 1 - abs(k))
                        .size(edge)
                        .offset(x = (wide - edge) / 2, y = middleY - edge / 2)
                        .graphicsLayer {
                            translationX = radius.toPx() * sin(radians)
                            translationY = radius.toPx() * (1 - cos(radians))
                            rotationZ = angle * TILT
                            rotationY = -sign(k) * near * TURN_DEGREES
                            cameraDistance = 14f * density
                            scaleX = scale
                            scaleY = scale
                            alpha = fade
                        }
                        .shadow(18.dp, clip = false)
                        .background(Color(0xFF1A1A1A))
                        .border(1.px, Color(0x33FFFFFF)),
                ) {
                    collection.cover(album)?.let { CoverImage(it, images, ContentScale.Crop, Modifier.matchParentSize(), onShown = {}) }
                    // Every cover but the front one at home's [CARD_LIGHT], brightening as it comes round.
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (1f - CARD_LIGHT) * near)))
                }
            }
        }

        // The record in front, named under the arc.
        val front = albumAt(position.roundToInt())
        FrontCard(front, Modifier.zIndex(SLOTS + 2f).align(Alignment.TopCenter).offset(y = middleY + edge / 2 + INFO_GAP.px))

        if (lettersOpen && blurred) {
            Box(Modifier.zIndex(SLOTS + 3f).fillMaxSize()) {
                GlassBackdrop(backdrop, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            }
            LetterWheel(
                letters = letters,
                at = wheelAt.value,
                tile = high * LETTER_SHARE,
                middleY = high * LETTER_MIDDLE,
                wide = wide,
                radius = radius,
                modifier = Modifier
                    .zIndex(SLOTS + 4f)
                    .fillMaxSize()
                    .focusRequester(wheel)
                    .focusProperties { this.up = up }
                    // Leaving the wheel (Up to the keys) hides it. Only after it has held the focus: its first report,
                    // as it appears, says it has none.
                    .onFocusChanged {
                        if (it.hasFocus) wheelHeld = true
                        else if (wheelHeld && lettersOpen) { wheelHeld = false; closeLetters(null) }
                    }
                    .onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        fun stepLetters(by: Int) {
                            wheelGoing += by
                            scope.launch { wheelAt.animateTo(wheelGoing.toFloat(), tween(TICK_MS, easing = FastOutSlowInEasing)) }
                        }
                        when (key.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { if (key.action == KeyEvent.ACTION_DOWN) stepLetters(-1); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (key.action == KeyEvent.ACTION_DOWN) stepLetters(1); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { if (key.action == KeyEvent.ACTION_DOWN) jumpToLetter(); true }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (key.action == KeyEvent.ACTION_UP) jumpToLetter()
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable(),
            )
        }
    }
}

/**
 * The carousel's first covers decoded into memory before the albums screen is opened: the ones within [AHEAD]
 * records of the first record of the wall in [order], at the window's height times [CARD_SHARE], which is at least the size a cover asks for (a larger picture
 * in memory serves a smaller request). Waits [WARM_WAIT_MS] first, so home's own pictures are asked for before these.
 * Nothing is cancelled: what comes in stays in memory for the carousel.
 */
@Composable
fun CarouselWarmUp(collection: Collection, order: WallOrder, images: ImageLoader) {
    val context = LocalContext.current
    val edge = (LocalWindowInfo.current.containerSize.height * CARD_SHARE).roundToInt()
    LaunchedEffect(collection, order, edge) {
        if (edge <= 0) return@LaunchedEffect
        delay(WARM_WAIT_MS)
        val albums = withContext(Dispatchers.Default) { wallOf(collection, "", order, favourites = false) }
        if (albums.isEmpty()) return@LaunchedEffect
        for (model in coversAround(collection, albums, 0)) images.enqueue(aheadRequest(context, model, edge, edge))
    }
}

/** The covers of the records within [AHEAD] places of [centre] in [albums], taken round the wall, nearest first, each
 * once. */
private fun coversAround(collection: Collection, albums: List<CloudAlbum>, centre: Int): Set<Any> {
    val count = albums.size
    val covers = LinkedHashSet<Any>()
    for (d in 0..AHEAD) {
        for (slot in intArrayOf(centre + d, centre - d)) {
            collection.cover(albums[((slot % count) + count) % count])?.let(covers::add)
        }
    }
    return covers
}

/** One letter of the wheel and the first record under it, by its place in the wall. */
class WallLetter(val letter: String, val first: Int)

/** The letter a record is filed under in [order]: the first letter of its title or its artist as the wall sorts them
 * ([fold]), in capitals; "#" for one that starts with anything else. */
fun initialOf(album: CloudAlbum, order: WallOrder): String {
    // By date the wheel is the decades: 1970, 1980, and "#" for a record with no year.
    if (order == WallOrder.Date) return RecordFacts.year(album.tracks)?.let { (it / 10 * 10).toString() } ?: "#"
    // Only the first characters are folded: the letter is the first one's, and folding a whole name for every record
    // on each new order holds the screen for seconds on a low-end TV box.
    val name = fold((if (order == WallOrder.Album) album.album else album.artist).take(INITIAL_CHARS))
    val c = name.firstOrNull() ?: return "#"
    return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
}

/** The letters [albums] run through in [order], in the order they first come, each with its first record; none
 * unless the wall is sorted by album or by artist. */
fun lettersOf(albums: List<CloudAlbum>, order: WallOrder): List<WallLetter> {
    if (order != WallOrder.Album && order != WallOrder.Artist && order != WallOrder.Date) return emptyList()
    val seen = LinkedHashMap<String, Int>()
    albums.forEachIndexed { i, album -> seen.putIfAbsent(initialOf(album, order), i) }
    return seen.map { (letter, first) -> WallLetter(letter, first) }
}

/**
 * The letters as the covers stand, in the same carousel style: tiles of the app's pale glass along the same
 * arc, smaller and closer, the front one at full brightness and the rest at [CARD_LIGHT], never ending. [at] is where the wheel stands, in letters.
 */
@Composable
private fun LetterWheel(letters: List<WallLetter>, at: Float, tile: androidx.compose.ui.unit.Dp, middleY: androidx.compose.ui.unit.Dp, wide: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val count = letters.size
    Box(modifier) {
        val base = floor(at).toInt()
        val shape = RoundedCornerShape(12.px)
        for (j in -LETTER_SLOTS..LETTER_SLOTS + 1) {
            val slot = base + j
            val k = slot - at
            if (abs(k) > LETTER_SLOTS + 0.5f) continue
            key(slot) {
                val letter = letters[((slot % count) + count) % count].letter
                val angle = k * LETTER_STEP_DEGREES
                val radians = angle * PI.toFloat() / 180f
                val near = minOf(abs(k), 1f)
                val scale = 1f - near * 0.16f - maxOf(abs(k) - 1f, 0f) * 0.03f
                Box(
                    Modifier
                        .zIndex(LETTER_SLOTS + 1 - abs(k))
                        .size(tile)
                        .offset(x = (wide - tile) / 2, y = middleY - tile / 2)
                        .graphicsLayer {
                            translationX = radius.toPx() * sin(radians)
                            translationY = radius.toPx() * (1 - cos(radians))
                            rotationZ = angle * TILT
                            rotationY = -sign(k) * near * TURN_DEGREES
                            cameraDistance = 14f * density
                            scaleX = scale
                            scaleY = scale
                            alpha = (LETTER_SLOTS + 0.5f - abs(k)).coerceIn(0f, 1f)
                        }
                        .shadow(12.dp, shape, clip = false)
                        .background(Color(0xFF2A2A2E), shape)
                        .border(1.px, Color(0x40FFFFFF), shape),
                    contentAlignment = Alignment.Center,
                ) {
                    // A decade is four figures, set smaller to fit the tile.
                    Text(letter, style = ui(if (letter.length > 2) 30.ts else 52.ts, FontWeight.SemiBold), color = Ink.Words)
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (1f - CARD_LIGHT) * near), shape))
                }
            }
        }
    }
}

/** The card under the carousel: the record's title, artist, and its year, tracks, length and format, centred, on the
 * app's pale glass (`#1FFFFFFF`, a `#26FFFFFF` edge, corner 12). */
@Composable
private fun FrontCard(album: CloudAlbum, modifier: Modifier) {
    val shape = RoundedCornerShape(12.px)
    val facts = remember(album) {
        val year = RecordFacts.year(album.tracks)
        val format = RecordFacts.format(album.tracks)
        val length = album.tracks.sumOf { (it.tags?.durationSeconds ?: 0.0) * 1000 }.toLong()
        Triple(year, format, length)
    }
    Column(
        modifier
            .widthIn(min = 520.px, max = 900.px)
            .background(Color(0x1FFFFFFF), shape)
            .border(1.px, Color(0x26FFFFFF), shape)
            .padding(horizontal = 32.px, vertical = 16.px),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.px),
    ) {
        Text(albumTitle(album), style = ui(26.ts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(albumArtist(album), style = ui(PAGE_TEXT), color = Color(0xD9FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        val (year, format, length) = facts
        Row(horizontalArrangement = Arrangement.spacedBy(6.px)) {
            val line = Color(0xB0FFFFFF)
            if (year != null) {
                Text(year.toString(), style = ui(17.ts), color = line)
                Text("·", style = ui(17.ts), color = line)
            }
            Text(stringResource(R.string.Rpm_Album_Tracks, album.tracks.size.toString()), style = ui(17.ts), color = line)
            Text("·", style = ui(17.ts), color = line)
            Text(clockOf(length), style = ui(17.ts), color = line)
            if (format != null) {
                Text("·", style = ui(17.ts), color = line)
                Text(listOfNotNull(format.format, format.quality).joinToString(" "), style = ui(17.ts), color = line)
            }
        }
    }
}

/** Which record of the wall is in front of the carousel, counted without end (it is taken modulo the wall's size), kept
 * while the wall is. */
class CarouselState {
    var front: Int = 0
}

/** How long one step of the carousel takes: a slow tick. */
private const val TICK_MS = 520

/** How fast a held key turns the carousel, in records a second, and how long it takes to reach that from standing. */
private const val HOLD_RATE = 7f
private const val HOLD_RAMP_S = 0.35f

/** How far past where it stands a let-go turn runs on, in seconds at the speed it had, before the record it rests on. */
private const val RUN_OUT_S = 0.12f

/** Slows evenly to a stop: the curve's slope starts at 2 and falls in a straight line to 0. */
private val EvenSlowing = Easing { t -> 1f - (1f - t) * (1f - t) }

/** How many characters of a name are folded to find its letter: a letter and the marks that may follow it. */
private const val INITIAL_CHARS = 4

/** How many covers stand to each side of the front one. */
private const val SLOTS = 5

/** How many records to each side of the front one have their covers decoded ahead: those on show and eight more. */
private const val AHEAD = SLOTS + 8

/** How long after the library is ready the carousel's first covers are asked for ([CarouselWarmUp]). */
private const val WARM_WAIT_MS = 4_000L

/** The angle between two covers along the arc, how far each tilts along it (a share of that angle), and how far a
 * cover beside the front one is turned toward it. */
private const val STEP_DEGREES = 5.8f
private const val TILT = 0.81f
private const val TURN_DEGREES = 32f

/** The cover's edge as a share of the screen's height, where the front cover's middle stands, and the arc's
 * radius as a share of the screen's width. */
private const val CARD_SHARE = 0.34f
private const val ARC_MIDDLE = 0.43f
private const val ARC_RADIUS = 0.9f

/** The letter tiles' edge as a share of the screen's height, where the front tile's middle stands, how many stand to
 * each side, and the angle between two along the arc. */
private const val LETTER_SHARE = 0.12f
private const val LETTER_MIDDLE = 0.17f
private const val LETTER_SLOTS = 7
private const val LETTER_STEP_DEGREES = 4.6f

/** The gap between the front cover's foot and the card under it. */
private const val INFO_GAP = 110
