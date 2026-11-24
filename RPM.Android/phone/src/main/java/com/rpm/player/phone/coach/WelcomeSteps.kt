package com.rpm.player.phone.coach

/** A page of the welcome, in the order the pages show. The greeting is not a numbered step. The iPhone's `WelcomePage`. */
enum class WelcomePage { HELLO, LANGUAGE, LOOK, LAYOUT, SUPPORT, SPIN }

/**
 * Which numbered steps the welcome has now, and which one shows for the page asked for: the iPhone's `WelcomeSteps`.
 *
 * The support step is there only while the account is asked to support rpm, and the song step only when there is a
 * song to play. Both can change while the welcome is open: the account's standing can arrive late, and a purchase on
 * the support step ends the ask while that step is on screen. So the steps are worked out again on every draw, and a
 * page that is no longer among them shows the next step that is ([shown]).
 *
 * @param asks the account is asked to support rpm.
 * @param hasSong there is a song for the last step to play.
 */
class WelcomeSteps(asks: Boolean, hasSong: Boolean) {
    /** The numbered steps, in order. */
    val steps: List<WelcomePage> = buildList {
        add(WelcomePage.LANGUAGE)
        add(WelcomePage.LOOK)
        add(WelcomePage.LAYOUT)
        if (asks) add(WelcomePage.SUPPORT)
        if (hasSong) add(WelcomePage.SPIN)
    }

    /** The page on screen when [page] is asked for: [page] while it is among the steps (or the greeting), else the next
     * step after it. Null when no step is left after it. */
    fun shown(page: WelcomePage): WelcomePage? = if (page == WelcomePage.HELLO || page in steps) page else after(page)

    /** The step that follows [page], or null when it is the last. [page] itself need not be among the steps. */
    fun after(page: WelcomePage): WelcomePage? = steps.firstOrNull { it.ordinal > page.ordinal }

    /** The step before [page], or the greeting before the first. The Back key goes there. */
    fun before(page: WelcomePage): WelcomePage = steps.lastOrNull { it.ordinal < page.ordinal } ?: WelcomePage.HELLO

    /** The number of [page] in "Step 2 of 4". 1 for a page that is not a step. */
    fun number(page: WelcomePage): Int = steps.indexOf(page).coerceAtLeast(0) + 1
}
