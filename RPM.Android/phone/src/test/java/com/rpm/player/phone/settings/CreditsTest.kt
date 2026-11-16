package com.rpm.player.phone.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The iPhone's CreditsTests, for the Android phone's table. */
class CreditsTest {

    /** Unit tests run in the module's folder, so the assets are read where the generator wrote them. */
    private fun shipped(file: String) = File("src/main/assets/credits", file)

    @Test fun everyLicenceAnEntryNamesExists() {
        for (section in Credits.sections) for (entry in section.entries) {
            assertFalse("${entry.name} names no licence", entry.licenses.isEmpty())
            for (name in entry.licenses) assertNotNull("${entry.name} names $name, which is not in the map", Credits.licenses[name])
        }
    }

    @Test fun everyLicenceTextShipped() {
        for ((key, licence) in Credits.licenses) {
            val file = licence.file
            if (file == null) {
                val termsGiven = Credits.sections.any { s -> s.entries.any { key in it.licenses && it.termsUrl != null } }
                assertTrue("$key has neither a text nor an address", licence.url != null || termsGiven)
                continue
            }
            assertTrue("$key names $file, which is not in the assets", shipped(file).exists())
            assertTrue("$file is too short to be a licence", shipped(file).readText().length > 200)
        }
    }

    /** The folder holds only the texts the table names, as the generator promises. */
    @Test fun noLicenceTextShipsUnnamed() {
        val named = Credits.licenses.values.mapNotNull { it.file }.toSet()
        val there = File("src/main/assets/credits").list()!!.toSet()
        assertEquals(named, there)
    }

    /** What the iPhone carries and this phone does not: SQLite, ONNX and the desktop's own ffmpeg with LAME. */
    @Test fun theScreenSaysNothingAboutWhatThePhoneDoesNotCarry() {
        val named = Credits.sections.flatMap { s -> s.entries.map { it.name } }.toSet()
        for (name in listOf("SQLite", "ONNX Runtime", "LAME", ".NET", "Uno Platform", "SkiaSharp", "TagLib#")) {
            assertFalse("$name is credited on the phone, which does not ship it", name in named)
        }
    }

    @Test fun theHoldersNameReplacesThePlaceholder() {
        val mit = "MIT License\n\nCopyright (c) <year> <copyright holders>\n\nPermission is hereby granted"
        val filled = Credits.fill(mit, "Copyright 2019 Square, Inc.")
        assertEquals("MIT License\n\nCopyright 2019 Square, Inc.\n\nPermission is hereby granted", filled)
    }

    @Test fun aPlaceholderWithNobodyToNameIsRemoved() {
        val mit = "MIT License\r\n\r\nCopyright (c) <year> <copyright holders>\r\n\r\nPermission is hereby granted"
        assertEquals("MIT License\n\nPermission is hereby granted", Credits.fill(mit, null))
    }

    @Test fun aLicenceWithRealHoldersIsLeftAlone() {
        val apache = "   Copyright [yyyy] [name of copyright owner]\n\n   Licensed under the Apache License"
        assertEquals(apache, Credits.fill(apache, "Copyright 2026 Someone Else"))
    }
}
