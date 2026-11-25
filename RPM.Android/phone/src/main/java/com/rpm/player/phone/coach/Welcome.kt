package com.rpm.player.phone.coach

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.Plays
import com.rpm.player.core.library.StarterTracks
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.AppColourChoice
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.system
import com.rpm.player.phone.downloads.LocalPlayable
import com.rpm.player.phone.library.LibraryLayout
import com.rpm.player.phone.library.SleeveSquare
import com.rpm.player.phone.settings.AppLanguages
import com.rpm.player.phone.settings.BackdropVeilSliders
import com.rpm.player.phone.settings.CoverCornerSlider
import com.rpm.player.phone.supporter.SupporterBilling
import com.rpm.player.phone.supporter.SupporterPerk
import com.rpm.player.phone.supporter.SupporterSheet
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.GroundTile
import com.rpm.player.phone.ui.LiveRoom
import com.rpm.player.phone.ui.VinylGround
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The welcome on the very first launch, after signing in: the desktop's setup wizard (`WizardPage`), made for the phone.
 * The phone has nothing to set up that the desktop's wizard sets up (a folder, an import, an account to make), so it
 * keeps the desktop's greeting and its look and layout steps, and ends with a song from the person's own collection,
 * played in the player while its hints show the player round.
 *
 * - **Hello**: "Welcome to rpm!" on black, the drifting room coming up behind it, the greeting turning through the app's
 *   languages every five seconds, as the desktop's does.
 * - **Language**: the app's language ([AppLanguages]). The one tapped is used at once, on this step.
 * - **Look**: the backdrops, how much they are dimmed and blurred, and how round the covers are. Each change shows
 *   behind the step at once.
 * - **Layout**: how the records open in the collection: the grid of covers, or the list with the songs.
 * - **Support**: for an account that does not support rpm, the desktop's supporter ask (its last setup step): why to
 *   support rpm, what supporters get, and the key that opens the subscription sheet ([SupporterSheet]).
 * - **Spin**: a song from the collection ([sample]), and the key that plays it and opens the player.
 *
 * The steps carry the desktop's "Step 1 of 3" and its row of dots. Which steps there are is worked out on every draw
 * ([WelcomeSteps]): the account's standing can arrive after the welcome has opened, and a purchase on the support step
 * ends the ask while that step is on screen.
 *
 * @param sample the song the last step plays; null leaves that step out, and the step before it ends the welcome.
 * @param support the supporter ask; null leaves the support step out: for an account that already supports rpm, and
 *   while the account's standing is not known, because asking a supporter to support rpm tells them something that is
 *   not true.
 * @param onPlay plays [sample] and opens the player. The welcome is over then.
 * @param onDone ends the welcome without playing.
 */
