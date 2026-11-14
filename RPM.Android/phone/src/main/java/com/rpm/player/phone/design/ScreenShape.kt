package com.rpm.player.phone.design

import androidx.compose.ui.unit.Dp

/**
 * **A page is laid out for a square screen when it is about as wide as it is tall.** The iPhone's pages are written for
 * a tall screen: one column, read from the top. On a square screen, such as the Unihertz Titan's, that column shows
 * little before it runs under the tab bar, and half the width is empty. A square page puts its parts side by side
 * instead, as the Android TV app does on its wide screen.
 *
 * The test is on the page's own size, not the device's, so a phone held sideways, a folding phone opened out, a tablet
 * and a window in split screen are judged on what they show. Wider than square counts as square.
 *
 * Every page that has a square layout asks this one rule, so they all change at the same shape.
 */
fun isSquare(width: Dp, height: Dp): Boolean = width >= height * SQUARE_FROM

/** How wide a page must be, as a share of its height, to be laid out as square. 0.8 takes in the Titan (1.0) and an
 *  opened folding phone (about 0.8 to 0.9); a tall phone is about 0.45. */
private const val SQUARE_FROM = 0.8f
