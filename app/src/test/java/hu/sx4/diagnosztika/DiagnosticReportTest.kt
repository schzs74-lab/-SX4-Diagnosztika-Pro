package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticReportTest {
    private fun reply(cmd: String) = when(cmd) {
        "03" -> "43 01 04 20"; "07" -> "47 00"; "0A" -> "NO DATA"
        "ATI" -> "ELM327 v1.5"; "ATDPN" -> "A6"; "0900" -> "49 00 00 00 00 00"
        "0101" -> "41 01 81 07 65 04"; "ATRV" -> "12.2V"
        "020000" -> "42 00 00 40 00 00 00"; "020200" -> "42 02 00 00 00"
        else -> error("Unexpected request: $cmd")
    }
    @Test fun freshReportUsesOnlyReadServicesAndRecordsTimeWindows() {
        val commands = mutableListOf<String>(); var time = 1000L
        val report = DiagnosticReport({ cmd -> commands += cmd; time += 50; reply(cmd) }, setOf(1), true,
            { true }, elapsed = { time }, utc = { 0 }).collect("7E8", "test", true)
        assertEquals(listOf("03", "07", "0A"), commands.take(3))
        assertFalse(commands.contains("04")); assertFalse(commands.any { it.startsWith("ATSH") })
        assertEquals("7E8", report.source); assertEquals("REAL", report.mode)
        assertEquals(5, report.sections.size)
        assertTrue(report.sections.zipWithNext().all { (a,b) -> a.toMs <= b.fromMs })
        val text = report.text()
        assertTrue(text.contains("1970-01-01T00:00:00Z")); assertTrue(text.contains("BIZONYTALAN"))
        assertTrue(text.contains("P0420")); assertTrue(text.contains("NEM ELLENŐRIZHETŐ"))
        assertTrue(text.contains("Nincs tárolt Freeze Frame")); assertTrue(text.contains("nem teljes"))
    }
    @Test fun malformedReadinessIsVisibleWithoutDiscardingOtherSections() {
        val report = DiagnosticReport({ if(it == "0101") "41 01 80" else reply(it) }, setOf(1), true, { true })
            .collect("7E8", "test")
        assertTrue(report.text().contains("Nincs érvényes válasz"))
        assertTrue(report.text().contains("Nincs tárolt Freeze Frame"))
        assertEquals(DtcScan.State.OK, report.dtcs.first().state)
    }
    @Test fun transportLossAndCancellationAbortInsteadOfSavingFalseCleanResult() {
        for (cancel in listOf(false, true)) {
            var connected = true; var current = true; var calls = 0
            val report = DiagnosticReport({ cmd -> calls++; if(cancel) current = false else connected = false; reply(cmd) },
                setOf(1), true, { connected }, { current })
            assertThrows(IllegalStateException::class.java) { report.collect("7E8", "test") }
            assertEquals(1, calls)
        }
    }
    @Test fun completedReportsPersistAndDemoCannotLookReal() {
        val dir = Files.createTempDirectory("sx4-report").toFile()
        try {
            val report = DiagnosticReport.demo("test")
            val file = DiagnosticReport.save(dir, report)
            assertEquals(listOf(file), DiagnosticReport.saved(dir))
            assertEquals(report.text(), file.readText())
            assertTrue(file.name.contains("demo")); assertTrue(file.readText().contains("NEM VALÓDI AUTÓADAT"))
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
            java.io.File(dir, "sx4-report-incomplete.txt.part").writeText("partial")
            assertEquals(1, DiagnosticReport.saved(dir).size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun sourceAndFailedOptionalResultRemainVisibleInSavedText() {
        val report = DiagnosticReport({ reply(it) }, setOf(1), true, { true }).collect("7E9", "buildX")
        val text = report.text()
        assertTrue(text.contains("forrás: 7E9")); assertTrue(text.contains("Alkalmazás: buildX"))
        assertFalse(text.contains("DEMO / SZIMULÁLT"))
        val permanent = report.sections.single { it.title.contains("(0A)") }
        assertTrue(permanent.lines.first().contains("NEM ELLENŐRIZHETŐ"))
        assertFalse(permanent.lines.joinToString().contains("Nincs hibakód"))
    }
}
