package com.rpm.player.tv.ui

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.rpm.player.core.R
import com.rpm.player.core.Region
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The set-up after a first sign-in, over the app while the collection loads and home is built
 * under it, so everything is in place when the person is done. On the desktop's default backdrop (RPaMp's):
 *
 * 0. the sign-in confirmed, "You're signed in as …", and "Continue set up";
 * 1. colours and resolution: the same photograph in each [PictureLook], and the resolutions this box offers;
 * 2. the player's default layout ([PegasusView]), each drawn as a sketch;
 * 3. the albums screen as a grid or a carousel, each drawn as a sketch;
 * 4. country (for local radio, [Region.country]) and interface language ([Region.language]); picking a language
 *    draws the screens again in it;
 * 5. for an account that does not support rpm yet, what supporters get, the price and a code for the website's page
 *    to become one (a TV takes no payments); for one that does, a thank-you ([supporter]);
 * 6. "All set!" and "Welcome to rpm." for [ALL_SET_MS], then [onDone].
 *
 * Each choice is stored as it is picked, in the same settings the settings panel changes. Back goes a screen back.
 * The resolution is applied by [onDone]'s caller, after the last screen, so the screens are not drawn again
 * half-way through.
 */
@Composable
fun FirstSetup(
    name: String,
    settings: PlayerSettings,
    region: Region,
    guessedCountry: String?,
    /** Whether the account supports rpm; null until read, which shows the plan. */
    supporter: Boolean?,
    onDone: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    SetupMusic()
    BackHandler(enabled = step in 1..5) { step-- }
    val first = remember(step) { FocusRequester() }

    // The focus is kept inside: the app is being built under the set-up, and a key that left it would land on home.
    Box(
        Modifier
            .fillMaxSize()
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        SetupGround()
        when (step) {
            0 -> SetupPage(title = null, hint = null) {
                Box(Modifier.size(110.s).background(Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(Fa.CHECK, 48.ts, Ink.Words)
                }
                Spacer(Modifier.height(30.s))
                Text(stringResource(R.string.Tv_SignIn_Done, name), style = ui(34.ts, FontWeight.SemiBold), color = Ink.Words, textAlign = TextAlign.Center)
                Spacer(Modifier.height(44.s))
                SetupButton(stringResource(R.string.Tv_Setup_Continue), onClick = { step = 1 }, modifier = Modifier.focusRequester(first))
            }
            1 -> SetupPage(stringResource(R.string.Tv_Setup_PictureTitle), stringResource(R.string.Tv_Setup_PictureHint)) {
                val sample = rememberSample()
                Row(horizontalArrangement = Arrangement.spacedBy(24.s)) {
                    for (look in PictureLook.entries) {
                        ChoiceCard(
                            label = stringResource(look.label),
                            selected = settings.picture == look,
                            onClick = { settings.pickPicture(look) },
                            modifier = if (settings.picture == look) Modifier.focusRequester(first) else Modifier,
                        ) {
                            if (sample != null) {
                                // The photograph is stored mirrored (its shop sign reads backwards); drawn the right way round.
                                Image(sample, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = look.filter, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = -1f })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(30.s))
                Text(stringResource(R.string.Tv_SettingsResolution), style = ui(20.ts, FontWeight.SemiBold), color = Ink.Artist)
                Spacer(Modifier.height(12.s))
                Row(horizontalArrangement = Arrangement.spacedBy(12.s)) {
                    for (choice in settings.resolutionChoices) {
                        Pill(resolutionLabel(choice, settings.outputHeight), settings.resolution == choice, onClick = { settings.pickResolution(choice) })
                    }
                }
                Spacer(Modifier.height(36.s))
                SetupButton(stringResource(R.string.Tv_Setup_Next), onClick = { step = 2 }, modifier = Modifier)
            }
            2 -> SetupPage(stringResource(R.string.Tv_Setup_ViewTitle), stringResource(R.string.Tv_Setup_ViewHint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.s)) {
                    for (view in listOf(PegasusView.Spread, PegasusView.Centred)) {
                        ChoiceCard(
                            label = stringResource(view.label),
                            selected = settings.defaultView == view,
                            onClick = { settings.pickDefaultView(view) },
                            modifier = if (settings.defaultView == view) Modifier.focusRequester(first) else Modifier,
                        ) { LayoutSketch(view) }
                    }
                }
                Spacer(Modifier.height(44.s))
                SetupButton(stringResource(R.string.Tv_Setup_Next), onClick = { step = 3 }, modifier = Modifier)
            }
            3 -> SetupPage(stringResource(R.string.Tv_Setup_LibraryTitle), stringResource(R.string.Tv_Setup_LibraryHint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.s)) {
                    for (carousel in listOf(false, true)) {
                        ChoiceCard(
                            label = stringResource(if (carousel) R.string.Tv_AlbumViewCarousel else R.string.Tv_AlbumViewGrid),
                            selected = settings.albumCarousel == carousel,
                            onClick = { if (settings.albumCarousel != carousel) settings.switchAlbumCarousel() },
                            modifier = if (settings.albumCarousel == carousel) Modifier.focusRequester(first) else Modifier,
                        ) { LibrarySketch(carousel) }
                    }
                }
                Spacer(Modifier.height(44.s))
                SetupButton(stringResource(R.string.Tv_Setup_Next), onClick = { step = 4 }, modifier = Modifier)
            }
            4 -> SetupPage(stringResource(R.string.Tv_Setup_RegionTitle), stringResource(R.string.Tv_Setup_RegionHint)) {
                RegionPicker(region, guessedCountry, first)
                Spacer(Modifier.height(30.s))
                SetupButton(stringResource(R.string.Tv_Setup_Next), onClick = { step = 5 }, modifier = Modifier)
            }
            5 -> if (supporter == true) {
                SetupPage(title = null, hint = null) {
                    SupporterMark(22f, Modifier)
                    Spacer(Modifier.height(30.s))
                    Text(stringResource(R.string.Tv_Setup_Thanks), style = ui(34.ts, FontWeight.SemiBold), color = Ink.Words, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(44.s))
                    SetupButton(stringResource(R.string.Tv_Setup_Next), onClick = { step = 6 }, modifier = Modifier.focusRequester(first))
                }
            } else {
                SetupPage(stringResource(R.string.Supporter_OfferTitle), stringResource(R.string.Supporter_Price)) {
                    SupporterOffer()
                    Spacer(Modifier.height(30.s))
                    SetupButton(stringResource(R.string.Supporter_Later), onClick = { step = 6 }, modifier = Modifier.focusRequester(first))
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.Tv_Setup_AllSet), style = ui(44.ts, FontWeight.Bold), color = Ink.Words, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.s))
                    Text(stringResource(R.string.Tv_Setup_Welcome), style = ui(26.ts), color = Ink.Artist, textAlign = TextAlign.Center)
                    // Holds the remote here, so no key reaches the screens being built under it.
                    DeskButton(onClick = {}, corner = 7.s, modifier = Modifier.focusRequester(first).size(1.px)) {}
                }
                LaunchedEffect(Unit) {
                    delay(ALL_SET_MS)
                    onDone()
                }
            }
        }
    }
    // A frame first: the screen just put up has to be laid out before its key can take the focus, and the key that
    // had it went with the screen before, leaving the remote nothing to move from.
    LaunchedEffect(step) {
        repeat(3) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * Plays [SetupLoop] while the set-up is on screen and the activity is started. The activity being made again for a
 * language pick neither pauses nor stops it; any other leaving stops it.
 */
@Composable
private fun SetupMusic() {
    val context = LocalContext.current
    // The activity, which the screens' own context is not when they are drawn on SharpHost's display.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val remade = { (owner as? Activity)?.isChangingConfigurations == true }
        // Added while the activity is started, it is handed ON_START at once.
        val watch = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> SetupLoop.play(context)
                Lifecycle.Event.ON_STOP -> if (!remade()) SetupLoop.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(watch)
        onDispose {
            owner.lifecycle.removeObserver(watch)
            if (!remade()) SetupLoop.stop()
        }
    }
}

