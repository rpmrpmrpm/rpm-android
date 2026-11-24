package com.rpm.player.phone.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rpm.player.core.R
import com.rpm.player.phone.design.AppColour
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.glassTint
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.header
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.pt
import kotlinx.coroutines.launch
import java.util.Calendar

/** The rpm site's pages, as the iPhone's `RpmSite`. */
internal object RpmSite {
    const val HOME = "https://rpmrpmrpm.com"
    const val SUPPORT = "https://rpmrpmrpm.com/support"
    const val PRIVACY = "https://rpmrpmrpm.com/privacy"
    const val TERMS = "https://rpmrpmrpm.com/terms"

    /** Google Play's page for the supporter subscription, where it is changed or cancelled. Support is sold through
     * Google Play, and the app links to no other payment page. */
    const val PLAY_SUBSCRIPTION = "https://play.google.com/store/account/subscriptions?sku=rpm_supporter&package=com.rpm.player"
}

/** The licence being read, with the entry it belongs to. One value rather than a flag and two others, so there is no
 * state in which the sheet is reading a licence it cannot name the holder of. */
private class Reading(val entry: CreditEntry, val licence: CreditLicense, val text: String)

/**
 * What rpm is, and everything on this phone that somebody else made: the iPhone's `AboutPage` sheet, raised over
 * everything from the About card.
 *
 * Two states in one sheet, as on iPhone: the list, and one licence in full. The bar's one key is Close on the list
 * and Back on a licence, and the system's back gesture does the same, because a second key that does nothing most of
 * the time looks broken.
 *
 * The list is generated (see [Credits]) and is long: every photograph and every lettering face is a row. It is a
 * lazy list here, where the iPhone uses a plain stack, so the sheet composes only the rows on screen. Its scroll
 * position is kept while a licence is open, so Back returns to the row the licence was opened from.
 */
@Composable
fun AboutPage(version: String, onClose: () -> Unit) {
    var reading by remember { mutableStateOf<Reading?>(null) }
    val list = rememberLazyListState()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val back = { if (reading != null) reading = null else onClose() }

    // A link that no app on the phone can open does nothing, rather than closing the app.
    val open: (String?) -> Unit = { url -> if (!url.isNullOrEmpty()) runCatching { links.openUri(url) } }

    // Reads the licence and switches to it in one step, so the sheet is never on the reader with nothing in it. The
    // read itself is off the main thread (see [Credits.text]).
    val read: (CreditEntry, CreditLicense) -> Unit = { entry, licence ->
        scope.launch { reading = Reading(entry, licence, Credits.text(context, licence, entry.copyright)) }
    }

    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Ink.ground)) {
            val now = reading
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Ink.white(0x0A))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(onClick = back, stretches = false, height = 34.dp, radius = 9.dp, inset = 16.dp, size = 13f) { style ->
                    BasicText(stringResource(if (now == null) R.string.About_Close else R.string.About_Back), style = style)
                }
                Spacer(Modifier.weight(1f))
                BasicText(stringResource(if (now == null) R.string.About_Title else R.string.About_License), style = kicker())
            }

            if (now != null) LicencePage(now) else CreditsList(version, list, open, read)
        }
    }
}

// ── the list ──

@Composable
private fun CreditsList(
    version: String,
    state: LazyListState,
    open: (String?) -> Unit,
    read: (CreditEntry, CreditLicense) -> Unit,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + bottom),
    ) {
        item(key = "identity", contentType = "identity") { Identity(version, open) }

        item(key = "heading", contentType = "heading") {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(stringResource(R.string.About_Acknowledgements), style = displayBold(17f).copy(color = Ink.white(0xFF)))
                BasicText(stringResource(R.string.About_Intro), style = display(12.5f).copy(color = Ink.white(0x99)))
            }
        }

        // The iPhone's stack is 18 points between sections and 8 between a section's heading and its rows; here
        // the same gaps are each item's top padding.
        for (section in Credits.sections) {
            item(key = "section-${section.title}", contentType = "section") {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(stringResource(section.title), style = displayBold(14f).copy(color = Ink.white(0xE0)))
                    section.note?.let { BasicText(stringResource(it), style = display(12f).copy(color = Ink.white(0x88))) }
                }
            }
            // Keyed by place, not by name: the list is fixed, and two rows can share a name and a detail.
            itemsIndexed(section.entries, key = { at, _ -> "${section.title}-$at" }, contentType = { _, _ -> "entry" }) { _, entry ->
                EntryRow(entry, open, read, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** The card at the top: the name, the tagline, the version and the four links to the site. */
@Composable
private fun Identity(version: String, open: (String?) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.white(0x12))
            .glassTint(RectangleShape, AppColour.ON_SECTION)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText("rpm", style = header(34f).copy(color = Ink.white(0xCC)))
        BasicText(stringResource(R.string.About_Tagline), style = display(13f).copy(color = Ink.white(0xB3)))
        val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
        BasicText(
            "${stringResource(R.string.About_Version, version)}  ·  © $year rpm. ${stringResource(R.string.About_Rights)}",
            Modifier.padding(top = 4.dp),
            style = display(11f).copy(color = Ink.white(0x66)),
        )
        // One row where the four labels fit, two where they do not, as on iPhone.
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((label, url) in SITE_LINKS) {
                GlassButton(onClick = { open(url) }, stretches = false, height = 30.dp, radius = 9.dp, inset = 12.dp, size = 12f) { style ->
                    BasicText(stringResource(label), maxLines = 1, style = style)
                }
            }
        }
    }
}

