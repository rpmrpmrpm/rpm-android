package com.rpm.player.phone.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.ActionSheet
import com.rpm.player.phone.design.Alert
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.DialogKey
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.KeyRole
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.PullDownMenu
import com.rpm.player.phone.design.PullItem
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.rememberHaptics
import kotlinx.coroutines.launch

/**
 * What the menu at the top right of a playlist page offers: the iPhone's `PlaylistMenu` (RPMUI/PlaylistPage.swift).
 * Which items a list may have depends on whose list it is and who made it, so the caller states it
 * ([PlaylistWrites.menu]).
 */
sealed interface PlaylistMenu {
    /** No menu: the favourite songs, and another account's playlist, where none of the items could be used. */
    data object None : PlaylistMenu

    /**
     * A playlist on this account. Add songs and Rearrange follow the page's own `adding` and `canReorder`, which can be
     * off for a list that holds songs this phone cannot see.
     *
     * @param onRename gives the list another name. It answers why it failed, or null.
     * @param onDelete deletes the list. It answers why it failed, or null.
     */
    class Own(val onRename: suspend (String) -> String?, val onDelete: suspend () -> String?) : PlaylistMenu

    /**
     * A list rpm made from the collection. Its songs and its name are rpm's, so adding, rearranging, editing and
     * deleting are shown greyed. [onNewList] shows another selection of the same list, and is null when the computer
     * sent no other selection: the item is then greyed too. It steps to the next slice of what the computer sent, so it
     * answers in the same frame and works without a network signal.
     */
    class Generated(val onNewList: (() -> Unit)?) : PlaylistMenu
}

/**
 * The key at the top right of a playlist page, across from the back key and in the same glass, where a record has its
 * heart (`PlaylistPage.corner` on iPhone): **Done** while the rows are being rearranged, so the way out of that mode is
 * where the way in was, and the menu otherwise. Nothing for a list with no menu.
 *
 * The menu drops from the key. A playlist on this account has Add songs, Rearrange, Edit playlist and Delete playlist;
 * a list rpm made has Generate a new list first, and the four greyed, so the menu has the same shape on every list.
 * Edit opens the name card ([PlaylistNameSheet]). Delete is asked first, at the foot of the screen; once the store has
 * dropped the list the page is left, and a failure is said in an alert while the page stays.
 *
 * @param name the list's name as shown, for the question before a delete and the name card.
 * @param top where the page's content starts: the back row ends there.
 * @param canAdd whether Add songs may be pressed: the list takes songs ([ListAdding]).
 * @param canRearrange whether Rearrange may be pressed: the phone sees all the songs, and there are two or more.
 */
@Composable
internal fun BoxScope.PlaylistCorner(
    menu: PlaylistMenu,
    name: String,
    page: Backdrop,
    top: Dp,
    bottomRoom: Dp,
    rearranging: Boolean,
    canAdd: Boolean,
    canRearrange: Boolean,
    onAdd: () -> Unit,
    onRearrange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var at by remember { mutableStateOf(Rect.Zero) }
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var askingDelete by remember { mutableStateOf(false) }
    // Why the last change from the menu failed, shown in an alert until it is closed.
    var trouble by remember { mutableStateOf<String?>(null) }

    if (!rearranging && menu == PlaylistMenu.None) return
    Box(
        Modifier.align(Alignment.TopEnd).padding(top = top - BACK_ROW).height(BACK_ROW).padding(end = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (rearranging) {
            val done = stringResource(R.string.CloudChip_StageDone)
            GlassKey(page, { haptics.pick(); onRearrange(false) }, Modifier.semantics { contentDescription = done }) {
                BasicText(Glyph.check, style = icon(15f).copy(color = Ink.white(0xE6)))
            }
        } else {
            val more = stringResource(R.string.Common_MoreOptions)
            GlassKey(
                page,
                { open = true },
                Modifier.onGloballyPositioned { at = it.boundsInRoot() }.semantics { contentDescription = more },
            ) {
                BasicText(Glyph.menu, style = icon(15f).copy(color = Ink.white(0xE6)))
            }
        }
    }

    if (open) {
        val own = menu is PlaylistMenu.Own
        val changes = listOf(
            PullItem(stringResource(R.string.Rpm_Playlist_AddSongs), glyph = Glyph.plus, enabled = own && canAdd, onPress = onAdd),
            PullItem(stringResource(R.string.Rpm_Queue_Rearrange), glyph = Glyph.upDown, enabled = own && canRearrange) {
                haptics.pick()
                onRearrange(true)
            },
            PullItem(stringResource(R.string.Rpm_PlaylistMenu_Edit), glyph = Glyph.pencil, enabled = own) { naming = true },
            PullItem(stringResource(R.string.Rpm_PlaylistMenu_Delete), glyph = Glyph.remove, enabled = own, destructive = true) { askingDelete = true },
        )
        val items = when (menu) {
            is PlaylistMenu.Generated -> listOf(
                PullItem(stringResource(R.string.Playlists_GenerateNew), glyph = Glyph.refresh, enabled = menu.onNewList != null) {
                    haptics.pick()
                    menu.onNewList?.invoke()
                },
            ) + changes
            else -> changes
        }
        OverChrome { PullDownMenu(page, at, items, onDismiss = { open = false }) }
    }

    val own = menu as? PlaylistMenu.Own
    OverChrome {
        AnimatedVisibility(naming && own != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            val rename = own?.onRename ?: return@AnimatedVisibility
            PlaylistNameSheet(NameErrand.Rename(name), page, bottomRoom, onSave = rename, onSaved = { naming = false }, onCancel = { naming = false })
        }
    }
    if (askingDelete && own != null) OverChrome {
        ActionSheet(
            page,
            stringResource(R.string.Rpm_PlaylistDelete_Title, name),
            stringResource(R.string.Rpm_PlaylistDelete_Body),
            listOf(
                DialogKey(stringResource(R.string.Rpm_PlaylistMenu_Delete), KeyRole.Destructive) {
                    askingDelete = false
                    scope.launch {
                        val failed = own.onDelete()
                        if (failed != null) {
                            trouble = failed
                        } else {
                            haptics.commit()
                            onBack()
                        }
                    }
                },
            ),
            cancel = DialogKey(stringResource(R.string.Common_Cancel), KeyRole.Cancel) { askingDelete = false },
        )
    }
    trouble?.let { said ->
        OverChrome {
            Alert(page, said, null, listOf(DialogKey(stringResource(R.string.Common_OK), KeyRole.Cancel) { trouble = null }))
        }
    }
}
