package com.rpm.player.phone.supporter

/**
 * When the supporter pop-up ([SupporterNudgeCard]) asks a listener to become a supporter, counted in launches that
 * reach the signed-in app: every [EVERY]th launch for an account that does not pay, every [EVERY_FOR_TESTERS]th for a
 * closed beta tester, and never for a paying supporter. The iPhone's `SupporterNudge` has the same rule.
 */
object SupporterNudge {
    /** What the account has, as far as the pop-up is concerned. */
    enum class Plan {
        /** A paying supporter. Never asked. */
        Pays,

        /** A closed beta tester: the supporter features without paying. Asked less often. */
        Tester,

        /** Neither. */
        Free;

        companion object {
            /** The plan from the account's quota (`CloudQuota.supporter` and `tester`). A tester is a tester whatever
             * the supporter flag says. */
            fun of(supporter: Boolean, tester: Boolean): Plan = when {
                tester -> Tester
                supporter -> Pays
                else -> Free
            }
        }
    }

    /** The pop-up is due on launch 50, 100, 150 and so on for an account that does not pay. */
    const val EVERY = 50

    /** The pop-up is due on launch 100, 200, 300 and so on for a closed beta tester. */
    const val EVERY_FOR_TESTERS = 100

    /** Whether launch number [launch] (the first launch is 1) is one that may show the pop-up to anybody. Every
     * [EVERY_FOR_TESTERS]th launch is also an [EVERY]th one. */
    fun onLaunch(launch: Int): Boolean = launch > 0 && launch % EVERY == 0

    /**
     * Whether the pop-up shows on launch number [launch].
     *
     * @param plan the account's plan, or null while it is not known. Not known is never a yes: a supporter must never
     * be asked.
     */
    fun due(launch: Int, plan: Plan?): Boolean = launch > 0 && when (plan) {
        Plan.Free -> launch % EVERY == 0
        Plan.Tester -> launch % EVERY_FOR_TESTERS == 0
        Plan.Pays, null -> false
    }
}
