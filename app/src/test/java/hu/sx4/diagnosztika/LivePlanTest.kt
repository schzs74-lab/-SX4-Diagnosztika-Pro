package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class LivePlanTest {
    @Test fun fastPidsEveryCycleSlowPidsInterleaved() {
        val seq = LivePlan.sequence(setOf(0x0C, 0x06, 0x05, 0x0D))
        assertEquals(listOf(0x0C, 0x06, 0x05, 0x0C, 0x06, 0x0D), seq)
    }
    @Test fun onlySupportedPidsArePolled() {
        assertEquals(listOf(0x0C), LivePlan.sequence(setOf(0x0C, 0x42)))
        assertEquals(listOf(0x05), LivePlan.sequence(setOf(0x05)))
        assertTrue(LivePlan.sequence(emptySet()).isEmpty())
    }
    @Test fun everyPolledPidHasADecoder() {
        (LivePlan.fast + LivePlan.slow).forEach { pid ->
            val len = if (pid in setOf(0x0C, 0x10, 0x14, 0x15)) 2 else 1
            assertTrue(Obd.reading(pid, List(len) { 128 }).isNotEmpty())
        }
    }
    @Test fun displayOrderAndStaleLimits() {
        assertEquals(listOf(0x0C, 0x05), LivePlan.displayed(setOf(0x05, 0x0C)))
        assertTrue(LivePlan.staleAfterMs(0x0C) < LivePlan.staleAfterMs(0x05))
    }
}
