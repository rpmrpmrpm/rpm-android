package com.rpm.player.core.frames

import androidx.compose.runtime.Immutable
import com.rpm.player.core.library.FolderFace
import com.rpm.player.core.library.GeneratedListLook
import com.rpm.player.core.library.TrackList

/**
 * A generated list as the squares draw it: its name and the colours of its frame. It matches the iPhone's
 * `FramedList` (RPMSharedUI/FramedList.swift). The playlists view, its covers and a list's own page all use it, so a
 * list never gets two different covers.
 *
 * @param name the list's name in the reader's language, lettered down the frame's band.
 */
@Immutable
data class FramedList(val name: String, val look: GeneratedListLook.Look) {
    companion object {
        /** The frame for [list], or null for a list somebody made, or a kind this build has no colours for. */
        fun of(list: TrackList?, name: String): FramedList? = list?.let(GeneratedListLook::of)?.let { FramedList(name, it) }

        /**
         * The name and inks of a folder, for its frame ([FolderFrameOverlay]) (`FramedList.folder` on the iPhone).
         * One of the app's own folders has a photograph of its own behind the frame's window
         * ([PlaylistPhotos.folder][com.rpm.player.core.library.PlaylistPhotos.folder]); a folder somebody made has a
         * still room in its colours there ([ListRoom]).
         *
         * One mood's folder wears the colours of that mood's list of the whole collection, so the folder and what is
         * in it look alike. The folder of the decades wears the 1980s'. The folder of all the moods and every folder
         * somebody made wear the app's own colours ([GeneratedListLook.app]). Null only for a mood this build has no
         * colours for.
         */
        fun folder(face: FolderFace, name: String): FramedList? = when (face) {
            FolderFace.Plain, FolderFace.Moods -> GeneratedListLook.app
            FolderFace.Decades -> GeneratedListLook.of("Decade", null, 1980)
            is FolderFace.Mood -> GeneratedListLook.of(face.kind, null, 0)
        }?.let { FramedList(name, it) }
    }
}