private val SITE_LINKS = listOf(
    R.string.About_Website to RpmSite.HOME,
    R.string.About_Support to RpmSite.SUPPORT,
    R.string.About_Privacy to RpmSite.PRIVACY,
    R.string.About_Terms to RpmSite.TERMS,
)

// ── one entry ──

@Composable
private fun EntryRow(entry: CreditEntry, open: (String?) -> Unit, read: (CreditEntry, CreditLicense) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.white(0x0E))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // A flow rather than the iPhone's row, so a long name with a version and a detail wraps instead of being cut.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            BasicText(entry.name, style = display(13.5f).copy(color = Ink.white(0xF0)))
            entry.version?.let { Faint(it) }
            entry.detail?.let { Faint(it) }
        }

        entry.use?.let { BasicText(stringResource(it), style = display(12f).copy(color = Ink.white(0x99))) }

        entry.copyright?.let { BasicText(it, style = display(11f).copy(color = Ink.white(0x66))) }

        // Wrapped, because a row can carry four of these.
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (name in entry.licenses) {
                val licence = Credits.licenses[name] ?: continue
                // A licence whose text ships opens here. One that does not is a published set of terms only its
                // publisher may host, so it opens in the browser, and the row says which by where it sends you
                // rather than by explaining. A notices document ships and still opens in the browser: it is far
                // too long for this reader.
                if (licence.file != null && !licence.isNotices) {
                    LinkKey(licence.name, Glyph.license) { read(entry, licence) }
                } else {
                    LinkKey(licence.name, Glyph.outward) { open(entry.termsUrl ?: licence.url) }
                }
            }
            if (!entry.url.isNullOrEmpty()) LinkKey(host(entry.url), Glyph.link) { open(entry.url) }
            if (!entry.source.isNullOrEmpty()) LinkKey(stringResource(R.string.About_SourceCode), Glyph.code) { open(entry.source) }
        }
    }
}

@Composable
private fun Faint(text: String) {
    BasicText(text, style = display(11.5f).copy(color = Ink.white(0x66)))
}

/** A plain link: a glyph and a label, dimmed while a finger is on it, as SwiftUI's plain button style does. */
@Composable
private fun LinkKey(title: String, glyph: String, go: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .graphicsLayer { alpha = if (pressed) 0.5f else 1f }
            .clickable(presses, indication = null, role = Role.Button, onClick = go),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(glyph, style = icon(10f).copy(color = Ink.white(0x77)))
        BasicText(title, style = display(11.5f).copy(color = Ink.white(0xCC)))
    }
}

/** A link's label: the site, not the address. A full address in eleven-point type is hard to read and will not fit. */
private fun host(url: String): String {
    val host = Uri.parse(url).host ?: return url
    return host.removePrefix("www.")
}

// ── one licence ──

@Composable
private fun LicencePage(reading: Reading) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(reading.licence.name, style = displayBold(17f).copy(color = Ink.white(0xFF)))
            BasicText(
                reading.entry.copyright?.let { "${reading.entry.name}. $it" } ?: reading.entry.name,
                style = display(12.5f).copy(color = Ink.white(0x99)),
            )
        }

        // Monospaced, and the only place in the app that is. A licence is a legal text with its own line breaks and
        // its own indentation, and setting it in the app's face re-wraps paragraphs written to a fixed measure.
        SelectionContainer {
            BasicText(reading.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = pt(11.5f), color = Ink.white(0xCC)))
        }
    }
}
