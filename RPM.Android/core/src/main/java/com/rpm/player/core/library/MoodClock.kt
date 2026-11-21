package com.rpm.player.core.library

/**
 * Which mood the hour asks for. The desktop's `MoodClock`, with the same bands.
 *
 * The home banner shows one generated list, and the clock decides which mood it is: something calm in the morning,
 * something to fall asleep to late at night.
 *
 * Every band names all five moods, in its own order of preference. A collection only has the mood lists its measured
 * music can fill, so the caller takes the first mood in the band's order that the collection has ([MoodPick]).
 *
 * The hour is local time, read when home is built. A home that stays on screen past the edge of a band keeps the card
 * it was built with.
 */
object MoodClock {
    // One list per band, so two hours in the same band give the same list.
    private val NIGHT = listOf(Mood.Sleep, Mood.Calm, Mood.Focus, Mood.Danceable, Mood.Energetic)
    private val MORNING = listOf(Mood.Calm, Mood.Focus, Mood.Sleep, Mood.Energetic, Mood.Danceable)
    private val DAY = listOf(Mood.Focus, Mood.Calm, Mood.Energetic, Mood.Danceable, Mood.Sleep)
    private val EVENING = listOf(Mood.Energetic, Mood.Danceable, Mood.Focus, Mood.Calm, Mood.Sleep)
    private val DANCING = listOf(Mood.Danceable, Mood.Energetic, Mood.Focus, Mood.Calm, Mood.Sleep)

    /**
     * The moods to offer at [hour], best first. An hour outside 0 to 23 is wrapped into that range, so a caller that
     * adds or takes away hours cannot pass one that is refused.
     */
    fun order(hour: Int): List<Mood> = when (Math.floorMod(hour, 24)) {
        // From ten at night through the small hours: Sleep first.
        in 22..23, in 0..4 -> NIGHT
        // Early morning: Calm first.
        in 5..9 -> MORNING
        // The working day: Focus first.
        in 10..16 -> DAY
        // Evening: Energetic first.
        in 17..19 -> EVENING
        // Between the evening and the night: Danceable first.
        else -> DANCING
    }

    /** The mood [hour] asks for before anything is known about what the collection can fill: the head of [order]. */
    fun best(hour: Int): Mood = order(hour).first()
}
