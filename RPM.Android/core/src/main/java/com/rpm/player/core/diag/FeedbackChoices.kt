package com.rpm.player.core.diag

import com.rpm.player.core.R

/**
 * The kinds and areas the Send feedback screen offers, with their strings. The values are the desktop's
 * (`FeedbackKind`, `FeedbackArea` in rpm-desktop), because the admin site groups reports by them and the server does
 * not check them.
 */
object FeedbackChoices {
    class Choice(val value: String, val label: Int)

    /** A problem first: it is the one that can be costing somebody their afternoon. */
    val kinds = listOf(
        Choice("problem", R.string.Feedback_KindProblem),
        Choice("idea", R.string.Feedback_KindIdea),
    )

    fun placeholder(kind: String): Int =
        if (kind == "idea") R.string.Feedback_PlaceholderIdea else R.string.Feedback_PlaceholderProblem

    /** "Other" first, so someone who does not know which part a thing belongs to need not guess. */
    val phoneAreas = listOf(
        Choice("other", R.string.Feedback_AreaOther),
        Choice("playback", R.string.Feedback_AreaPlayback),
        Choice("library", R.string.Feedback_AreaLibrary),
        Choice("downloads", R.string.Feedback_AreaDownloads),
        Choice("cloud", R.string.Feedback_AreaCloud),
        Choice("radio", R.string.Feedback_AreaRadio),
        Choice("casting", R.string.Feedback_AreaCasting),
        Choice("interface", R.string.Feedback_AreaInterface),
    )

    val tvAreas = listOf(
        Choice("other", R.string.Feedback_AreaOther),
        Choice("playback", R.string.Feedback_AreaPlayback),
        Choice("library", R.string.Feedback_AreaLibrary),
        Choice("radio", R.string.Feedback_AreaRadio),
        Choice("interface", R.string.Feedback_AreaInterface),
    )
}

/** The size a screenshot is drawn at before it is sent: the long edge at most [MAX_EDGE] pixels, and at most
 * [MAX_BYTES] of PNG, the server's limit. */
object ShotFit {
    const val MAX_EDGE = 1600
    const val MAX_BYTES = 1_500_000

    /** The scale that brings a [width] by [height] picture down to [maxEdge], or 1. */
    fun scale(width: Int, height: Int, maxEdge: Int): Double {
        val long = maxOf(width, height)
        return if (long <= maxEdge || long <= 0) 1.0 else maxEdge.toDouble() / long
    }
}

/** The screenshot a Send feedback screen opens with: a new object for every opening. */
class FeedbackShot(val png: ByteArray?)
