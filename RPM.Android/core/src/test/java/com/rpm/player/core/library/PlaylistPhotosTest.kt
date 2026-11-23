package com.rpm.player.core.library

import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RpmPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaylistPhotosTest {
    @Test fun aPlainMoodWearsItsPhotographWashedInItsHue() {
        // Calm's hue is Azure, and a plain mood's seed is 977 * 10 + 5, which lands on it.
        assertEquals(PlaylistPhoto("calm-all.jpg", RpmPalette.RING[5]), PlaylistPhotos.of("Calm", null, 0))
    }

    @Test fun aGenreCutWearsItsOwn() {
        assertEquals("calm-rock.jpg", PlaylistPhotos.of("Calm", "Rock", 0)?.file)
    }

    @Test fun listsWithNoPhotographHaveNone() {
        assertNull(PlaylistPhotos.of("RightNow", null, 0))
        assertNull(PlaylistPhotos.of(null, null, 0))
        assertNull(PlaylistPhotos.of("Calm", "No such genre", 0))
    }

    /** Each of the app's own folders has a photograph behind its frame, and a folder somebody made has none. The
     * folder of all the moods washes the dance floor in the app's blue, so it is not the dance floor list's own cover,
     * and the image loader keeps the two apart (`theAppsFoldersHaveAPhotograph` on the iPhone). */
    @Test fun theAppsFoldersHaveAPhotograph() {
        val folder = File("src/main/assets/" + PlaylistPhotos.FOLDER)
        for (face in listOf(FolderFace.Moods, FolderFace.Decades, FolderFace.Mood("Calm"), FolderFace.Mood("Danceable"), FolderFace.Mood("Sleep"))) {
            val photo = PlaylistPhotos.folder(face)
            assertTrue("$face", photo != null && File(folder, photo.file).isFile)
        }
        assertNull(PlaylistPhotos.folder(FolderFace.Plain))
        assertEquals(GeneratedListLook.app.trio.mid, PlaylistPhotos.folder(FolderFace.Moods)?.tint)
        assertEquals(PlaylistPhotos.of("Danceable", null, 0), PlaylistPhotos.folder(FolderFace.Mood("Danceable")))
        assertEquals(PlaylistPhotos.of("Decade", null, 1980), PlaylistPhotos.folder(FolderFace.Decades))
        assertEquals("danceable-all.jpg", PlaylistPhotos.folder(FolderFace.Moods)?.file)
        assertNotEquals(
            PlaylistPhotoKeyer.of(PlaylistPhotos.folder(FolderFace.Moods)!!),
            PlaylistPhotoKeyer.of(PlaylistPhotos.folder(FolderFace.Mood("Danceable"))!!),
        )
    }

    /** The app's own look is the iPhone's: its blue, cyan and violet, a border of its blue at a quarter, and the most
     * readable of the three on that border. */
    @Test fun theAppsLookIsTheIPhones() {
        val app = GeneratedListLook.app
        assertEquals(Rgb.of(0x03, 0x71, 0xD0), app.trio.mid)
        assertEquals(Rgb.of(1, 28, 52), app.border)
        val best = listOf(app.trio.hot, app.trio.mid, app.trio.deep).maxBy { GeneratedListLook.contrast(it, app.border) }
        assertEquals(best, app.text)
    }

    /** Every file the table names is in the assets, so a list never asks for a picture that is not there. */
    @Test fun everyFileInTheTableIsShipped() {
        val folder = File("src/main/assets/" + PlaylistPhotos.FOLDER)
        val missing = PlaylistPhotoFiles.BY_CUT.values.filterNot { File(folder, it).isFile }
        assertTrue("missing: $missing", missing.isEmpty())
    }
}
