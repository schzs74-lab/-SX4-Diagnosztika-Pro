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

    @Test fun quotedFieldsAndEmptyRecordsFollowTheLexerRules() {
        assertEquals(listOf(listOf("a,b", "a\"b", "a\r\nb", "a\nb", "a\rb", "", "")),
            CsvExport.parse("\"a,b\",\"a\"\"b\",\"a\r\nb\",\"a\nb\",\"a\rb\",,\"\"\r\n"))
        assertEquals(listOf(listOf("", "", "")), CsvExport.parse(",,\n"))
        assertEquals(listOf(listOf("")), CsvExport.parse("\"\""))
        assertEquals(listOf(listOf("a","b"),listOf("c","d")), CsvExport.parse("a,b\nc,d"))
        for(input in listOf("\"a\"x,b\r\n", "\"a\" ,b\r\n", "a\rb,c\r\n", "\"a\"\rb,c\r\n", "a\"b,c", "\"unclosed"))
            assertThrows(input,IllegalArgumentException::class.java) { CsvExport.parse(input) }
    }

    private val legacyHeader = "schema,session_id,mode,ecu,polling_profile,app_version,utc_time,elapsed_ms,pid_hex,value,unit,payload_hex,reading,status"
    private fun legacyRow(value: String) = "1,id,REAL,7E8,ALL_LIVE_PIDS,b27,1970-01-01T00:00:00.001Z,1,24,$value,lambda,00 01 00 00,lambda 0.000,OK"

    @Test fun oldScientificValuesAreLocalizedWithoutTouchingText() {
        for((number,expected) in listOf("3.0517578125E-5" to "0,000030517578125", "-3.0517578125E-5" to "-0,000030517578125", "1.25E+3" to "1250")) {
            val rows=CsvExport.parse(CsvExport.toHungarianExcel("$legacyHeader\r\n${legacyRow(number)}\r\n"),';')
            assertEquals(expected,rows[1][9]);assertEquals("lambda 0.000",rows[1][12])
            assertEquals("1970-01-01T00:00:00.001Z",rows[1][6]);assertEquals("00 01 00 00",rows[1][11])
        }
    }

    @Test fun missingExtraDuplicateAndWrongSchemaFieldsAreRejected() {
        for(input in listOf("schema,value\r\n1,0.4\r\n", "$legacyHeader\r\n1\r\n", "$legacyHeader\r\n${legacyRow("0.4")},extra\r\n",
            legacyHeader.replace("mode","schema")+"\r\n", "$legacyHeader\r\n${legacyRow("0.4").replaceFirst("1,","2,")}\r\n"))
            assertThrows(IllegalArgumentException::class.java) { CsvExport.toHungarianExcel(input) }
    }

    @Test fun extremeExponentsAndOversizedFieldsFailBeforeUnboundedExpansion() {
        for(number in listOf("1E-2147483648","1E+2147483647","1E-129","NaN","=1+1"))
            assertThrows(IllegalArgumentException::class.java) { CsvExport.toHungarianExcel("$legacyHeader\r\n${legacyRow(number)}\r\n") }
        assertThrows(IllegalArgumentException::class.java) { CsvExport.parse("\""+"x".repeat(16_385)+"\"") }
    }

    @Test fun stagingIsPrivateByteExactForStandardAndRemovedOnClose() {
        val dir=Files.createTempDirectory("sx4-stage").toFile()
        try {
            val source=java.io.File(dir,"source.csv").apply { writeBytes(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte(),0,13,10,65)) }
            var staged:java.io.File?=null
            CsvExport.prepare(source,java.io.File(dir,"cache"),false).use { prepared ->
                staged=prepared.file;assertArrayEquals(source.readBytes(),prepared.file.readBytes());assertTrue(prepared.file.name.endsWith(".part"))
                assertNotEquals(source.parentFile,prepared.file.parentFile)
            }
            assertFalse(staged!!.exists());assertEquals(7,source.length().toInt())
        } finally {dir.deleteRecursively()}
    }

    @Test fun invalidUtf8AndMalformedSourceLeaveNoStagedExport() {
        val dir=Files.createTempDirectory("sx4-stage-error").toFile()
        try {
            val source=java.io.File(dir,"source.csv");val cache=java.io.File(dir,"cache")
            source.writeText("$legacyHeader\r\n\"1\"x,bad\r\n")
            assertThrows(IllegalArgumentException::class.java) { CsvExport.prepare(source,cache,true) }
            source.writeBytes(byteArrayOf(0xC3.toByte(),0x28))
            assertThrows(java.nio.charset.CharacterCodingException::class.java) { CsvExport.prepare(source,cache,true) }
            assertTrue(java.io.File(cache,"csv-export").listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }

    @Test fun largeRecordingIsPreparedByStreamingAndKeepsAllRows() {
        val dir=Files.createTempDirectory("sx4-stage-large").toFile()
        try {
            val log=MeasurementLog(dir,"REAL","7E8","CONTINUOUS_OXYGEN_WITH_CONTEXT","test",0,0)
            val sample=LiveSample.decode(20,listOf(83,128));val metrics=PollingTelemetry.Metrics(1.75,42,0,780.5,120,2,300,"MOTOR_JAR_ZART_KOR")
            repeat(30_000) { assertTrue(log.append(sample,it.toLong(),metrics)) }
            val source=log.finish("stop",30_000)
            CsvExport.prepare(source,java.io.File(dir,"cache"),true).use { prepared ->
                assertEquals(30_002,prepared.file.bufferedReader().use { it.lineSequence().count() })
                prepared.file.inputStream().use { assertEquals(0xEF,it.read());assertEquals(0xBB,it.read());assertEquals(0xBF,it.read()) }
            }
        } finally {dir.deleteRecursively()}
    }
}

