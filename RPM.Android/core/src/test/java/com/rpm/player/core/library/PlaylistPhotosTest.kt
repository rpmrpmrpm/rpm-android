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

    /** Each of the app's own folders has a photograph behind its frame, from the folders' own assets, and a folder
     * somebody made has none. No folder wears a list's photograph: the folder holds those lists. Each is washed in its
     * folder's colour, the folder of all the moods in the app's blue (`theAppsFoldersHaveAPhotograph` on the
     * iPhone). */
    @Test fun theAppsFoldersHaveAPhotograph() {
        val listFiles = PlaylistPhotoFiles.BY_CUT.values.toSet()
        for ((key, file) in FolderPhotoFiles.files) assertTrue(key, file !in listFiles)

        val folder = File("src/main/assets/" + PlaylistPhotos.FOLDER_PHOTOS)
        for (face in listOf(FolderFace.Moods, FolderFace.Decades, FolderFace.Mood("Calm"), FolderFace.Mood("Danceable"), FolderFace.Mood("Sleep"))) {
            val photo = PlaylistPhotos.folder(face)
            assertTrue("$face", photo != null && photo.assets == PlaylistPhotos.FOLDER_PHOTOS && File(folder, photo.file).isFile)
        }
        assertNull(PlaylistPhotos.folder(FolderFace.Plain))
        assertEquals(PlaylistPhoto("folder-moods.jpg", GeneratedListLook.app.trio.mid, PlaylistPhotos.FOLDER_PHOTOS), PlaylistPhotos.folder(FolderFace.Moods))
        assertEquals(PlaylistPhotos.of("Calm", null, 0)?.tint, PlaylistPhotos.folder(FolderFace.Mood("Calm"))?.tint)
        assertEquals(PlaylistPhotos.of("Decade", null, 1980)?.tint, PlaylistPhotos.folder(FolderFace.Decades)?.tint)
        assertEquals("folder-decades.jpg", PlaylistPhotos.folder(FolderFace.Decades)?.file)
    }

    /** The image loader's key names the assets folder, so a folder's photograph and a list's with the same file name
     * and colour are two pictures. */
    @Test fun theKeyTellsTheAssetsFoldersApart() {
        val tint = RpmPalette.RING[5]
        assertNotEquals(
            PlaylistPhotoKeyer.of(PlaylistPhoto("x.jpg", tint)),
            PlaylistPhotoKeyer.of(PlaylistPhoto("x.jpg", tint, PlaylistPhotos.FOLDER_PHOTOS)),
        )
    }

    /** Every file the folders' table names is in the assets. */
    @Test fun everyFolderFileIsShipped() {
        val folder = File("src/main/assets/" + PlaylistPhotos.FOLDER_PHOTOS)
        val missing = FolderPhotoFiles.files.values.filterNot { File(folder, it).isFile }
        assertTrue("missing: $missing", missing.isEmpty())
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