/**
 * The set-up's music, a soft loop for the screens: `res/raw/setup_loop.mp3`, looped, at half
 * volume, from the first set-up screen to the end of "All set!", where it fades out over [FADE_OUT_MS]. The file fades
 * in and out within itself, so the loop point is quiet even though the MP3 carries no gapless header.
 *
 * Held for the process rather than by the screen: picking a language makes the activity again, and the music goes on
 * through that ([SetupMusic]). What it plays from is a fact asked each time, not a sequence remembered: [play] and
 * [pause] set whether music is wanted, and the player, ready or not yet, follows that when it can. Main thread.
 */
private object SetupLoop {
    private var player: MediaPlayer? = null
    private var ready = false
    private var wanted = false
    private val fades = Handler(Looper.getMainLooper())

    fun play(context: Context) {
        wanted = true
        val held = player ?: open(context) ?: return
        held.setVolume(VOLUME, VOLUME)
        if (ready && !held.isPlaying) held.start()
    }

    fun pause() {
        wanted = false
        player?.takeIf { ready && it.isPlaying }?.pause()
    }

    /** Fades out and lets the player go. */
    fun stop() {
        wanted = false
        val held = player ?: return
        player = null
        if (!ready || !held.isPlaying) {
            held.release()
            ready = false
            return
        }
        ready = false
        // Each fade holds its own player and releases it at its end, so none is cut off and left playing.
        val steps = FADE_OUT_MS / FADE_STEP_MS
        for (i in 1..steps) {
            fades.postDelayed({
                val level = VOLUME * (1f - i.toFloat() / steps)
                held.setVolume(level, level)
                if (i == steps) held.release()
            }, (i * FADE_STEP_MS).toLong())
        }
    }

