package com.rpm.player.phone.shell

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.chromeGlass
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.system

/**
 * The main screens of the app, as on the iPhone (`RPMTab` in RPMUI/RPMTabBar.swift): Home, the library, the
 * radio and the profile. Playlists are a way of reading the library, not a tab.
 */
enum class Tab(val glyph: String, @StringRes val label: Int) {
    Home(Glyph.home, R.string.Rpm_Tab_Home),
    Library(Glyph.library, R.string.Settings_Library),
    Radio(Glyph.radio, R.string.Rpm_Tab_Radio),
    Profile(Glyph.profile, R.string.Rpm_Tab_Profile),
}

/**
 * The bottom of the app: what is playing, and where to go. This matches the iPhone's `RPMChrome`. It is two
 * floating glass shapes over the page. The mini player sits above the tab capsule. There is no solid panel
 * behind them, so the page shows through the glass. The mini player is shown while something is playing
 * ([miniPlayer] draws it).
 *
 * The shapes are 16 dp in from the sides. The capsule is 16 dp above the bottom of the screen, inside the
 * gesture bar, as the iPhone places it above the home indicator. With three-key navigation, a capsule there
 * would cover the keys. So it stands 8 dp above the keys instead.
 */
@Composable
fun Chrome(backdrop: Backdrop, showing: Tab, onShow: (Tab) -> Unit, miniPlayer: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val keys = WindowInsets.tappableElement.getBottom(density)
    val below: Dp = if (keys > 0) with(density) { keys.toDp() } + 8.dp else EDGE

    Column(
        modifier.padding(horizontal = EDGE).padding(bottom = below),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        miniPlayer?.invoke()
        TabBar(backdrop, showing, onShow)
    }
}

/** How far the shapes stand in from the sides, and the capsule up from the bottom. */
private val EDGE = 16.dp

/**
 * Four tabs in a glass capsule, as the iPhone's `RPMTabBar`. The selected tab sits on a white pill. The pill
 * slides to the new tab with a spring like the iPhone's (response 0.35 s, damping 0.8).
 */
@Composable
private fun TabBar(backdrop: Backdrop, showing: Tab, onShow: (Tab) -> Unit) {
    val capsule = RoundedCornerShape(percent = 50)
    val at by animateFloatAsState(showing.ordinal.toFloat(), spring(dampingRatio = 0.8f, stiffness = PILL_STIFFNESS), label = "pill")

    BoxWithConstraints(Modifier.fillMaxWidth().chromeGlass(backdrop, capsule).padding(4.dp)) {
        val each = maxWidth / Tab.entries.size
        Box(Modifier.offset(x = each * at).width(each).height(TAB_HEIGHT).clip(capsule).background(Ink.white(0x24)))
        Row(Modifier.fillMaxWidth()) {
            for (tab in Tab.entries) {
                TabFace(tab, lit = tab == showing, Modifier.weight(1f)) { onShow(tab) }
            }
        }
    }
}

/** One tab: its icon above its name. The name uses the system font, as the iPhone's tab labels do. */
@Composable
private fun TabFace(tab: Tab, lit: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(TAB_HEIGHT)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(tab.glyph, style = icon(17f).copy(color = if (lit) Color.White else Ink.white(0x99)))
        BasicText(
            stringResource(tab.label),
            maxLines = 1,
            style = system(10f, if (lit) FontWeight.SemiBold else FontWeight.Normal).copy(color = if (lit) Color.White else Ink.white(0x80)),
        )
    }
}

/** A tab's height, which the pill matches. */
private val TAB_HEIGHT = 52.dp

/** SwiftUI's `spring(response: 0.35)` as a stiffness: (2π / 0.35)². */
private const val PILL_STIFFNESS = 322f
