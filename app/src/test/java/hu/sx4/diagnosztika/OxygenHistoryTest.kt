package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class OxygenHistoryTest {
    @Test fun channelsKeepTheirOwnTimestampsAndMissingDataIsNotZero() {
        val h = OxygenHistory()
        h.add(0x14, 1000, .2); h.add(0x15, 1400, .7); h.add(0x14, 1800, .8)
        assertEquals(listOf(1000L,1800L), h.points(0x14, 2000).map { it.atMs })
        assertEquals(listOf(1400L), h.points(0x15, 2000).map { it.atMs })
        assertTrue(h.points(99, 2000).isEmpty())
    }
    @Test fun errorsAndLongDelaysBreakTheLine() {
        val h = OxygenHistory()
        h.add(20, 0, .2); h.add(20, 100, .3); h.gap(20); h.add(20, 200, .4); h.add(20, 6000, .5)
        assertEquals(listOf(true,false,true,true), h.points(20, 6000).map { it.startsSegment })
    }
    @Test fun oldPointsExpireAndMemoryIsBounded() {
        val h = OxygenHistory(capacity=3)
        repeat(10) { h.add(20, it.toLong(), .5) }
        assertEquals(3, h.points(20, 10).size)
        assertTrue(h.points(20, 60_010).isEmpty())
        h.clear(); assertTrue(h.points(21, 0).isEmpty())
    }
    @Test fun malformedOrOutOfOrderSamplesAreRejected() {
        val h = OxygenHistory(); h.add(20, 10, .5)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.01, 1.3)) {
            assertThrows(IllegalArgumentException::class.java) { h.add(20, 11, value) }
        }
        assertThrows(IllegalArgumentException::class.java) { h.add(20, 9, .5) }
    }
    @Test fun fastPlanOnlyIncludesSupportedVoltageChannels() {
        assertEquals(listOf(20,21), LivePlan.oxygenSequence(setOf(12,20,21,24)))
        assertEquals(listOf(21), LivePlan.oxygenSequence(setOf(21,24)))
        assertTrue(LivePlan.oxygenSequence(setOf(24)).isEmpty())
    }
}
