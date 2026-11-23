package com.rpm.player.phone.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rpm.player.core.R

/**
 * What the library's walls know about the collection they show beyond its rows: whether a pull's read is running
 * ([refreshing]), what a pull does ([onRefresh], the collection read again with the one on screen kept until the new
 * one is in), and whose the collection is when it is borrowed ([lender], null on the person's own). The playlists view
 * reads only the lists again on a pull ([onReread]), as the iPhone's does.
 *
 * [owner] is the name of the person whose collection it is, on their own collection too: the account's own profile
 * name, or the lender's. Null when no name is known: unlike [lender], it has no word for an unknown person. The
 * playlists view names it under each list somebody made (`ownerName` on iPhone).
 */
class WallReading(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
    val lender: String?,
    val onReread: suspend () -> Unit,
    val owner: String? = null,
)

/** The smallest a pinch makes the album wall's covers, and the largest it makes any wall's (`bounded` on iPhone). */
internal const val WALL_SMALLEST = 60.0
private const val PINCH_LARGEST = 900.0

/** The stride a pinch has reached: the size it began at, scaled by the spread of the fingers, kept within bounds. */
internal fun pinchedStride(from: Double, zoom: Float, smallest: Double): Double = (from * zoom).coerceIn(smallest, PINCH_LARGEST)

/** The counts line, with whose collection it is in front on a borrowed one: "Shared by Sam  ·  412 albums". */
@Composable
internal fun lent(lender: String?, counts: String): String =
    if (lender == null) counts else stringResource(R.string.Rpm_Library_SharedBy, lender) + "  ·  " + counts
