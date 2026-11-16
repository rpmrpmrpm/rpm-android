@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.rpm.player.phone.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.rpm.player.core.R
import com.rpm.player.core.cloud.PageGround
import com.rpm.player.core.cloud.ProfileBadges
import com.rpm.player.phone.design.Alert
import com.rpm.player.phone.design.DialogKey
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.KeyRole
import com.rpm.player.phone.design.PageTitle
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.GroundTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MARGIN = 20.dp

/**
 * What the reader has changed on the edit page and not saved yet, beside what the page showed when it opened. Nothing
 * reaches the server until [PersonPage.save].
 */
@Stable
internal class PageEdit(page: PersonPage) {
    private val profile = page.profile

    val nameAtStart = profile?.displayName.orEmpty()
    var name by mutableStateOf(nameAtStart)

    /** A new photo, ready to send; null to keep the one there is. */
    var photo by mutableStateOf<ByteArray?>(null)
    var dropPhoto by mutableStateOf(false)

    /** A new banner photo, ready to send; null to keep the one there is. */
    var banner by mutableStateOf<ByteArray?>(null)
    var dropBanner by mutableStateOf(false)

    /** Whether the banner is the playing artist's photo, as the page decides it ([ProfileScreen]). */
    val followsAtStart = profile?.bannerFollowsListening == true || (page.isSample && profile?.bannerSource != "photo")
    var follows by mutableStateOf(followsAtStart)

    val groundAtStart = PageGround.of(profile?.pageGround.orEmpty())
    var ground by mutableStateOf(groundAtStart)

    val badgeAtStart = profile?.badge.orEmpty()
    var badge by mutableStateOf(badgeAtStart)
    val colourAtStart = profile?.badgeColour.orEmpty()
    var colour by mutableStateOf(colourAtStart)

    /** The server takes a name of 2 to 40 characters. */
    val nameValid: Boolean get() = name.trim().length in 2..40

    val changed: Boolean
        get() = name.trim() != nameAtStart || photo != null || dropPhoto || banner != null || dropBanner ||
            follows != followsAtStart || ground != groundAtStart || badge != badgeAtStart || colour != colourAtStart
}

/**
 * The reader's own page, to change: the iPhone's `ProfileEditSheet`. Every part of the page is a card on this one
 * screen, one under the other: the display name, the profile photo, the banner, the background and the badge. Save at
 * the foot sends every change at once. The background behind the cards is the one chosen, so a choice can be seen.
 *
 * Closing with changes not saved asks first: apply them, or discard them.
 *
 * @param ownBadges the badges the reader's own collection unlocks, worked out on this phone.
 * @param playingPhoto the photo of the artist playing now, for the banner card; null when nothing plays or none was found.
 */
@Composable
internal fun EditPage(page: PersonPage, images: ImageLoader, ownBadges: List<String>, playingPhoto: String?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backdrop = rememberBackdrop()
    val edit = remember(page.userId) { PageEdit(page) }
    var asking by remember { mutableStateOf(false) }
    var pickingFor by remember { mutableStateOf(Picked.Photo) }
    var pictureFailed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = pickingFor
        scope.launch {
            val ready = withContext(Dispatchers.Default) { PicturePrep.prepare(context, uri, target) }
            pictureFailed = ready == null
            if (ready == null) return@launch
            when (target) {
                Picked.Photo -> {
                    edit.photo = ready
                    edit.dropPhoto = false
                }
                Picked.Banner -> {
                    edit.banner = ready
                    edit.dropBanner = false
                    edit.follows = false
                }
            }
        }
    }
    fun pick(target: Picked) {
        pickingFor = target
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun save() {
        scope.launch { if (page.save(edit, ownBadges)) onClose() }
    }
    fun close() {
        if (edit.changed) asking = true else onClose()
    }
    BackHandler { close() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            PageGroundView(edit.ground.wire)
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = MARGIN, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageTitle(stringResource(R.string.Rpm_Profile_EditPage), Modifier.weight(1f))
                    CloseKey(::close)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MARGIN),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NameCard(edit)
                    PhotoCard(edit, page, images, onPick = { pick(Picked.Photo) })
                    BannerCard(edit, page, images, playingPhoto, onPick = { pick(Picked.Banner) })
                    BackgroundCard(edit, gold = page.profile?.supporter == true)
                    BadgesCard(edit, unlocked = (page.profile?.badges.orEmpty() + ownBadges).distinct())
                    val trouble = if (pictureFailed) R.string.Rpm_Profile_PictureFailed else page.editTrouble
                    trouble?.let { said -> BasicText(stringResource(said), style = display(13f).copy(color = Ink.red)) }
                    // Save stands under every setting, as the last thing on the page.
                    GlassButton(
                        onClick = ::save,
                        weight = Weight.Primary,
                        enabled = edit.changed && edit.nameValid && !page.saving,
                        height = 48.dp,
                        radius = 14.dp,
                        size = 16f,
                    ) { style ->
                        BasicText(stringResource(R.string.Rpm_Profile_Save), style = style)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (asking) {
            Alert(
                backdrop,
                stringResource(R.string.Rpm_Profile_UnsavedTitle),
                null,
                listOf(
                    DialogKey(stringResource(R.string.Rpm_Profile_Discard), KeyRole.Destructive) {
                        asking = false
                        onClose()
                    },
                    DialogKey(stringResource(R.string.Rpm_Profile_ApplyChanges)) {
                        asking = false
                        // A name the server would refuse stays on the page, to be put right first.
                        if (edit.nameValid) save()
                    },
                ),
            )
        }
    }
}

