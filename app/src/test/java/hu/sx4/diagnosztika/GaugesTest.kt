package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class GaugesTest {
    private val stft = Gauges.engineDials.single { it.pid == 0x06 }

    @Test fun formatsWithDecimalCommaAndSignOnCentredScales() {
        assertEquals("780", Gauges.format(Gauges.rpm, 780.0))
        assertEquals("+3,1", Gauges.format(stft, 3.125))
        assertEquals("-5,5", Gauges.format(stft, -5.47))
        assertEquals("0,0", Gauges.format(stft, 0.04))
        assertEquals("0,62", Gauges.format(Gauges.mixtureDials.first(), 0.62))
    }

    @Test fun needlePositionIsClampedToTheScale() {
        assertEquals(0.0, Gauges.fraction(Gauges.rpm, -100.0), 0.0)
        assertEquals(1.0, Gauges.fraction(Gauges.rpm, 9000.0), 0.0)
        assertEquals(0.5, Gauges.fraction(stft, 0.0), 1e-9)
    }

    @Test fun readingNeverPresentsMissingOrOldDataAsCurrent() {
        val latest = mapOf(0x0C to (780.0 to 1_000L), 0x03 to (null to 1_000L))
        assertEquals(GaugeReading.Unsupported, Gauges.reading(Gauges.rpm, emptySet(), latest, 1_500L) { 2_000L })
        assertEquals(GaugeReading.NoData, Gauges.reading(Gauges.rpm, setOf(0x0C), emptyMap(), 1_500L) { 2_000L })
        assertEquals(GaugeReading.NoData, Gauges.reading(Gauges.rpm.copy(pid = 0x03), setOf(0x03), latest, 1_500L) { 2_000L })
        assertEquals(GaugeReading.Value(780.0, false, 500L), Gauges.reading(Gauges.rpm, setOf(0x0C), latest, 1_500L) { 2_000L })
        assertEquals(GaugeReading.Value(780.0, true, 4_000L), Gauges.reading(Gauges.rpm, setOf(0x0C), latest, 5_000L) { 2_000L })
    }

    @Test fun everyInstrumentUsesADecodedNumericPidOnce() {
        assertEquals(Gauges.all.size, Gauges.all.map { it.pid }.toSet().size)
        for (spec in Gauges.all) {
            assertTrue(spec.max > spec.min)
            // Each PID must decode to a number; otherwise the instrument could never show a value.
            val sample = (1..4).firstNotNullOfOrNull { n -> runCatching { LiveSample.decode(spec.pid, List(n) { 0x40 }) }.getOrNull() }
            assertNotNull("%02X".format(spec.pid), sample?.value)
        }
    }
}
