package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class LivePlanTest {
    @Test fun fastPidsEveryCycleSlowPidsInterleaved() {
        val seq = LivePlan.sequence(setOf(0x0C, 0x06, 0x05, 0x0D))
        assertEquals(listOf(0x0C, 0x06, 0x05, 0x0C, 0x06, 0x0D), seq)
    }
    @Test fun onlySupportedPidsArePolled() {
        assertEquals(listOf(0x0C), LivePlan.sequence(setOf(0x0C, 0x99)))
        assertEquals(listOf(0x05), LivePlan.sequence(setOf(0x05)))
        assertTrue(LivePlan.sequence(emptySet()).isEmpty())
    }
    @Test fun everyPolledPidHasADecoder() {
        (LivePlan.fast + LivePlan.slow).forEach { pid ->
            assertTrue("%02X".format(pid), Obd.decodable(pid))
            assertTrue(listOf(1, 2, 4).any { len -> runCatching { Obd.reading(pid, List(len) { 128 }) }.isSuccess })
        }
    }
    @Test fun displayOrderAndStaleLimits() {
        assertEquals(listOf(0x0C, 0x05), LivePlan.displayed(setOf(0x05, 0x0C)))
    }
    @Test fun staleLimitFollowsEachPidsOwnRefreshPeriod() {
        val seq = LivePlan.sequence(setOf(0x0C, 0x06, 0x05, 0x0D))   // 0C 06 05 0C 06 0D
        assertEquals(3, LivePlan.pollsBetween(0x0C, seq))
        assertEquals(6, LivePlan.pollsBetween(0x05, seq))
        assertEquals(2_000, LivePlan.staleAfterMs(0x0C, seq, 200))          // floor
        assertEquals(7_500, LivePlan.staleAfterMs(0x0C, seq, 1_000))
        assertTrue(LivePlan.staleAfterMs(0x0C, seq, 500) < LivePlan.staleAfterMs(0x05, seq, 500))
        assertThrows(IllegalArgumentException::class.java) { LivePlan.pollsBetween(0x42, seq) }
    }
}
