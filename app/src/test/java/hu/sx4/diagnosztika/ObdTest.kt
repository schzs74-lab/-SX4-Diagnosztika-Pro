package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class ObdTest {
    private fun rejected(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun echoCarriageReturnsAndSearchingAreHandled() {
        assertEquals(listOf(0x1A, 0xF8), Obd.bytesFor("010C\rSEARCHING...\r41 0C 1A F8\r>", 1, 12))
        assertEquals(listOf(0x1A, 0xF8), Obd.bytesFor("410c1af8", 1, 12))
    }
    @Test fun allDtcFamiliesAndHexDigits() {
        assertEquals(listOf("P0420", "P0ABC", "C1234", "B2DEF", "U3FFF"), Obd.dtcs("43 04 20 0A BC 52 34 AD EF FF FF 00 00", false))
    }
    @Test fun noCodesIsDifferentFromInvalidResponse() {
        assertEquals(emptyList<String>(), Obd.dtcs("43 00 00 00 00", false))
        assertEquals(emptyList<String>(), Obd.dtcs("43 00", true))
        listOf("NO DATA", "?", "STOPPED", "BUS ERROR", "", "43 04 2", "7F 03 11", "44", "43", "43 hello").forEach { raw -> rejected { Obd.dtcs(raw, false) } }
    }
    @Test fun canCountAndPaddingAreNotCodes() {
        assertEquals(listOf("P0420"), Obd.dtcs("43 01 04 20 00 00 00", true))
        rejected { Obd.dtcs("43 02 04 20", true) }
        rejected { Obd.dtcs("43 01 00 00", true) }
        rejected { Obd.dtcs("43 01 04 20 12 34", true) }
    }
    @Test fun multipleHeaderlessEcusAreCollected() {
        assertEquals(listOf("P0420", "P0ABC"), Obd.dtcs("43 04 20\r43 0A BC", false))
        rejected { Obd.bytesFor("41 05 80\r41 05 81", 1, 5) }
    }
    @Test fun numberedElmResponseIsReassembled() {
        assertEquals(listOf("P0420", "P0ABC", "P0133", "U3FFF"), Obd.dtcs("00A\r0: 43 04 04 20 0A BC\r1: 01 33 FF FF", true))
        rejected { Obd.dtcs("00A\r0: 43 04 04 20\r2: 0A BC 01 33 FF FF", true) }
        rejected { Obd.dtcs("00A\r0: 43 04 04 20", true) }
        rejected { Obd.dtcs("0: 43 04 20", false) }
    }
    @Test fun isoTpSingleFrame11And29BitHeaders() {
        assertEquals(listOf("P0420", "P0ABC"), Obd.dtcs("7E8 04 43 01 04 20 00 00 00\r18DAF110 04 43 01 0A BC 00 00 00", true))
        assertEquals(listOf("P0420"), Obd.dtcs("7E80443010420000000", true))
        assertEquals(listOf("P0420"), Obd.dtcs("7E8 8 04 43 01 04 20 00 00 00", true))
    }
    @Test fun isoTpInterleavedEcus() {
        val response = "7E8 10 0A 43 04 04 20 0A BC\r7E9 04 43 01 01 33 00 00 00\r7E8 21 01 33 FF FF 00 00 00"
        assertEquals(setOf("P0420", "P0ABC", "P0133", "U3FFF"), Obd.dtcs(response, true).toSet())
    }
    @Test fun malformedIsoTpIsRejected() {
        listOf("7E8 10 0A 43 04 04 20 0A BC", "7E8 21 01 33 FF FF 00 00 00", "7E8 10 0A 43 04 04 20 0A BC\r7E8 22 01 33 FF FF 00 00 00", "7E8 07 43 01 04 20", "7E8 04 43 01 04").forEach { raw -> rejected { Obd.dtcs(raw, true) } }
    }
    @Test fun supportMaskIncludesBoundaryBitsAndLaterPages() {
        assertEquals(setOf(1, 32), Obd.pids("41 00 80 00 00 01"))
        assertEquals(setOf(33, 64), Obd.pids("41 20 80 00 00 01", 32))
        assertTrue(Obd.pids("41 00 00 00 00 00").isEmpty())
        rejected { Obd.pids("41 00 80 00 00") }
        rejected { Obd.pids("41 00 80 00 00 01 02") }
    }
    @Test fun measuredValuesAndUnavailableOxygenTrim() {
        assertEquals("Fordulatszám: 1726.25 1/perc", Obd.reading(12, listOf(0x1A, 0xF9)))
        assertEquals("Hűtőfolyadék: -40 °C", Obd.reading(5, listOf(0)))
        assertEquals("Sebesség: 255 km/h", Obd.reading(13, listOf(255)))
        assertEquals("STFT B1: -100.0 %", Obd.reading(6, listOf(0)))
        assertEquals("LTFT B1: 0.0 %", Obd.reading(7, listOf(128)))
        assertEquals("Fojtószelep: 100.0 %", Obd.reading(17, listOf(255)))
        assertTrue(Obd.reading(20, listOf(128,255)).endsWith("nem elérhető"))
        assertTrue(Obd.reading(21, listOf(128,128)).contains("0.640 V"))
        rejected { Obd.reading(12, listOf(0x1A)) }
        rejected { Obd.reading(20, listOf(128)) }
        rejected { Obd.reading(5, emptyList()) }
    }
    @Test fun clearRequiresExactPositiveReplyFromEveryEcu() {
        assertTrue(Obd.clearAcknowledged("04\r44\r>"))
        assertTrue(Obd.clearAcknowledged("7E8 01 44 00 00 00 00 00 00"))
        listOf("ERROR 44", "414400", "44 00", "NO DATA", "44\r7F 04 22", "44\rGARBAGE").forEach { assertFalse(it, Obd.clearAcknowledged(it)) }
    }
    @Test fun kLineBusInitAndAdapterStatusLines() {
        assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x11), Obd.bytesFor("0100\rBUS INIT: ...OK\r41 00 BE 3E B8 11", 1, 0))
        assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x11), Obd.bytesFor("BUS INIT: OK\r41 00 BE 3E B8 11", 1, 0))
        // Must be a regular rejection with a message, never a NullPointerException.
        listOf("BUS INIT: ...ERROR", "LV RESET", "BUS BUSY", "ACT ALERT", "XYZ 12").forEach { raw ->
            val e = assertThrows(Exception::class.java) { Obd.bytesFor(raw, 1, 0) }
            assertFalse(raw, e is NullPointerException)
            assertNotNull(raw, e.message)
        }
    }
    @Test fun kLineMultiLineDtcsWithoutCount() {
        assertEquals(listOf("P0133", "P0420", "P0171", "P0300"), Obd.dtcs("43 01 33 04 20 01 71\r43 03 00 00 00 00 00", false))
    }
    @Test fun additionalEnginePids() {
        assertEquals("Számított terhelés: 100.0 %", Obd.reading(0x04, listOf(255)))
        assertEquals("Szívócső-nyomás (MAP): 35 kPa", Obd.reading(0x0B, listOf(35)))
        assertEquals("Gyújtási előgyújtás: -64.0 °", Obd.reading(0x0E, listOf(0)))
        assertEquals("Gyújtási előgyújtás: 10.0 °", Obd.reading(0x0E, listOf(148)))
        assertEquals("Beszívott levegő: 25 °C", Obd.reading(0x0F, listOf(65)))
        assertEquals("Légtömegáram (MAF): 2.56 g/s", Obd.reading(0x10, listOf(1, 0)))
        rejected { Obd.reading(0x10, listOf(1)) }
        rejected { Obd.reading(0x99, listOf(1, 2)) }
    }
    @Test fun clearRequiresStoppedEngineAndStationaryVehicle() {
        assertNull(Obd.clearBlockReason(listOf(0, 0), listOf(0), true))
        assertNull(Obd.clearBlockReason(listOf(0, 0), null, false))
        assertNotNull(Obd.clearBlockReason(listOf(0x0C, 0x30), listOf(0), true))
        assertNotNull(Obd.clearBlockReason(listOf(0, 1), listOf(0), true))
        assertNotNull(Obd.clearBlockReason(listOf(0, 0), listOf(3), true))
        assertNotNull(Obd.clearBlockReason(listOf(0, 0), null, true))
        assertNotNull(Obd.clearBlockReason(null, listOf(0), true))
        assertNotNull(Obd.clearBlockReason(listOf(0), listOf(0), true))
    }
    @Test fun extendedPidDecoders() {
        assertEquals("Üzemanyag-rendszer: zárt kör (lambdaszabályozás)", Obd.reading(0x03, listOf(2, 0)))
        assertTrue(Obd.reading(0x03, listOf(1, 4)).contains("2. rendszer: nyitott kör (terhelés"))
        assertEquals("Lambdaszondák: B1: S1,S2 • B2: –", Obd.reading(0x13, listOf(0x03)))
        assertEquals("OBD-szabvány: EOBD (Európa)", Obd.reading(0x1C, listOf(6)))
        assertEquals("Megtett út égő hibalámpával: 300 km", Obd.reading(0x21, listOf(1, 44)))
        assertEquals("O2 S1 (széles sávú): λ 1.000; 4.000 V", Obd.reading(0x24, listOf(0x80, 0, 0x80, 0)))
        assertEquals("Üzemanyagszint: 50.2 %", Obd.reading(0x2F, listOf(128)))
        assertEquals("Katalizátor-hőmérséklet B1S1: 460.0 °C", Obd.reading(0x3C, listOf(0x13, 0x88)))
        assertEquals("ECU tápfeszültség: 13.80 V", Obd.reading(0x42, listOf(0x35, 0xE8)))
        assertEquals("Előírt lambda: 1.000", Obd.reading(0x44, listOf(0x80, 0)))
        assertEquals("Külső hőmérséklet: 18 °C", Obd.reading(0x46, listOf(58)))
        assertEquals("Idő a hibakódtörlés óta: 258 perc", Obd.reading(0x4E, listOf(1, 2)))
        rejected { Obd.reading(0x24, listOf(0x80, 0)) }
        assertFalse(Obd.decodable(0x99))
    }
    @Test fun supportChainFollowsNextPageBitsAndStopsOnRefusal() {
        val pages = mapOf(0x20 to setOf(0x21, 0x2F, 0x40), 0x40 to setOf(0x42, 0x46))
        val asked = mutableListOf<Int>()
        assertEquals(setOf(0x0C, 0x20, 0x21, 0x2F, 0x40, 0x42, 0x46),
            Obd.supportChain(setOf(0x0C, 0x20)) { asked += it; pages[it] })
        assertEquals(listOf(0x20, 0x40), asked)
        assertEquals(setOf(0x0C), Obd.supportChain(setOf(0x0C)) { error("nem kérdezhető") })
        assertEquals(setOf(0x0C, 0x20), Obd.supportChain(setOf(0x0C, 0x20)) { null })
        // Every page claims a next one: the chain still never asks beyond page E0.
        asked.clear()
        Obd.supportChain(setOf(0x20)) { asked += it; setOf(it + 0x20) }
        assertEquals((0x20..0xE0 step 0x20).toList(), asked)
    }
    @Test fun readinessSparkIgnition() {
        // MIL on, 1 DTC; misfire+fuel+components supported, fuel incomplete; cat, EVAP, O2, O2 heater supported; cat and EVAP incomplete.
        val r = Obd.readiness(listOf(0x81, 0x27, 0x65, 0x05))
        assertTrue(r.mil); assertEquals(1, r.dtcCount); assertTrue(r.sparkIgnition)
        val m = r.monitors.associateBy { it.name }
        assertEquals(Obd.Monitor("Üzemanyag-rendszer", true, false), m["Üzemanyag-rendszer"])
        assertEquals(Obd.Monitor("Gyújtáskimaradás", true, true), m["Gyújtáskimaradás"])
        assertEquals(Obd.Monitor("Katalizátor", true, false), m["Katalizátor"])
        assertEquals(Obd.Monitor("Lambdaszonda", true, true), m["Lambdaszonda"])
        assertFalse(m.getValue("EGR-rendszer").supported)
        val lines = Obd.readinessLines(r)
        assertEquals("Hibalámpa (MIL): BE • ECU szerinti hibakódszám: 1", lines.first())
        assertTrue(lines.contains("Katalizátor: NEM KÉSZ"))
        assertTrue(lines.last().startsWith("Nem támogatott: ") && lines.last().contains("EGR-rendszer"))
        rejected { Obd.readiness(listOf(0, 0, 0)) }
    }
    @Test fun freezeFrameFrames() {
        assertEquals(setOf(0x02, 0x04, 0x05, 0x0C, 0x20), Obd.freezeFramePids("42 00 00 58 10 00 01", 0))
        assertEquals(listOf(0x1A, 0xF8), Obd.freezeFrameData("42 0C 00 1A F8", 0x0C))
        assertEquals("P0420", Obd.freezeFrameDtc(Obd.freezeFrameData("42 02 00 04 20", 2)))
        assertNull(Obd.freezeFrameDtc(Obd.freezeFrameData("42 02 00 00 00", 2)))
        rejected { Obd.freezeFrameData("42 0C 01 1A F8", 0x0C) }   // not frame 0
        rejected { Obd.freezeFramePids("42 00 00 58 10 00", 0) }
        rejected { Obd.freezeFrameDtc(listOf(4)) }
    }
}
