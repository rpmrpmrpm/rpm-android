package com.rpm.player.phone.downloads

import com.rpm.player.core.library.isExplicit
import com.rpm.player.phone.design.MarkedTitle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.downloads.AlbumDownloads.Row as DownloadRow
import com.rpm.player.core.downloads.AlbumDownloads.Standing
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Cover
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.SwipeKey
import com.rpm.player.phone.design.SwipeRow
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.iconOutline
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.library.SleeveSquare
import java.util.Locale

/**
 * The albums downloaded to this phone, and the way to remove them: the iPhone's `DownloadsPage`
 * (RPMUI/DownloadsPage.swift). It is opened from the downloads row of the profile tab and rises over the app as the
 * iPhone's sheet does.
 *
 * One album is removed with a swipe. Several are removed with Select, which puts a tick beside each row and a Remove key
 * under the list. Select all is there because clearing the phone before a trip is the usual reason to open this page.
 *
 * @param coverOf the cover of a downloaded album, from the collection on screen; null draws the empty sleeve.
 */
@Composable
fun DownloadsPage(
    rows: List<DownloadRow>,
    coverOf: (CloudAlbum) -> Cover?,
    images: ImageLoader,
    onRemove: (Set<String>) -> Unit,
    onClose: () -> Unit,
    /** Whether a record's title wears the E. The collection's copy of the record says, where it still has one. */
    explicitOf: (CloudAlbum) -> Boolean = { it.isExplicit },
) {
    BackHandler(onBack = onClose)
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var asking by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // The selection may only hold rows that still exist. An album that has finished being removed, or was removed
    // another way, must not be removed again.
    val ids = remember(rows) { rows.map { it.album.key }.toSet() }
    LaunchedEffect(ids) { picked = picked intersect ids }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.ground)
            // The page takes every touch, so none reaches the app under it.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize()) {
            Bar(
                selecting = selecting,
                canSelect = rows.isNotEmpty(),
                onClose = onClose,
                onSelect = {
                    selecting = !selecting
                    if (!selecting) picked = emptySet()
                },
            )
            Box(Modifier.weight(1f)) {
                if (rows.isEmpty()) {
                    Empty()
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "summary", contentType = "summary") {
                            BasicText(
                                summary(rows),
                                Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 6.dp),
                                style = display(12f).copy(color = Ink.white(0x88)),
                            )
                        }
                        items(rows, key = { it.album.key }, contentType = { "download" }) { row ->
                            val key = row.album.key
                            SwipeRow(
                                leading = null,
                                trailing = SwipeKey(Glyph.remove, stringResource(R.string.Rpm_Download_Remove), Ink.red) { onRemove(setOf(key)) },
                            ) {
                                Line(row, coverOf(row.album), images, selecting, picked = key in picked, explicit = explicitOf(row.album)) {
                                    if (selecting) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        picked = if (key in picked) picked - key else picked + key
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                RemoveBar(
                    all = picked.size == rows.size,
                    count = picked.size,
                    onAll = { picked = if (picked.size == rows.size) emptySet() else ids },
                    onRemove = { asking = true },
                )
            } else {
                Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            }
        }
    }

    if (asking) {
        val count = picked.size
        ConfirmSheet(
            question = if (count == 1) {
                stringResource(R.string.Phone_Download_RemoveQuestion)
            } else {
                stringResource(R.string.Phone_Downloads_RemoveQuestion, count.toString())
            },
            action = stringResource(R.string.Rpm_Download_Remove),
            onConfirm = {
                asking = false
                onRemove(picked)
                picked = emptySet()
                selecting = false
            },
            onDismiss = { asking = false },
        )
    }
}

/** Close, the page's name, and Select or Done. */
@Composable
private fun Bar(selecting: Boolean, canSelect: Boolean, onClose: () -> Unit, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BAR)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onClick = onClose, stretches = false, height = 34.dp, radius = 9.dp, inset = 16.dp, size = 13f) { style ->
            BasicText(stringResource(R.string.About_Close), style = style)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            BasicText(stringResource(R.string.Rpm_Downloads_Title), style = kicker())
        }
        GlassButton(
            onClick = onSelect,
            modifier = Modifier.alpha(if (canSelect) 1f else 0.4f),
            enabled = canSelect,
            stretches = false,
            height = 34.dp,
            radius = 9.dp,
            inset = 16.dp,
            size = 13f,
        ) { style ->
            BasicText(stringResource(if (selecting) R.string.Rpm_Downloads_Done else R.string.Rpm_Downloads_Select), style = style)
        }
    }
}

