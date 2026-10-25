package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DriveRecorderTest {
    @Test fun fastProfileIncludesSupportedContextAndRecordsRawVoltageWithMetadata() {
        val dir=Files.createTempDirectory("sx4-drive").toFile();var now=1000L
        val log=MeasurementLog(dir,"REAL","7E8","CONTINUOUS_OXYGEN_WITH_CONTEXT","test",0,now)
        try {
            val commands=mutableListOf<String>()
            val recorder=DriveRecorder({ cmd -> commands+=cmd;now+=50;when(cmd) {
                "010C" -> "41 0C 00 00";"0103" -> "41 03 00 00";"0114" -> "41 14 FF FF";else -> "41 15 80 FF"
            } },setOf(12,3,20,21),true,log,{now})
            val events=List(8){ now+=200;recorder.step() }
            assertEquals(listOf("010C","0103","0114","0115","0114","0115","0114","0115"),commands)
            val text=log.finish("stop",now).readText()
            assertTrue(text.contains("rate_hz,request_ms"));assertTrue(text.contains("\"1.275\""))
            assertTrue(text.contains("MOTOR_ALL"));assertEquals("MOTOR_ALL",events[2].metrics.context)
            assertTrue(text.contains("\"OK\""));assertFalse(commands.contains("04"))
        } finally { if(!log.closed) log.abort();dir.deleteRecursively() }
    }
    @Test fun delayedResponsesChangeMeasuredRateRatherThanAssumingFixedPolling() {
        val dir=Files.createTempDirectory("sx4-rate").toFile();var now=1000L
        val log=MeasurementLog(dir,"DEMO","DEMO","test","test",0,now)
        try {
            val r=DriveRecorder({now+=300;"41 14 80 FF"},setOf(20),true,log,{now})
            r.step();now+=200;val next=r.step()
            assertEquals(2.0,next.metrics.hz!!,0.0001);assertEquals(300,next.metrics.requestMs.toInt())
        } finally { log.abort();dir.deleteRecursively() }
    }
    @Test fun cancellationDuringReadNeverAppendsTheLateSample() {
        val dir=Files.createTempDirectory("sx4-cancel").toFile();var current=true
        val log=MeasurementLog(dir,"REAL","7E8","test","test",0,1000)
        try {
            val r=DriveRecorder({current=false;"41 14 80 FF"},setOf(20),true,log,{1100},{current})
            assertThrows(IllegalStateException::class.java){r.step()};assertEquals(0,log.rows)
        } finally { log.abort();dir.deleteRecursively() }
    }
    @Test fun missingContextIsExplicitAndBadReplyCreatesErrorNotZero() {
        val dir=Files.createTempDirectory("sx4-missing").toFile()
        val log=MeasurementLog(dir,"REAL","7E8","test","test",0,1000)
        try {
            val r=DriveRecorder({"NO DATA"},setOf(20),true,log,{1100})
            val event=r.step();assertNull(event.sample);assertNotNull(event.error)
            assertNull(event.metrics.rpm);assertNull(event.metrics.fuelStatus)
            val text=log.finish("stop",1200).readText()
            assertTrue(text.contains("\"ERROR\""));assertTrue(text.contains("FORDULATSZAM_NINCS_FRISS_ADAT"))
        } finally { if(!log.closed)log.abort();dir.deleteRecursively() }
    }
}