    private fun open(context: Context): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            context.resources.openRawResourceFd(com.rpm.player.tv.R.raw.setup_loop).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            isLooping = true
            setVolume(VOLUME, VOLUME)
            setOnPreparedListener { prepared ->
                // Only the player still held: one let go while it was getting ready is not started.
                if (prepared !== player) return@setOnPreparedListener
                ready = true
                if (wanted) prepared.start()
            }
            prepareAsync()
        }.also { player = it }
    } catch (e: Exception) {
        android.util.Log.w("rpm.setup", "set-up music not played: $e")
        null
    }

    /** Half volume. */
    private const val VOLUME = 0.5f
    private const val FADE_OUT_MS = 1_200
    private const val FADE_STEP_MS = 40
}

/** The website's page for becoming a supporter, as the desktop's supporter dialog opens it. */
private const val SUPPORTER_PAGE = "https://rpmrpmrpm.com/become-supporter"

/** What supporters get, each with the desktop's glyph (SupporterPerks.cs), beside the code for [SUPPORTER_PAGE]. */
@Composable
private fun SupporterOffer() {
    val perks = listOf(
        R.string.Supporter_PerkStorage to '',
        R.string.Supporter_PerkBitrate to '',
        R.string.Supporter_PerkRecognition to '',
        R.string.Supporter_PerkDj to '',
        R.string.Supporter_PerkKaraoke to '',
        R.string.Supporter_PerkBackdrop to '',
        R.string.Supporter_PerkBadge to '',
        R.string.Supporter_PerkCommunity to '',
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(60.s)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.s)) {
            for ((text, glyph) in perks) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(36.s), contentAlignment = Alignment.Center) { Glyph(glyph, 18.ts, Color(0xFFE8C77A)) }
                    Spacer(Modifier.width(12.s))
                    Text(stringResource(text), style = ui(20.ts), color = Ink.Words)
                }
            }
            Text(stringResource(R.string.Supporter_PerkMore), style = ui(18.ts), color = Ink.Artist, modifier = Modifier.padding(start = 48.s))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(260.s).background(Color.White, RoundedCornerShape(12.s)).padding(14.s)) {
                val code = remember { qrBitmap(SUPPORTER_PAGE) }
                Image(code, contentDescription = null, modifier = Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            }
            Spacer(Modifier.height(14.s))
            Text(stringResource(R.string.Tv_Setup_SupporterScan), style = ui(17.ts), color = Ink.Artist, textAlign = TextAlign.Center, modifier = Modifier.width(300.s))
        }
    }
}


