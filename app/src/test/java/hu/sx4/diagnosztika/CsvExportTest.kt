package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CsvExportTest {
    @Test fun recordedFileBecomesSemicolonAndDecimalCommaOnlyInNumericColumns() {
        val dir = Files.createTempDirectory("sx4-export").toFile()
        try {
            val log = MeasurementLog(dir, "REAL", "7E8", "OXYGEN_ONLY", "test-build", 0, 1000)
            val metrics = PollingTelemetry.Metrics(1.75, 42, 0, 780.5, 120, 2, 300, "MOTOR_JAR_ZART_KOR")
            log.append(LiveSample.decode(0x06, listOf(100)), 1450, metrics)   // STFT -21.875 %
            log.failure(0x14, "=bad;\"reply\"\nline", 1500)
            val source = log.finish("kézi mentés", 1600).readText()
            val records = CsvExport.parse(CsvExport.toHungarianExcel(source).removePrefix("\uFEFF"), ';')
            val original = CsvExport.parse(source.removePrefix("\uFEFF"))
            assertEquals(original.size, records.size)
            assertEquals(original[2][original.first().indexOf("reading")], records[2][original.first().indexOf("reading")])
            val header = original.first()
            val sample = records[1]
            assertEquals("-21,875", sample[header.indexOf("value")])
            assertEquals("1,75", sample[header.indexOf("rate_hz")])
            assertEquals("780,5", sample[header.indexOf("rpm")])
            assertEquals("450", sample[header.indexOf("elapsed_ms")])
            // Text keeps its decimal point; only measured numeric columns are converted.
            assertEquals("STFT B1: -21.9 %", sample[header.indexOf("reading")])
            assertEquals("1970-01-01T00:00:00.450Z", sample[header.indexOf("utc_time")])
            assertEquals("64", sample[header.indexOf("payload_hex")])
        } finally { dir.deleteRecursively() }
    }

    @Test fun separatorsQuotesAndLineBreaksInsideFieldsSurviveTheConversion() {
        val dir = Files.createTempDirectory("sx4-export").toFile()
        try {
            val log = MeasurementLog(dir, "DEMO", "DEMO", "ALL_LIVE_PIDS", "test-build", 0, 1000)
            log.failure(0x15, "=bad;\"reply\"\nline, more", 1100)
            val converted = CsvExport.toHungarianExcel(log.finish("end", 1200).readText())
            assertTrue(converted.startsWith("\uFEFF\"schema\";"))
            assertTrue(converted.contains("\"'=bad;\"\"reply\"\"\nline, more\""))
            assertTrue(converted.contains("\r\n"))
            assertFalse(converted.lines().first().contains(","))
        } finally { dir.deleteRecursively() }
    }

    @Test fun olderSchemaOneFilesAreConvertedByHeaderNames() {
        val schema1 = "\uFEFFschema,session_id,mode,ecu,polling_profile,app_version,utc_time,elapsed_ms,pid_hex,value,unit,payload_hex,reading,status\r\n" +
            "\"1\",\"id\",\"REAL\",\"7E8\",\"OXYGEN_ONLY\",\"b25\",\"1970-01-01T00:00:00.450Z\",\"450\",\"14\",\"0.415\",\"V\",\"53 80\",\"O2 B1S1: 0.415 V\",\"OK\"\r\n"
        val row = CsvExport.parse(CsvExport.toHungarianExcel(schema1).removePrefix("\uFEFF"), ';')[1]
        assertEquals("0,415", row[9]); assertEquals("O2 B1S1: 0.415 V", row[12]); assertEquals("14", row[8])
    }

    @Test fun malformedSourceIsRejectedInsteadOfExportingAPartialCopy() {
        assertThrows(IllegalArgumentException::class.java) { CsvExport.toHungarianExcel("\"schema\",\"value\r\n") }
        assertThrows(IllegalArgumentException::class.java) { CsvExport.toHungarianExcel("") }
        assertThrows(IllegalArgumentException::class.java) { CsvExport.parse("ab\"c\",1\r\n") }
    }

    @Test fun exportNameIsDistinctFromTheStandardCopy() {
        assertEquals("sx4-real-20261026-101500-id-excel-hu.csv", CsvExport.hungarianName("sx4-real-20261026-101500-id.csv"))
    }
}