@Composable
fun Welcome(
    collection: Collection,
    images: ImageLoader,
    settings: PhoneSettings,
    sample: CloudTrack?,
    support: WelcomeSupport?,
    onPlay: (CloudTrack) -> Unit,
    onDone: () -> Unit,
) {
    // Saved state, so a new language on Android 9 to 12, which makes the activity again, opens on the same step.
    var page by rememberSaveable { mutableStateOf(WelcomePage.HELLO) }
    val steps = WelcomeSteps(asks = support != null, hasSong = sample != null)
    // The page on screen; null when no step is left after the one asked for, for the moment before the welcome ends.
    val shown = steps.shown(page)
    var subscribing by remember { mutableStateOf(false) }
    val forward: (WelcomePage) -> Unit = { from -> steps.after(from)?.let { page = it } ?: onDone() }
    // The ask has ended while its step is on screen: a purchase was confirmed, so the account supports rpm now. The
    // welcome goes on to the next step, or ends when there is none.
    LaunchedEffect(support != null) {
        if (support == null && page == WelcomePage.SUPPORT) forward(WelcomePage.SUPPORT)
    }
    // Opens on one page, for looking at it without taps. See debugWelcomePage.
    val context = LocalContext.current
    LaunchedEffect(Unit) { debugWelcomePage(context)?.let { page = it } }
    BackHandler(enabled = page != WelcomePage.HELLO) { page = steps.before(shown ?: page) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Every touch stops here: the app under the welcome is not in play yet.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // The ground: the drifting room for the greeting, the chosen backdrop for the steps after it.
        Crossfade(shown == WelcomePage.HELLO, animationSpec = tween(700), label = "welcome ground") { hello ->
            if (hello) HelloRoom() else VinylGround()
        }
        AnimatedContent(
            shown,
            transitionSpec = { fadeIn(tween(280, delayMillis = 170)) togetherWith fadeOut(tween(170)) },
            label = "welcome step",
        ) { on ->
            val count = steps.steps.size
            when (on) {
                WelcomePage.HELLO -> Hello(onContinue = { page = WelcomePage.LANGUAGE })
                WelcomePage.LANGUAGE -> Step(steps.number(on), count, stringResource(R.string.Wizard_Phone_LanguageTitle), stringResource(R.string.Wizard_Phone_LanguageHint), next = { forward(on) }) {
                    Languages()
                }
                WelcomePage.LOOK -> Step(steps.number(on), count, stringResource(R.string.Wizard_LookTitle), stringResource(R.string.Wizard_Phone_LookAllHint), next = { forward(on) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Backdrops(settings)
                        BackdropVeilSliders()
                        // The colour of the app's glass, between the sliders and the cover corners, as on iPhone.
                        AppColourChoice(settings.isSupporter)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            BasicText(stringResource(R.string.Settings_CoverCorners), style = kicker())
                            CoverCornerSlider()
                        }
                    }
                }
                WelcomePage.LAYOUT -> Step(
                    steps.number(on),
                    count,
                    stringResource(R.string.Wizard_LayoutTitle),
                    stringResource(R.string.Wizard_Phone_LayoutBody),
                    next = { forward(on) },
                    nextLabel = if (steps.after(on) != null) null else stringResource(R.string.Wizard_Finish),
                ) {
                    Layouts(settings)
                }
                WelcomePage.SUPPORT -> Step(
                    steps.number(on),
                    count,
                    stringResource(R.string.Wizard_SupportTitle),
                    stringResource(R.string.Wizard_SupportPitch),
                    next = { subscribing = true },
                    nextLabel = stringResource(R.string.Cloud_BecomeSupporter),
                    nextGlyph = Glyph.heart,
                    skip = { forward(on) },
                    skipLabel = stringResource(R.string.Supporter_Later),
                ) {
                    Perks()
                }
                WelcomePage.SPIN -> sample?.let { song ->
                    Step(
                        steps.number(on),
                        count,
                        stringResource(R.string.Wizard_Phone_PlayerTitle),
                        stringResource(R.string.Wizard_Phone_PlayerBody),
                        next = { onPlay(song) },
                        nextLabel = stringResource(R.string.Wizard_Phone_PlayerGo),
                        skip = onDone,
                    ) {
                        Sample(song, collection, images)
                    }
                }
                null -> Box(Modifier.fillMaxSize())
            }
        }
        if (subscribing && support != null) {
            SupporterSheet(support.billing, support.accountId, onClose = { subscribing = false })
        }
    }
}

/**
 * What the support step needs: the Google Play subscription and the account a purchase is for (its Cognito `sub`).
 * After a confirmed purchase the app reads the account again ([SupporterBilling]), the quota then says the account
 * supports rpm, and the ask ends.
 */
class WelcomeSupport(val billing: SupporterBilling, val accountId: String)

/**
 * The page the welcome opens on, for looking at it without taps, as the iPhone's `RPM_WELCOME_PAGE`: the name in the
 * file `welcome-page` in the app's files folder ("support", "spin"). The shell shows the welcome again while the file is
 * there. Only a debuggable build reads it, so a listener's build never does. Turned on with
 * `adb shell "run-as com.rpm.player sh -c 'echo support > files/welcome-page'"`, off by removing the file. Null when
 * unset.
 */
fun debugWelcomePage(context: Context): WelcomePage? {
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    val name = runCatching { java.io.File(context.filesDir, "welcome-page").readText().trim() }.getOrNull() ?: return null
    return WelcomePage.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** What supporters get, on the support step: the desktop's card on its last setup step, one column for the phone's
 * width. Each perk's glyph carries its colour ([SupporterPerk]). */
@Composable
private fun Perks() {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x13FFFFFF))
            .border(1.dp, Color(0x2BFFFFFF), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(stringResource(R.string.Supporter_OfferTitle).uppercase(), style = kicker())
        for (perk in SupporterPerk.ALL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(perk.glyph, Modifier.width(30.dp), style = icon(15f).copy(color = if (perk.gold) Ink.gold else ACCENT))
                BasicText(stringResource(perk.words), style = display(15f).copy(color = Ink.white(0xCC)))
            }
        }
        BasicText(stringResource(R.string.Supporter_PerkMore), style = display(13f).copy(color = Ink.white(0x6B)))
    }
}

/** The drifting room behind the greeting, faded in a second after the words, as the desktop's comes in. */
@Composable
private fun HelloRoom() {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(1_000)
        shown.animateTo(1f, tween(1_500))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
        LiveRoom(RoomPalette.FALLBACK, "welcome", running = true, Modifier.fillMaxSize())
    }
}

/** The app's languages the greeting turns through: the desktop's `AppLanguage.Offered`. */
private val GREETED = listOf("en", "fr", "es", "de", "zh-Hans")

