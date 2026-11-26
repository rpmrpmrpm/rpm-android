package com.rpm.player.phone.supporter

/**
 * When the supporter pop-up ([SupporterNudgeCard]) asks a listener to become a supporter: on every [EVERY]th launch that
 * reaches the signed-in app, and only for an account that does not pay. The iPhone's `SupporterNudge` has the same rule.
 */
object SupporterNudge {
    /** The pop-up is due on launch 50, 100, 150 and so on. */
    const val EVERY = 50

    /** Whether launch number [launch] (the first launch is 1) is one that may show the pop-up. */
    fun onLaunch(launch: Int): Boolean = launch > 0 && launch % EVERY == 0

    /**
     * Whether the pop-up shows on launch number [launch].
     *
     * @param supporter whether the account pays for its support (`CloudQuota.wearsMark`), or null while that is not
     * known. A closed beta tester does not pay, so is asked, as the settings page and the welcome offer it the
     * subscription. Not known is never a yes: a supporter must never be asked.
     */
    fun due(launch: Int, supporter: Boolean?): Boolean = supporter == false && onLaunch(launch)
}