/** How long "All set!" stands before the app opens. */
private const val ALL_SET_MS = 5_000L

/** The sign-in and set-up screens' ground: the desktop's default backdrop, shaded as the collection screens are. */
@Composable
fun SetupGround() {
    BackdropPicture(PageBackground.Default, dim = 1f - Ink.Scrim.alpha, onShown = {}, modifier = Modifier.fillMaxSize())
}

/** One set-up screen: the title at the app's largest size, the hint under it, then [content], centred. */
@Composable
private fun SetupPage(title: String?, hint: String?, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 60.s)) {
        if (title != null) Text(title, style = ui(44.ts, FontWeight.Bold), color = Ink.Words, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(10.s))
            Text(hint, style = ui(22.ts), color = Ink.Artist, textAlign = TextAlign.Center)
        }
        if (title != null || hint != null) Spacer(Modifier.height(36.s))
        content()
    }
}

/** The set-up's key: `340·s` by `64·s`, `#1FFFFFFF` (lit `#40FFFFFF`) with a 1 px `#B3FFFFFF` rim, corner 7·s. */
@Composable
private fun SetupButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    var lit by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.s)
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .width(340.s)
            .height(64.s)
            .background(if (lit) Color(0x40FFFFFF) else Color(0x1FFFFFFF), shape)
            .border(1.px, Color(0xB3FFFFFF), shape),
    ) {
        Text(text, style = ui(22.ts, FontWeight.SemiBold), color = Ink.Words)
    }
}

/** A choice with a picture: [preview] in a `320·s` by `180·s` frame, its name under it with a tick when it is the
 * one chosen, and a white rim round the frame on the chosen one. */
@Composable
private fun ChoiceCard(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, preview: @Composable () -> Unit) {
    val frame = RoundedCornerShape(10.s)
    DeskButton(onClick = onClick, corner = 14.s, modifier = modifier.padding(10.s)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(320.s, 180.s)
                    .clip(frame)
                    .background(Color(0xFF0B1424))
                    .border(if (selected) 3.px else 1.px, if (selected) Color.White else Color(0x40FFFFFF), frame),
            ) { preview() }
            Spacer(Modifier.height(12.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Glyph(Fa.CHECK, 15.ts, Ink.Words)
                    Spacer(Modifier.width(8.s))
                }
                Text(label, style = ui(21.ts, FontWeight.SemiBold), color = if (selected) Ink.Words else Ink.Artist)
            }
        }
    }
}

/** A choice without a picture: its name on a pill, white with dark text when chosen. */
@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(30.s)
    DeskButton(
        onClick = onClick,
        corner = 30.s,
        modifier = Modifier
            .height(48.s)
            .background(if (selected) Color.White else Color(0x1FFFFFFF), shape)
            .border(1.px, Color(0x59FFFFFF), shape)
            .padding(horizontal = 22.s),
    ) {
        Text(label, style = ui(19.ts, FontWeight.SemiBold), color = if (selected) Color(0xFF111111) else Ink.Words)
    }
}

