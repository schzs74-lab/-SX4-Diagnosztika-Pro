package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class ClearPermitTest {
    private val permit = ClearPermit(7, "7E8", setOf("P0420"), 1_000L)
    @Test fun sameSnapshotAndMonotonicDeadlineAreRequired() {
        permit.validate(7, "7E8", 1_001L, setOf("P0420"))
        permit.validate(7, "7E8", 1_000L + ClearPermit.LIFETIME_NANOS)
        for (time in listOf(999L, 1_001L + ClearPermit.LIFETIME_NANOS)) {
            assertThrows(IllegalStateException::class.java) { permit.validate(7, "7E8", time) }
        }
    }
    @Test fun differentSessionSourceOrCodeSetCannotReuseSnapshot() {
        assertThrows(IllegalStateException::class.java) { permit.validate(8, "7E8", 1_001L) }
        assertThrows(IllegalStateException::class.java) { permit.validate(7, "7E9", 1_001L) }
        assertThrows(IllegalStateException::class.java) { permit.validate(7, null, 1_001L) }
        assertThrows(IllegalStateException::class.java) { permit.validate(7, "7E8", 1_001L, setOf("P0420", "P0133")) }
    }
}
