package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class LiveSampleTest {
    @Test fun voltageRpmAndTrimsAreNumericWithExplicitUnits() {
        assertEquals(.415, LiveSample.decode(20, listOf(83,128)).value!!, .000001)
        assertEquals("V", LiveSample.decode(21, listOf(122,128)).unit)
        assertEquals(709.25, LiveSample.decode(12, listOf(0x0B,0x15)).value!!, .000001)
        assertEquals(-7.8125, LiveSample.decode(7, listOf(118)).value!!, .000001)
    }
    @Test fun statusFieldsRemainTextRatherThanFabricatedMeasurements() {
        val status = LiveSample.decode(3, listOf(2,0))
        assertNull(status.value); assertEquals(listOf(2,0), status.data)
        assertTrue(status.reading.contains("zárt kör"))
    }
    @Test fun invalidPayloadCannotBecomeASample() {
        assertThrows(IllegalArgumentException::class.java) { LiveSample.decode(20, listOf(83)) }
        assertThrows(IllegalStateException::class.java) { LiveSample.decode(99, listOf(1)) }
    }
    @Test fun everyDemoSampleIsDecodableAndOxygenVoltageStaysInRange() {
        for (at in 0L..10_000 step 100) for (pid in DemoSamples.supported) {
            val s = LiveSample.decode(pid, DemoSamples.data(pid, at))
            if (pid in setOf(20,21)) assertTrue(s.value!! in 0.0..1.275)
        }
    }
}