/** The photograph the colours are judged on: bright reds, yellows, blues and skin, from the genre pictures. */
@Composable
private fun rememberSample(): ImageBitmap? {
    val context = LocalContext.current
    return remember {
        runCatching { context.assets.open("genre-photos/latin.jpg").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }
}

private val PictureLook.label: Int
    get() = when (this) {
        PictureLook.Standard -> R.string.Tv_PictureStandard
        PictureLook.Softer -> R.string.Tv_PictureSofter
        PictureLook.Muted -> R.string.Tv_PictureMuted
    }

private val SKETCH_LINE = Color(0xB3FFFFFF)
private val SKETCH_DIM = Color(0x4DFFFFFF)
private val SKETCH_COVER = Brush.linearGradient(listOf(Color(0xFFE0457B), Color(0xFF6A3DE8)))

/** A sketch of a player layout: Full has the record at the left, its lines beside it and the spectrum under them;
 * Compact the record centred with its lines under it. */
@Composable
private fun LayoutSketch(view: PegasusView) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(Color(0xFF27406E), Color(0xFF0B1424)), center = Offset(w * 0.5f, h * 0.4f), radius = w * 0.7f))
        fun bars(top: Float, bottom: Float, from: Float, to: Float) {
            val n = 28
            val step = (to - from) / n
            for (i in 0 until n) {
                val t = (kotlin.math.sin(i * 0.7f) * 0.5f + 0.5f) * 0.7f + 0.3f
                val barH = (bottom - top) * t
                drawRoundRect(SKETCH_DIM, Offset(from + i * step + step * 0.2f, bottom - barH), Size(step * 0.6f, barH), CornerRadius(2f))
            }
        }
        when (view) {
            PegasusView.Spread -> {
                val side = h * 0.46f
                drawRoundRect(SKETCH_COVER, Offset(w * 0.1f, h * 0.12f), Size(side, side), CornerRadius(6f))
                val x = w * 0.1f + side + w * 0.06f
                drawRoundRect(SKETCH_LINE, Offset(x, h * 0.2f), Size(w * 0.38f, h * 0.06f), CornerRadius(4f))
                drawRoundRect(SKETCH_DIM, Offset(x, h * 0.32f), Size(w * 0.28f, h * 0.045f), CornerRadius(4f))
                drawRoundRect(SKETCH_DIM, Offset(x, h * 0.42f), Size(w * 0.22f, h * 0.045f), CornerRadius(4f))
                bars(h * 0.68f, h * 0.9f, w * 0.1f, w * 0.9f)
            }
            PegasusView.Centred -> {
                val side = h * 0.5f
                drawRoundRect(SKETCH_COVER, Offset((w - side) / 2, h * 0.1f), Size(side, side), CornerRadius(6f))
                drawRoundRect(SKETCH_LINE, Offset(w * 0.32f, h * 0.68f), Size(w * 0.36f, h * 0.06f), CornerRadius(4f))
                drawRoundRect(SKETCH_DIM, Offset(w * 0.38f, h * 0.79f), Size(w * 0.24f, h * 0.045f), CornerRadius(4f))
            }
        }
    }
}

/** A sketch of the albums screen: the grid's rows of covers, or the carousel's covers on an arc, the middle one
 * large and the ones beside it smaller and dimmer. */
@Composable
private fun LibrarySketch(carousel: Boolean) {
    val covers = listOf(Color(0xFFE0457B), Color(0xFF6A3DE8), Color(0xFF33C3FF), Color(0xFFF2A93B), Color(0xFF3BD18B), Color(0xFFB04BE0))
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0B1424))
        if (!carousel) {
            val cols = 5
            val gap = w * 0.03f
            val side = (w - gap * (cols + 1)) / cols
            for (row in 0 until 2) for (col in 0 until cols) {
                val y = gap * 2 + row * (side + gap * 1.6f)
                drawRoundRect(covers[(row * cols + col) % covers.size], Offset(gap + col * (side + gap), y), Size(side, side), CornerRadius(4f))
                drawRoundRect(SKETCH_DIM, Offset(gap + col * (side + gap), y + side + gap * 0.3f), Size(side * 0.7f, gap * 0.5f), CornerRadius(2f))
            }
        } else {
            val centre = h * 0.62f
            for (k in listOf(-2, 2, -1, 1, 0)) {
                val scale = when (kotlin.math.abs(k)) { 0 -> 1f; 1 -> 0.72f; else -> 0.52f }
                val side = centre * scale
                val cx = w / 2 + k * w * 0.2f
                val cy = h * 0.42f + kotlin.math.abs(k) * h * 0.04f
                val light = when (kotlin.math.abs(k)) { 0 -> 1f; 1 -> 0.55f; else -> 0.3f }
                drawRoundRect(covers[(k + 2) % covers.size].copy(alpha = light), Offset(cx - side / 2, cy - side / 2), Size(side, side), CornerRadius(5f))
            }
            drawRoundRect(SKETCH_LINE, Offset(w * 0.36f, h * 0.84f), Size(w * 0.28f, h * 0.05f), CornerRadius(4f))
        }
    }
}

