package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class VehicleStatusTest {
    /** Scripted ECU: unknown commands answer NO DATA, like an ELM327 with an ECU that ignores the request. */
    private class FakeEcu(private val replies: Map<String, String>) {
        val sent = mutableListOf<String>()
        fun send(cmd: String): String { sent += cmd; return replies[cmd] ?: "NO DATA" }
    }

    private val supported = setOf(0x01, 0x05, 0x0C, 0x1C, 0x21)

    @Test fun fullReportWithStoredFreezeFrame() {
        val ecu = FakeEcu(mapOf(
            "0101" to "41 01 81 07 65 00",
            "011C" to "41 1C 06",
            "0121" to "41 21 00 2A",
            "ATRV" to "12.4V",
            "020000" to "42 00 00 58 10 00 00",   // 02, 04, 05, 0C
            "020200" to "42 02 00 04 20",
            "020400" to "42 04 00 80",
            "020500" to "42 05 00 81",
            "020C00" to "42 0C 00 26 C0",
        ))
        val sections = VehicleStatus(ecu::send, supported).report().associateBy { it.title }
        val readiness = sections.getValue("Readiness monitorok (0101)").lines
        assertEquals("Hibalámpa (MIL): BE • ECU szerinti hibakódszám: 1", readiness.first())
        assertTrue(readiness.contains("Katalizátor: KÉSZ"))
        assertEquals(listOf("OBD-szabvány: EOBD (Európa)", "Megtett út égő hibalámpával: 42 km",
            "Adapter által mért feszültség: 12.4V"), sections.getValue("Járműállapot").lines)
        assertEquals(listOf("Kiváltó hibakód: P0420", "Számított terhelés: 50.2 %", "Hűtőfolyadék: 89 °C",
            "Fordulatszám: 2480.00 1/perc"), sections.getValue("Freeze Frame (0. keret)").lines)
        assertFalse("Mode 04 must never be part of a status read", ecu.sent.contains("04"))
    }

    @Test fun noStoredFreezeFrameAndFailingQueriesStayLocal() {
        val ecu = FakeEcu(mapOf("020000" to "42 00 00 40 00 00 00", "020200" to "42 02 00 00 00", "011C" to "41 1C"))
        val sections = VehicleStatus(ecu::send, supported).report().associateBy { it.title }
        assertTrue(sections.getValue("Readiness monitorok (0101)").lines.single().startsWith("Nincs érvényes válasz"))
        val info = sections.getValue("Járműállapot").lines
        assertTrue(info[0].startsWith("1C: nincs érvényes válasz"))
        assertTrue(info.last().contains("nem érhető el"))
        assertEquals(listOf("Nincs tárolt Freeze Frame"), sections.getValue("Freeze Frame (0. keret)").lines)
        assertEquals(listOf("0101", "011C", "0121", "ATRV", "020000", "020200"), ecu.sent)
    }

    @Test fun unsupportedReadinessIsNotQueried() {
        val ecu = FakeEcu(emptyMap())
        val sections = VehicleStatus(ecu::send, setOf(0x0C)).report()
        assertEquals(listOf("Az ECU nem jelzi támogatottnak"), sections[0].lines)
        assertFalse(ecu.sent.contains("0101"))
    }

    @Test fun lostConnectionAbortsTheReport() {
        var up = true
        val send: (String) -> String = { cmd -> if (cmd == "011C") { up = false; throw java.io.IOException("A kapcsolat megszakadt") } else "NO DATA" }
        assertThrows(java.io.IOException::class.java) { VehicleStatus(send, supported, { up }).report() }
    }
}