/** One album: its tick while selecting, its sleeve, its name and artist, and its size or how far it has got. */
@Composable
private fun Line(row: DownloadRow, cover: Cover?, images: ImageLoader, selecting: Boolean, picked: Boolean, explicit: Boolean, onTap: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.ground)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            selecting,
            enter = expandHorizontally(tween(200)) + fadeIn(tween(200)),
            exit = shrinkHorizontally(tween(200)) + fadeOut(tween(200)),
        ) {
            if (picked) {
                BasicText(Glyph.downloaded, style = icon(20f).copy(color = Color.White))
            } else {
                BasicText(CIRCLE, style = iconOutline(20f).copy(color = Ink.white(0x55)))
            }
        }
        SleeveSquare(cover, SLEEVE.dp, images, radius = 6.dp, glyphSize = 16f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MarkedTitle(row.album.album, explicit, display(15f).copy(color = Color.White))
            BasicText(row.album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0x88)))
        }
        BasicText(state(row), style = display(12f).copy(color = Ink.white(0x77), fontFeatureSettings = "tnum"))
    }
}

/** Select all or none, and Remove with how many are picked. */
@Composable
private fun RemoveBar(all: Boolean, count: Int, onAll: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BAR)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassButton(onClick = onAll, modifier = Modifier.weight(1f), height = 44.dp, radius = 12.dp) { style ->
            BasicText(stringResource(if (all) R.string.Rpm_Downloads_SelectNone else R.string.Rpm_Downloads_SelectAll), style = style)
        }
        GlassButton(
            onClick = onRemove,
            modifier = Modifier.weight(1f).alpha(if (count == 0) 0.4f else 1f),
            weight = Weight.Primary,
            enabled = count > 0,
            height = 44.dp,
            radius = 12.dp,
        ) { style ->
            BasicText(stringResource(R.string.Rpm_Downloads_RemoveCount, count.toString()), style = style)
        }
    }
}

/** No downloads yet: the download mark and what to do. */
@Composable
private fun Empty() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(Glyph.download, style = icon(34f).copy(color = Ink.white(0x55)))
        BasicText(stringResource(R.string.Rpm_Downloads_Empty), style = display(14f).copy(color = Ink.white(0xAA), textAlign = TextAlign.Center))
    }
}

@Composable
private fun summary(rows: List<DownloadRow>): String {
    val total = rows.sumOf { it.bytes }
    return if (rows.size == 1) {
        stringResource(R.string.Rpm_Downloads_SummaryOne, DownloadSizes.format(total))
    } else {
        stringResource(R.string.Rpm_Downloads_Summary, rows.size.toString(), DownloadSizes.format(total))
    }
}

@Composable
private fun state(row: DownloadRow): String = when (val standing = row.standing) {
    Standing.OnPhone -> DownloadSizes.format(row.bytes)
    is Standing.Downloading -> if (standing.preparing) stringResource(R.string.Rpm_Downloads_Preparing) else "${Math.round(standing.fraction * 100)}%"
    is Standing.Stuck -> stringResource(R.string.Rpm_Download_StuckCount, standing.have.toString(), standing.of.toString())
}

/**
 * Sizes as the iPhone shows them (`DownloadsPage.size`, `ByteCountFormatter` with binary counting): kilobytes under a
 * million bytes, otherwise megabytes or gigabytes. The profile tab's downloads row uses the same.
 */
object DownloadSizes {
    fun format(bytes: Long): String {
        val locale = Locale.getDefault()
        return when {
            bytes < 1_000_000 -> String.format(locale, "%d KB", Math.round(bytes / 1024.0))
            bytes < 1L shl 30 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(locale, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/** The edge of a row's sleeve. */
private const val SLEEVE = 48

/** Font Awesome's open circle, drawn from the regular face: a row not picked. */
private const val CIRCLE = ""

/** The bars over and under the list: the iPhone's thin material, drawn flat (no blur over a scrolling list). */
private val BAR = Color(0xF2141B27)
