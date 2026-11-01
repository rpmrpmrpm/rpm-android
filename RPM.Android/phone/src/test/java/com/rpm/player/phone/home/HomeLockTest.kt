package com.rpm.player.phone.home

import com.rpm.player.core.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The line on the locked home screen, for an empty or small collection, owned or borrowed. */
class HomeLockTest {
    @Test
    fun anEmptyOwnCollectionSaysHowToFillIt() {
        assertEquals(R.string.Rpm_Library_Empty, lockLine(have = 0, lent = false))
    }

    @Test
    fun anEmptyBorrowedCollectionDoesNotTellThePersonToUpload() {
        assertEquals(R.string.Rpm_Share_EmptyShelf, lockLine(have = 0, lent = true))
    }

    @Test
    fun aSmallOwnCollectionSaysWhereToAddMusic() {
        assertEquals(R.string.Rpm_HomeLock_Body, lockLine(have = 9, lent = false))
    }

    @Test
    fun aSmallBorrowedCollectionOnlySaysWhatHomeIs() {
        assertEquals(R.string.HomeLock_Body, lockLine(have = 9, lent = true))
    }
}
