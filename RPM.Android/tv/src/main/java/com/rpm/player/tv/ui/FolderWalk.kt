package com.rpm.player.tv.ui

import com.rpm.player.core.library.PlaylistRow
import java.util.Locale

/**
 * Where the playlists screen stands in the playlist folders, and which card takes the focus when a folder is shown.
 * OK on a folder steps into it ([into]); Back steps up one folder ([up]) and leaves the screen only at the top.
 *
 * @param at the folder asked for, outermost first; empty at the top. The screen shows the folder
 * [PlaylistBrowse.of][com.rpm.player.core.library.PlaylistBrowse.of] puts the reader in, which is [at] unless that
 * folder has emptied.
 * @param cameFrom the folder just left by Back. Its card in [at] takes the focus, so the reader is back where they
 * were. Null after stepping into a folder, where the first card takes the focus.
 */
data class FolderWalk(val at: List<String> = emptyList(), val cameFrom: List<String>? = null) {

    /** Into the folder [path]. */
    fun into(path: List<String>): FolderWalk = FolderWalk(path, null)

    /** Up one folder from [standing], the folder shown. Null at the top, where Back leaves the screen. */
    fun up(standing: List<String>): FolderWalk? = if (standing.isEmpty()) null else FolderWalk(standing.dropLast(1), standing)

    /** Which of [rows] takes the focus: the folder come back from ([cameFrom]), else the first. Folder names are
     * compared ignoring case, as the folders themselves are ([PlaylistFolderTree][com.rpm.player.core.library.PlaylistFolderTree]). */
    fun focusIndex(rows: List<PlaylistRow>): Int {
        val from = cameFrom ?: return 0
        val found = rows.indexOfFirst { it is PlaylistRow.Folder && same(it.folder.path, from) }
        return if (found < 0) 0 else found
    }

    private fun same(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a.indices.all { a[it].lowercase(Locale.getDefault()) == b[it].lowercase(Locale.getDefault()) }
}
