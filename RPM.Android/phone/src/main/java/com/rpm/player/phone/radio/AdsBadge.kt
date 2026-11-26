package com.rpm.player.phone.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.system

/**
 * The "AD" mark, cut out of a small pale tile (`AdsBadge` on iPhone). It is shown after the name of a station that
 * carries advertising ([com.rpm.player.core.radio.RadioStation.carriesAds]).
 */
@Composable
internal fun AdsBadge() {
    val said = stringResource(R.string.Radio_CarriesAds)
    Box(
        Modifier.size(19.dp, 13.dp).background(Ink.white(0x8C), RoundedCornerShape(2.5.dp)).semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("AD", style = system(9f, FontWeight.Bold).copy(color = Ink.ground))
    }
}

/**
 * The AD mark in the bottom-right corner of a station's square (`adsCorner` on iPhone), for a tile whose name is
 * centred or narrow and has no room for the mark beside it. The mark sits on a dark plate, because the square can be
 * any colour.
 */
@Composable
internal fun BoxScope.AdsCorner(shown: Boolean, inset: Dp = 6.dp) {
    if (!shown) return
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(inset)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(3.dp),
    ) {
        AdsBadge()
    }
}
