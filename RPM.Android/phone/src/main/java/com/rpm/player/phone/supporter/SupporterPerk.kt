package com.rpm.player.phone.supporter

import androidx.annotation.StringRes
import com.rpm.player.core.R
import com.rpm.player.phone.design.Glyph

/**
 * One thing supporters get: its words and its glyph. [gold] marks the two perks that other people can see, the backdrop
 * and the mark; the rest are drawn in the app's cyan. The desktop (`SupporterPerks.All`), the Android TV set-up and the
 * iPhone list the same eight, in the same order.
 */
class SupporterPerk(@StringRes val words: Int, val glyph: String, val gold: Boolean = false) {
    companion object {
        /** All of them, storage first and the community last. */
        val ALL = listOf(
            SupporterPerk(R.string.Supporter_PerkStorage, Glyph.cloud),
            SupporterPerk(R.string.Supporter_PerkBitrate, Glyph.waveform),
            SupporterPerk(R.string.Supporter_PerkRecognition, Glyph.fingerprint),
            SupporterPerk(R.string.Supporter_PerkDj, Glyph.sliders),
            SupporterPerk(R.string.Supporter_PerkKaraoke, Glyph.microphone),
            SupporterPerk(R.string.Supporter_PerkBackdrop, Glyph.palette, gold = true),
            SupporterPerk(R.string.Supporter_PerkBadge, Glyph.heart, gold = true),
            SupporterPerk(R.string.Supporter_PerkCommunity, Glyph.people),
        )
    }
}
