package com.rpm.player.core.cloud

import com.rpm.player.core.net.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How `cloud/quota` is read: the supporter features, the tester mark and the supporter's mark, also from a
 * server that does not send the newer fields. The iOS app checks the same answers (WireTests.swift). */
class CloudQuotaTest {

    private fun read(json: String) = WireJson.decodeFromString(CloudQuota.serializer(), json)

    @Test fun aTesterHasTheSupporterFeaturesACeilingAndNoMark() {
        val quota = read("""{"usedBytes":12000000000,"quotaBytes":107374182400,"fileCount":2400,"unlimited":false,"supporter":true,"tester":true}""")
        assertTrue(quota.supporter)
        assertTrue(quota.tester)
        assertFalse(quota.unlimited)
        assertEquals(107374182400, quota.quotaBytes)
        assertFalse(quota.wearsMark)
    }

    @Test fun aPayingSupporterWearsTheMark() {
        val quota = read("""{"usedBytes":1,"quotaBytes":9223372036854775807,"fileCount":1,"unlimited":true,"supporter":true,"tester":false}""")
        assertTrue(quota.supporter)
        assertTrue(quota.wearsMark)
    }

    @Test fun aServerWithoutTheSupporterFieldTakesItFromUnlimited() {
        val supporter = read("""{"usedBytes":1,"quotaBytes":9223372036854775807,"fileCount":1,"unlimited":true}""")
        assertTrue(supporter.supporter)
        assertFalse(supporter.tester)

        val free = read("""{"usedBytes":12000000000,"quotaBytes":100000000000,"fileCount":4210}""")
        assertFalse(free.supporter)
        assertFalse(free.wearsMark)
    }
}
