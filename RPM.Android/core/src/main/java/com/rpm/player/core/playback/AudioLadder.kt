package com.rpm.player.core.playback

import com.rpm.player.core.net.Reach

/**
 * The rates a track is served at, and who may have which. It is the iPhone app's `AudioLadder` (RPMKit/Wire), which
 * is ported from the store's own `AudioLadder.cs`. Two things have to agree on it: the store decides what an account
 * may be given, and this side decides what to ask for. A screen that offers what the store will not serve has a
 * setting that does nothing.
 *
 * What supporting rpm buys is the lossless original, not a wider choice of lossy rates.
 */
object AudioLadder {
    /** Smallest first. */
    val rates = listOf(96, 128, 192, 256, 320)

    /** What a free account is served, whatever it asks for. */
    const val FREE_KILOBITS = 128

    /** The untranscoded file. What supporting rpm buys. */
    const val ORIGINAL = 0

    /**
     * The rate used on a network where data is limited (Data Saver here, Low Data Mode on iPhone). It is the same
     * rendition a free account is served, so it certainly exists at the store.
     */
    const val FRUGAL = 128

    /**
     * The rates one account may choose between, and whether the choice is theirs to make. An account whose plan has
     * not been read is offered [UNREAD], the whole ladder: the store caps the serving anyway, and telling a supporter
     * they are on the free plan because a request timed out is the worse of the two mistakes.
     *
     * @property rungs the rates on offer, in the order a chooser lists them.
     * @property capped whether the choice has been taken away, which is what a screen greys out.
     */
    data class RateOffer(val rungs: List<Int>, val capped: Boolean) {
        /** The rate this offer settles on for a remembered one. A rate not in the offer (a supporter's 320 read back
         * after the support ended) gives the first rung, which for [FREE] is the only one. */
        fun settles(on: Int): Int = if (on in rungs) on else rungs[0]

        /**
         * What to ask the store for, given both remembered rates and the network. Which setting applies depends on
         * the network, whether the account may have it depends on the plan, and whether the person asked the phone to
         * save data depends on the system. They are answered together, so the box on the screen and the stream agree.
         *
         * Data Saver caps the chosen rate at [FRUGAL]. Not with `min` alone: the original is zero, and `min(0, 128)`
         * would let the original through.
         */
        fun settles(onWifi: Int, onCellular: Int, reach: Reach): Int {
            val asked = if (reach.costsData) onCellular else onWifi
            if (!reach.isConstrained) return settles(asked)
            val rationed = if (asked == ORIGINAL) FRUGAL else minOf(asked, FRUGAL)
            return settles(rationed)
        }

        companion object {
            /** Every rung, the original first, then the other rates from the highest to the lowest. */
            val WHOLE = RateOffer(listOf(ORIGINAL, 320, 256, 192, 128, 96), capped = false)

            /** The one rung a free account is served, and nothing else to pick from. */
            val FREE = RateOffer(listOf(FREE_KILOBITS), capped = true)

            /** Before the plan of the account is known, and when reading it failed: the whole ladder, on purpose. */
            val UNREAD = WHOLE

            fun of(isSupporter: Boolean): RateOffer = if (isSupporter) WHOLE else FREE

            /** The rate a player with no setting of its own asks for (the TV): the original for a supporter or a
             * closed beta tester, both marked supporters on the quota, the one rung a free account is served, and the
             * original while the plan is not known, because the store caps what it serves either way. */
            fun forPlan(isSupporter: Boolean?): Int = (isSupporter?.let { of(it) } ?: UNREAD).settles(ORIGINAL)
        }
    }
}

/**
 * The rate the player asks the store for, written by the app on the main thread and read on a loader thread as a
 * track is opened ([TrackSources]). The phone writes it from its two settings and the network (`settleTheRate` on
 * iPhone). The TV writes it from the account's plan alone (TvApplication.settleTheRate).
 */
class StreamRate(kilobits: Int) {
    @Volatile var kilobits: Int = kilobits
}