/** One part of the page to change, as the settings draw theirs: a rounded card with its name at the top. */
@Composable
private fun EditCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.white(0x12))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(title, style = kicker())
        content()
    }
}

@Composable
private fun NameCard(edit: PageEdit) {
    EditCard(stringResource(R.string.Rpm_Profile_Name)) {
        val well = RoundedCornerShape(12.dp)
        BasicTextField(
            edit.name,
            onValueChange = { edit.name = it.take(40) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(well)
                .background(Ink.white(0x14))
                .border(1.dp, Ink.white(0x22), well)
                .padding(12.dp),
            textStyle = display(17f).copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
        )
    }
}

@Composable
private fun PhotoCard(edit: PageEdit, page: PersonPage, images: ImageLoader, onPick: () -> Unit) {
    EditCard(stringResource(R.string.Rpm_Profile_PhotoTitle)) {
        val shown: Any? = when {
            edit.photo != null -> edit.photo
            edit.dropPhoto -> null
            else -> page.photo ?: page.profile?.let { LocalSampleFaces.current[it.userId] }
        }
        val hasPhoto = edit.photo != null || (!edit.dropPhoto && page.profile?.hasAvatar == true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Ink.white(0x26)), contentAlignment = Alignment.Center) {
                val first = page.profile?.displayName?.firstOrNull()
                BasicText(
                    if (first != null && first.isLetterOrDigit()) first.uppercase() else "?",
                    style = system(30f, FontWeight.SemiBold).copy(color = Ink.white(0xDD)),
                )
                if (shown != null) {
                    AsyncImage(model = rememberPicture(shown, 72.dp), contentDescription = null, imageLoader = images, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallKey(stringResource(R.string.Rpm_Profile_BannerPhoto), Weight.Ghost, onClick = onPick)
                if (hasPhoto) {
                    SmallKey(stringResource(R.string.Rpm_Profile_RemovePhoto), Weight.Ghost) {
                        edit.photo = null
                        edit.dropPhoto = true
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerCard(edit: PageEdit, page: PersonPage, images: ImageLoader, playingPhoto: String?, onPick: () -> Unit) {
    EditCard(stringResource(R.string.Rpm_Profile_BannerTitle)) {
        val own: Any? = when {
            edit.banner != null -> edit.banner
            edit.dropBanner -> null
            else -> page.banner
        }
        val shown = if (edit.follows) playingPhoto else own
        val frame = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxWidth().height(96.dp).clip(frame).background(Ink.white(0x10)).border(1.dp, Ink.white(0x1A), frame), contentAlignment = Alignment.Center) {
            BasicText(Glyph.record, style = icon(22f).copy(color = Ink.white(0x33)))
            if (shown != null) {
                AsyncImage(model = rememberPicture(shown, 360.dp, 96.dp), contentDescription = null, imageLoader = images, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Choice(stringResource(R.string.Rpm_Profile_BannerPlaying), picked = edit.follows) { edit.follows = true }
        Choice(stringResource(R.string.Rpm_Profile_BannerOwn), picked = !edit.follows) { edit.follows = false }
        if (!edit.follows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallKey(stringResource(R.string.Rpm_Profile_BannerPhoto), Weight.Ghost, onClick = onPick)
                if (own != null) {
                    SmallKey(stringResource(R.string.Rpm_Profile_RemoveBanner), Weight.Ghost) {
                        edit.banner = null
                        edit.dropBanner = true
                    }
                }
            }
        }
    }
}

/** A choice of one among several: a round mark, filled when it is the one chosen, and its name. */
@Composable
private fun Choice(title: String, picked: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onPick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(2.dp, Ink.white(if (picked) 0xF2 else 0x55), CircleShape), contentAlignment = Alignment.Center) {
            if (picked) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
        }
        BasicText(title, style = display(15f).copy(color = Ink.white(if (picked) 0xFF else 0xBB)))
    }
}

/**
 * The solid colours a page can be drawn on, darkest last. The iPhone's `PageGroundPicker.colours` holds the same values,
 * in the same order; change both.
 */
internal val PAGE_COLOURS = listOf(
    0xFF2A0A5EL, 0xFF0B1E4AL, 0xFF0E4B4FL, 0xFF14402AL,
    0xFF4F1028L, 0xFF5A2A0CL, 0xFF2A2F38L, 0xFF0A0A0AL,
)

/** What the page is drawn on: one of the app's backgrounds, or a solid colour. The gold background is a supporter's. */
@Composable
private fun BackgroundCard(edit: PageEdit, gold: Boolean) {
    EditCard(stringResource(R.string.Settings_Background)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (choice in Ground.entries) {
                if (choice == Ground.Gold && !gold) continue
                val ground = if (choice == Ground.Standard) PageGround.Standard else PageGround.Backdrop(choice.id)
                val picked = ground == edit.ground
                val name = stringResource(choice.nameKey)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .border(if (picked) 2.dp else 1.dp, Ink.white(if (picked) 0xF2 else 0x33), RoundedCornerShape(10.dp))
                            .semantics { contentDescription = name }
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { edit.ground = ground },
                    ) {
                        GroundTile(choice, 62.dp, 116.dp)
                    }
                    BasicText(name, maxLines = 1, style = display(11f).copy(color = Ink.white(if (picked) 0xFF else 0x99)))
                }
            }
        }
        BasicText(stringResource(R.string.Rpm_Profile_Colours), style = kicker())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (argb in PAGE_COLOURS) {
                val ground = PageGround.Colour(argb)
                val picked = ground == edit.ground
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(if (picked) 3.dp else 1.dp, Ink.white(if (picked) 0xF2 else 0x44), CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { edit.ground = ground },
                )
            }
        }
    }
}

/**
 * The badge under the name and its colour: every badge unlocked to choose from, side by side, and the ones still to
 * unlock with what unlocks them, greyed, as achievements.
 *
 * @param unlocked the ids unlocked, from the server and from this phone's own count.
 */
@Composable
private fun BadgesCard(edit: PageEdit, unlocked: List<String>) {
    val held = unlocked.toSet()
    val (open, closed) = ProfileBadges.all.partition { it.id in held }
    EditCard(stringResource(R.string.Rpm_Badges_Title)) {
        BasicText(stringResource(R.string.Rpm_Badges_Colour), style = kicker())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (value in ProfileBadges.colours) {
                val wire = "#%06X".format(value)
                val picked = badgeColour(edit.colour) == badgeColour(wire)
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(badgeColour(wire))
                        .border(if (picked) 3.dp else 1.dp, Ink.white(if (picked) 0xF2 else 0x44), CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { edit.colour = wire },
                )
            }
        }
        BasicText(stringResource(R.string.Rpm_Badges_Unlocked), Modifier.padding(top = 4.dp), style = kicker())
        // The badges unlocked side by side, as many on a line as fit. The one shown has a white ring.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NoBadgeChip(picked = edit.badge.isEmpty()) { edit.badge = "" }
            for (badge in open) {
                val picked = badge.id == edit.badge
                Box(
                    Modifier
                        .border(if (picked) 2.dp else 0.dp, if (picked) Color.White else Color.Transparent, RoundedCornerShape(16.dp))
                        .padding(3.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { edit.badge = badge.id },
                ) {
                    BadgePill(badge, edit.colour)
                }
            }
        }
        if (closed.isNotEmpty()) {
            BasicText(stringResource(R.string.Rpm_Badges_Locked), Modifier.padding(top = 4.dp), style = kicker())
            for (badge in closed) LockedBadge(badge)
        }
    }
}

/** "No badge", as a chip among the badges: the choice to show none. */
@Composable
private fun NoBadgeChip(picked: Boolean, onPick: () -> Unit) {
    BasicText(
        stringResource(R.string.Rpm_Badges_NoBadge),
        Modifier
            .border(if (picked) 2.dp else 1.dp, Ink.white(if (picked) 0xFF else 0x44), RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = display(14f).copy(color = Ink.white(0xCC)),
    )
}

/** A badge still to unlock: its pill greyed, and what unlocks it. */
@Composable
private fun LockedBadge(badge: ProfileBadges.Badge) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.white(0x0C))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.graphicsLayer { alpha = 0.35f }) { BadgePill(badge, "#555555") }
        BasicText(stringResource(badge.need), style = display(12f).copy(color = Ink.white(0x88)))
    }
}
