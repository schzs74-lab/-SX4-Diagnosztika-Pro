package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class PollingTelemetryTest {
    @Test fun rateIsMeasuredPerChannelAndExpiresWithoutSamples() {
        val t=PollingTelemetry()
        assertNull(t.success(LiveSample.decode(20,listOf(80,128)),1000,40).hz)
        assertEquals(2.0,t.success(LiveSample.decode(20,listOf(90,128)),1500,45).hz!!,0.0001)
        assertNull(t.success(LiveSample.decode(21,listOf(90,128)),1600,80).hz)
        assertNull(t.metrics(20,12000).hz)
        assertEquals(45,t.metrics(20,12000).requestMs.toInt())
    }
    @Test fun maximumVoltageAndOpenLoopKeepTheirMeasuredValue() {
        val t=PollingTelemetry()
        t.success(LiveSample.decode(12,listOf(0x0C,0x30)),1000,1)
        t.success(LiveSample.decode(3,listOf(4,0)),1010,1)
        val sample=LiveSample.decode(21,listOf(255,255))
        val m=t.success(sample,1100,50)
        assertEquals(1.275,sample.value!!,0.0)
        assertEquals("MOTOR_JAR_NYITOTT_KOR",m.context)
        assertEquals(780.0,m.rpm!!,0.0); assertEquals(100,m.rpmAgeMs!!.toInt())
    }
    @Test fun contextExpiresAndFailedContextDoesNotRemainFresh() {
        val t=PollingTelemetry()
        t.success(LiveSample.decode(12,listOf(0,0)),1000,1)
        assertEquals("MOTOR_ALL",t.metrics(20,1010).context)
        assertNull(t.metrics(20,6001).rpm)
        t.failure(12,2000,1)
        assertEquals("FORDULATSZAM_NINCS_FRISS_ADAT",t.metrics(20,2100).context)
    }
    @Test fun errorsDoNotBecomeSuccessfulSamplesOrZeroMeasurements() {
        val t=PollingTelemetry()
        t.success(LiveSample.decode(20,listOf(80,128)),1000,10)
        val error=t.failure(20,1500,70)
        assertNull(error.hz); assertEquals(1,error.errors)
        assertEquals(1.0,t.success(LiveSample.decode(20,listOf(80,128)),2000,10).hz!!,0.0)
    }
    @Test fun decimalsRemainExactEnoughWithoutScientificNotation() {
        assertEquals("0.000030517578125",PollingTelemetry.decimal(3.0517578125E-5))
        assertEquals("-7.8125",PollingTelemetry.decimal(-7.8125))
    }
}