/**
 * Country and language side by side: every country by its name in the interface's language, the one chosen (or the
 * radio's guess) ticked and scrolled to; and the interface's languages each by its own name. A country is stored as
 * it is picked; a language is stored and the activity made again, so these screens are drawn in it at once.
 */
@Composable
private fun RegionPicker(region: Region, guessedCountry: String?, first: FocusRequester) {
    val context = LocalContext.current
    // The activity through its lifecycle: on SharpHost's display the screens' own context is not the activity, and a
    // language pick could not recreate it.
    val activity = LocalLifecycleOwner.current as? Activity
    val shown = context.resources.configuration.locales[0]
    var country by remember { mutableStateOf(region.country ?: guessedCountry) }
    val countries = remember(shown) {
        val collator = Collator.getInstance(shown)
        Locale.getISOCountries().map { code -> code to Locale.Builder().setRegion(code).build().getDisplayCountry(shown) }
            .filter { it.second.isNotBlank() }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
    val language = remember(shown) { Region.languageOf(context) }
    // Opened on the chosen country, and again in each language, whose names sort differently.
    val list = remember(shown) { androidx.compose.foundation.lazy.LazyListState((countries.indexOfFirst { it.first == country } - 3).coerceAtLeast(0)) }

    Row(horizontalArrangement = Arrangement.spacedBy(40.s)) {
        Column {
            Text(stringResource(R.string.Tv_Setup_Country), style = ui(20.ts, FontWeight.SemiBold), color = Ink.Artist, modifier = Modifier.padding(start = 14.s, bottom = 8.s))
            Box(Modifier.width(480.s).height(360.s).background(Color(0x26000000), RoundedCornerShape(10.s))) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(6.s)) {
                    items(countries, key = { it.first }) { (code, name) ->
                        ListRow(
                            label = name,
                            selected = code == country,
                            onClick = {
                                country = code
                                region.country = code
                            },
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
        Column {
            Text(stringResource(R.string.Tv_Setup_Language), style = ui(20.ts, FontWeight.SemiBold), color = Ink.Artist, modifier = Modifier.padding(start = 14.s, bottom = 8.s))
            Column(Modifier.width(360.s).background(Color(0x26000000), RoundedCornerShape(10.s)).padding(6.s)) {
                for (tag in Region.LANGUAGES) {
                    val locale = Locale.forLanguageTag(tag)
                    ListRow(
                        label = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) },
                        selected = tag == language,
                        onClick = {
                            if (tag != language) {
                                region.language = tag
                                activity?.recreate()
                            }
                        },
                        // The remote starts on the language in force: always on screen, unlike a country far down
                        // a list, and where it stays after a language is picked and the screens are drawn again.
                        modifier = if (tag == language) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

/** One row of the country or language list: 52·s tall, the name in 20·s, a tick on the chosen one, lit under the remote. */
@Composable
private fun ListRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var lit by remember { mutableStateOf(false) }
    DeskButton(
        onClick = onClick,
        corner = 7.s,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .onFocusChanged { lit = it.isFocused }
            .fillMaxWidth()
            .height(52.s)
            .background(if (lit) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(7.s))
            .padding(horizontal = 14.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ui(20.ts, if (selected) FontWeight.SemiBold else FontWeight.Normal), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Glyph(Fa.CHECK, 15.ts, Ink.Words)
        }
    }
}
