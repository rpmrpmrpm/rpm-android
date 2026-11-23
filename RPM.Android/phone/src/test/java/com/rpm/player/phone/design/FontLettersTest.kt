package com.rpm.player.phone.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The character map of the title face, read by [fontLetters], which decides which letters of a title it draws. */
class FontLettersTest {
    private val thiamine = fontLetters(File("src/main/res/font/thiamine_rpm.otf").readBytes())
    private val cabin = fontLetters(File("src/main/res/font/cabin_medium.ttf").readBytes())

    /** Thiamine RPM has Latin letters with the accents of the app's languages, and nothing else. */
    @Test fun theTitleFaceHasLatinOnly() {
        assertEquals(189, thiamine.size)
        for (letter in "settings bibliothèque Einstellungen música œ") assertTrue("$letter", letter.code in thiamine)
        for (letter in "设置音乐收藏") assertFalse("$letter", letter.code in thiamine)
    }

    /** The body face is read too, so the reader is not fitted to one file. */
    @Test fun theBodyFaceHasLatinAndNoChinese() {
        for (letter in "Default Vinyl ß é") assertTrue("$letter", letter.code in cabin)
        assertFalse(0x8BBE in cabin)
    }
}