/** The greeting in [language], read from the app's own strings for that language. */
private fun hello(context: Context, language: String): String {
    val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    return context.createConfigurationContext(config).resources.getString(R.string.Wizard_Hello)
}

@Composable
private fun Hello(onContinue: () -> Unit) {
    val context = LocalContext.current
    // The phone's own language first, then the others in the desktop's order.
    val greetings = remember(context) {
        val own = Locale.getDefault().language
        val first = GREETED.firstOrNull { Locale.forLanguageTag(it).language == own } ?: "en"
        (listOf(first) + GREETED.filter { it != first }).map { hello(context, it) }.distinct()
    }
    var shownAt by remember { mutableIntStateOf(0) }
    val words = remember { Animatable(0f) }
    val rest = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(600)
        words.animateTo(1f, tween(700))
    }
    LaunchedEffect(Unit) {
        delay(2_000)
        rest.animateTo(1f, tween(700))
    }
    LaunchedEffect(greetings) {
        while (greetings.size > 1) {
            delay(5_000)
            shownAt = (shownAt + 1) % greetings.size
        }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crossfade(shownAt, animationSpec = tween(700), label = "greeting", modifier = Modifier.graphicsLayer { alpha = words.value }) { at ->
            BasicText(greetings[at], style = displayBold(44f).copy(color = Color.White, textAlign = TextAlign.Center, lineHeight = 50.sp))
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.graphicsLayer { alpha = rest.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            BasicText(stringResource(R.string.Wizard_HelloSubtitle), style = display(18f).copy(color = Color(0xC4FFFFFF), textAlign = TextAlign.Center))
            GlassButton(onClick = onContinue, stretches = false, inset = 28.dp) { style ->
                BasicText(stringResource(R.string.Wizard_Continue), style = style)
                Spacer(Modifier.width(8.dp))
                BasicText(Glyph.into, style = icon(11f).copy(color = style.color))
            }
        }
    }
}

/** The desktop's step colours: the lit dots, the picked tile's edge. */
private val ACCENT = Color(0xFF04C8FF)

/**
 * One step: "Step 2 of 3", the title and the line under it, the step's own choices, and at the foot the row of dots
 * with the key that goes on. [skip] adds "Skip for now" beside it.
 */
@Composable
private fun Step(
    at: Int,
    of: Int,
    title: String,
    body: String,
    next: () -> Unit,
    nextLabel: String? = null,
    nextGlyph: String? = null,
    skip: (() -> Unit)? = null,
    skipLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(stringResource(R.string.Wizard_StepOf, at, of).uppercase(), style = kicker())
            BasicText(title, style = displayBold(28f).copy(color = Color.White, lineHeight = 34.sp))
            BasicText(body, style = display(14f).copy(color = Color(0xC4FFFFFF), lineHeight = 20.sp))
            Spacer(Modifier.height(14.dp))
            content()
        }
        // The desktop's dots: lit up to the step showing.
        val dots = @Composable { modifier: Modifier ->
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                for (dot in 1..of) Box(Modifier.size(9.dp).clip(CircleShape).background(if (dot <= at) ACCENT else Color(0x38FFFFFF)))
            }
        }
        val nextKey = @Composable { stretches: Boolean ->
            GlassButton(onClick = next, weight = Weight.Primary, stretches = stretches, inset = 22.dp) { style ->
                nextGlyph?.let {
                    BasicText(it, style = icon(12f).copy(color = style.color))
                    Spacer(Modifier.width(8.dp))
                }
                BasicText(nextLabel ?: stringResource(R.string.Wizard_Next), maxLines = 1, style = style)
            }
        }
        if (skip != null) {
            // A step with a skip key has two keys with long words ("Play and show me around"). A phone is too narrow
            // for both beside the dots, and the words would wrap onto three lines. So the dots stand on their own row,
            // the key that goes on takes the width, and the skip key is under it.
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 18.dp, bottom = 8.dp)) {
                dots(Modifier)
                Spacer(Modifier.height(14.dp))
                nextKey(true)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = skip)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText(skipLabel ?: stringResource(R.string.Wizard_SkipForNow), style = system(14f).copy(color = Ink.white(0xB3))) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                dots(Modifier.weight(1f))
                nextKey(false)
            }
        }
    }
}

/** The backdrops, as the settings page shows them, larger; the one tapped is the ground at once. Gold only for a supporter. */
@Composable
private fun Backdrops(settings: PhoneSettings) {
    val chosen = Ground.standing(settings.backdrop, settings.isGold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (ground in Ground.entries) {
            if (ground.isSupporters && !settings.isGold) continue
            Choice(picked = ground == chosen, onClick = { settings.changeBackdrop(ground.id) }) {
                GroundTile(ground, TILE_WIDE, TILE_TALL, Modifier.clip(RoundedCornerShape(8.dp)))
                BasicText(stringResource(ground.nameKey), Modifier.padding(top = 8.dp), style = system(13f, FontWeight.Medium).copy(color = Color.White))
            }
        }
    }
}

