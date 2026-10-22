package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MeasurementLogTest {
    private fun log(dir: java.io.File, mode: String = "REAL", rows: Int = 100, duration: Long = 3_600_000) =
        MeasurementLog(dir, mode, if (mode == "REAL") "7E8" else "DEMO", "OXYGEN_ONLY", "test-build", 0, 1000, maxRows=rows, maxDurationMs=duration)

    @Test fun completedCsvContainsModeSourceNumericValuesAndSampleTimes() {
        val dir = Files.createTempDirectory("sx4-log").toFile()
        try {
            val r = log(dir); r.append(LiveSample.decode(20, listOf(83,128)), 1450)
            assertTrue(MeasurementLog.saved(dir).isEmpty())
            val file = r.finish("kézi mentés", 1500)
            val text = file.readText()
            assertTrue(text.startsWith("\uFEFFschema,")); assertTrue(text.contains("\"REAL\",\"7E8\""))
            assertTrue(text.contains("\"1970-01-01T00:00:00.450Z\",\"450\",\"14\",\"0.415\",\"V\",\"53 80\""))
            assertTrue(text.contains("\"END\"")); assertEquals(listOf(file), MeasurementLog.saved(dir))
            assertEquals(file, r.finish("again", 1600))
            assertThrows(IllegalStateException::class.java) { r.append(LiveSample.decode(20, listOf(0,128)), 1700) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun errorsAreNotRecordedAsZeroAndCsvTextCannotBecomeAFormula() {
        val dir = Files.createTempDirectory("sx4-log").toFile()
        try {
            val r = log(dir); r.failure(21, "=bad,\"reply\"\nline", 1100); val text = r.finish("end", 1200).readText()
            assertTrue(text.contains("\"15\",\"\",\"\",\"\",\"'=bad,\"\"reply\"\"\nline\",\"ERROR\""))
        } finally { dir.deleteRecursively() }
    }
    @Test fun rowAndTimeLimitsFinalizeAReadableFile() {
        val dir = Files.createTempDirectory("sx4-log").toFile()
        try {
            val sample = LiveSample.decode(20, listOf(80,128))
            val r = log(dir, rows=1); assertTrue(r.append(sample, 1100)); assertFalse(r.append(sample, 1200))
            assertTrue(r.closed); assertEquals(1, r.rows); assertTrue(r.file.readText().contains("LIMIT"))
            val timed = log(dir, duration=100); assertFalse(timed.append(sample, 1101)); assertTrue(timed.file.isFile)
        } finally { dir.deleteRecursively() }
    }
    @Test fun demoAndRealHaveSeparateFilesAndImmutableSourceLabels() {
        val dir = Files.createTempDirectory("sx4-log").toFile()
        try {
            val real = log(dir); val demo = log(dir, mode="DEMO")
            real.finish("end", 1200); demo.finish("end", 1200)
            assertNotEquals(real.file.name, demo.file.name)
            assertTrue(real.file.readText().contains("\"REAL\",\"7E8\""))
            assertTrue(demo.file.readText().contains("\"DEMO\",\"DEMO\""))
        } finally { dir.deleteRecursively() }
    }
    @Test fun abortedPartialFilesAreNotSharedAsCompletedMeasurements() {
        val dir = Files.createTempDirectory("sx4-log").toFile()
        try { val r = log(dir); r.abort(); assertTrue(MeasurementLog.saved(dir).isEmpty()) }
        finally { dir.deleteRecursively() }
    }
}
