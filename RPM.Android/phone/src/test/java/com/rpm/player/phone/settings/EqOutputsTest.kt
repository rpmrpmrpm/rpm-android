package com.rpm.player.phone.settings

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which speakers an equaliser curve is kept for. */
class EqOutputsTest {
    private val speaker = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Titan")
    private val earpiece = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Titan")
    private val buds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds Pro", "AA:BB")
    private val sco = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds Pro", "AA:BB")
    private val wired = OutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "")

    @Test
    fun With_nothing_connected_the_music_goes_to_the_phones_speaker() {
        assertEquals(EqOutput("speaker", "Titan"), EqOutputs.pick(listOf(earpiece, speaker), null, "Titan"))
        assertEquals(EqOutput("speaker", "Model"), EqOutputs.pick(emptyList(), null, "Model"))
    }

    @Test
    fun Bluetooth_wins_over_wired_and_the_speaker_and_calls_are_not_music() {
        assertEquals(EqOutput("bluetooth:Buds Pro", "Buds Pro"), EqOutputs.pick(listOf(speaker, sco, wired, buds), null, "Titan"))
        assertEquals(EqOutput("wired:", "Titan"), EqOutputs.pick(listOf(speaker, sco, wired), null, "Titan"))
    }

    @Test
    fun The_route_the_phone_names_wins_and_takes_its_name_from_the_device_list() {
        val routed = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
        assertEquals(EqOutput("speaker", "Titan"), EqOutputs.pick(listOf(speaker, buds), routed, "Titan"))
        val toBuds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "", "AA:BB")
        assertEquals(EqOutput("bluetooth:Buds Pro", "Buds Pro"), EqOutputs.pick(listOf(speaker, buds), toBuds, "Titan"))
    }

    @Test
    fun Two_headphones_keep_two_curves() {
        val other = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Speaker Box")
        assertFalse(EqOutputs.pick(listOf(buds), null, "Titan").key == EqOutputs.pick(listOf(other), null, "Titan").key)
    }

    @Test
    fun Casting_has_one_curve_and_no_name() {
        assertTrue(EqOutput.CAST.casting)
        assertEquals(null, EqOutput.CAST.label)
        assertFalse(EqOutputs.pick(listOf(speaker), null, "Titan").casting)
    }
}