private val TILE_WIDE = 104.dp
private val TILE_TALL = 186.dp

/**
 * The app's languages, each named in itself, with the phone's own first (`WelcomeLanguages` on iPhone). The one tapped
 * is used at once: the welcome shows itself again in it, on this step.
 */
@Composable
private fun Languages() {
    val context = LocalContext.current
    // Asked again when the configuration changes, which a new language does.
    val chosen = remember(LocalConfiguration.current) { AppLanguages.chosen(context) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (code in listOf("") + AppLanguages.OFFERED) {
            val said = if (code.isEmpty()) stringResource(R.string.Settings_AppLanguagePhone) else AppLanguages.name(code)
            Choice(picked = code == chosen, onClick = { if (code != chosen) AppLanguages.choose(context, code) }, Modifier.fillMaxWidth()) {
                BasicText(
                    said,
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    style = system(16f, FontWeight.SemiBold).copy(color = Color.White),
                )
            }
        }
    }
}

/** The two ways the records open: the grid of covers and the list with the songs. */
@Composable
private fun Layouts(settings: PhoneSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((layout, said) in listOf(LibraryLayout.Grid to R.string.Wizard_LayoutWallDesc, LibraryLayout.List to R.string.Wizard_LayoutListDesc)) {
            Choice(picked = settings.libraryLayout == layout, onClick = { settings.changeLibraryLayout(layout) }, Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BasicText(layout.mark, style = icon(30f).copy(color = ACCENT))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BasicText(stringResource(layout.label), style = system(16f, FontWeight.SemiBold).copy(color = Color.White))
                        BasicText(stringResource(said), style = system(12.5f).copy(color = Color(0x8CFFFFFF)))
                    }
                }
            }
        }
    }
}

/** A choice as the desktop's wizard draws one (`WizardChoices`): a faint tile with a cyan edge while it is picked. */
@Composable
private fun Choice(picked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(Color(0x13FFFFFF))
            .border(2.dp, if (picked) ACCENT else Color(0x2BFFFFFF), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** The song the last step plays: its cover, its title and its artist. */
@Composable
private fun Sample(song: CloudTrack, collection: Collection, images: ImageLoader) {
    val album = collection.albumOf[song.rpmId]
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SleeveSquare(album?.let { collection.cover(it) }, 200.dp, images, radius = 14.dp, glyphSize = 44f)
        BasicText(song.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = displayBold(20f).copy(color = Color.White, textAlign = TextAlign.Center))
        BasicText(song.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Ink.white(0xB3), textAlign = TextAlign.Center))
    }
}

/**
 * The song the welcome plays: one the person already knows where they have listened before (the one played most), else
 * one they marked, else a song from the collection. Only a song that can play now, and one long enough to look round the
 * player while it plays. See [sampleOf].
 */
@Composable
fun rememberSample(collection: Collection): CloudTrack? {
    val playable = LocalPlayable.current
    return remember(collection, playable) {
        sampleOf(collection.tracksById.values, collection.plays) { rpmId -> collection.albumOf[rpmId]?.let { playable.canPlay(it.key) } == true }
    }
}

/**
 * The welcome's song from [tracks], as [rememberSample] picks it. [canPlay] says whether a track's record plays now.
 *
 * A collection that is the starter tracks ([StarterTracks]), shown while the person has no music of their own, has no
 * plays and no marks to go by, so the song is the first of [StarterTracks.welcomeOrder] that qualifies. Without them a
 * new account would have no song, and the welcome would leave out its last step and the player's tour.
 */
internal fun sampleOf(tracks: kotlin.collections.Collection<CloudTrack>, plays: Map<String, Plays>, canPlay: (String) -> Boolean): CloudTrack? {
    val songs = tracks.filter { track ->
        !track.title.isNullOrBlank() && (track.tags?.durationSeconds ?: 0.0) >= MIN_SECONDS && canPlay(track.rpmId)
    }
    if (songs.isNotEmpty() && songs.all { StarterTracks.isStarter(it.rpmId) }) {
        val byId = songs.associateBy { it.rpmId }
        StarterTracks.welcomeOrder.firstNotNullOfOrNull(byId::get)?.let { return it }
    }
    return songs.maxWithOrNull(
        compareBy<CloudTrack> { plays[it.rpmId]?.count ?: 0 }
            .thenBy { if (it.tags?.favorite == true) 1 else 0 }
            .thenByDescending { it.rpmId },
    )
}

/** A song shorter than this ends before the player has been shown round. */
private const val MIN_SECONDS = 90.0
