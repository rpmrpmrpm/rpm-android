package com.rpm.player.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rpm.player.core.R

/**
 * What the record looks like for an album that has no picture of its own disc. It is the desktop's `DiscStandIn`
 * setting and the iPhone's of the same name, with the CD as the default on the phone and the TV.
 */
enum class DiscStandIn(val stored: String) {
    /** A plain CD ([BlankDisc]), drawn as a photographed CD is, with the CD's rim and hub over it. */
    Cd("cd"),

    /** The app's own drawn vinyl, with the sleeve on its label. */
    Vinyl("vinyl"),
}

/**
 * The plain CD that stands in for a record with no disc picture, when [DiscStandIn.Cd] is chosen (the default).
 *
 * Each app wraps [bitmap] in its own `ScannedDisc`, which reads it as a whole CD: it is opaque at four fifths of the
 * radius ([DiscScan]). Every screen then draws it exactly as it draws a photographed CD: the pressing and the label
 * go, the CD's rim, hub and spindle go on top, and the record does not wobble. No screen needs a separate path for it.
 *
 * The picture (`cd_blank`) is the desktop's `Assets/cd-blank.png`: a scan of the data side of a CD, cut round, with
 * the clear centre left transparent.
 */
object BlankDisc {
    /** Where the choice is kept, in each app's own settings file. The iPhone and the Apple TV use the same key. */
    const val KEY = "look.discStandIn"

    /** The choice when none was made. */
    val INITIAL = DiscStandIn.Cd

    /** The choice that [stored] names, or [INITIAL]. */
    fun read(stored: String?): DiscStandIn = DiscStandIn.entries.firstOrNull { it.stored == stored } ?: INITIAL

    @Volatile
    private var held: Bitmap? = null

    /**
     * The picture, decoded once and kept, in software so its pixels can be read. Null if it cannot be decoded; the
     * record then wears the vinyl, which is always there.
     */
    fun bitmap(context: Context): Bitmap? =
        held ?: BitmapFactory.decodeResource(context.resources, R.drawable.cd_blank)?.also { held = it }
}
