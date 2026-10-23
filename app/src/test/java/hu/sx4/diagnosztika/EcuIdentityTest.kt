package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class EcuIdentityTest {
    private val vin = "1D4GP00R55B123456"
    private fun hex(bytes: List<Int>) = bytes.joinToString(" ") { "%02X".format(it) }
    private fun ascii(value: String) = value.map { it.code }
    private fun can(pid: Int, bytes: List<Int>, count: Int = 1) = "49 %02X %02X ".format(pid, count) + hex(bytes)
    private fun legacy(pid: Int, bytes: List<Int>) = bytes.chunked(4).mapIndexed { index, part ->
        "49 %02X %02X ".format(pid, index + 1) + hex(part)
    }.joinToString("\r")

    @Test fun readsCanVinAndElmNumberedVin() {
        assertEquals(listOf(vin), EcuIdentity.decode(can(2, ascii(vin)), 2, true))
        val numbered = "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36"
        assertEquals(listOf(vin), EcuIdentity.decode(numbered, 2, true))
    }

    @Test fun readsLegacyVinWithSequenceAndLeadingPadding() {
        assertEquals(listOf(vin), EcuIdentity.decode(legacy(2, listOf(0, 0, 0) + ascii(vin)), 2, false))
    }

    @Test fun readsFixedWidthCalibrationNamesAndCvns() {
        val calibration = ascii("56028949AC") + List(6) { 0 }
        assertEquals(listOf("56028949AC"), EcuIdentity.decode(can(4, calibration), 4, true))
        assertEquals(listOf("56028949AC"), EcuIdentity.decode(legacy(4, calibration), 4, false))
        assertEquals(listOf("1234ABCD", "00000001"), EcuIdentity.decode(can(6, listOf(0x12,0x34,0xAB,0xCD,0,0,0,1), 2), 6, true))
        assertEquals(listOf("1234ABCD"), EcuIdentity.decode(legacy(6, listOf(0x12,0x34,0xAB,0xCD)), 6, false))
        assertEquals(listOf("Engine ECU"), EcuIdentity.decode(can(10, ascii("Engine ECU") + List(10) { 0 }), 10, true))
    }

    @Test fun preservesAddressForSeparateCanEcus() {
        val raw = "7E8 07 49 06 01 12 34 AB CD\r7E9 07 49 06 01 00 00 00 01"
        assertEquals(listOf("1234ABCD (ECU: 7E8)", "00000001 (ECU: 7E9)"), EcuIdentity.decode(raw, 6, true))
    }

    @Test fun rejectsAmbiguousHeaderlessReplies() {
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(can(6, listOf(1,2,3,4)) + "\r" + can(6, listOf(5,6,7,8)), 6, true) }
    }

    @Test fun rejectsTruncationBadCountAndUnexpectedMode() {
        for (raw in listOf(can(2, ascii(vin.dropLast(1))), can(2, ascii(vin), 2), can(2, ascii(vin), 0), "7F 09 12", "NO DATA")) {
            assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(raw, 2, true) }
        }
    }

    @Test fun rejectsInvalidVinAndEmbeddedNull() {
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(can(2, ascii("ID4GP00R55B123456")), 2, true) }
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(can(4, listOf(65,0,66) + List(13) { 0 }), 4, true) }
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(can(4, List(16) { 0 }), 4, true) }
    }

    @Test fun rejectsMissingDuplicateOrReorderedLegacyFrames() {
        val frames = legacy(2, listOf(0,0,0) + ascii(vin)).split('\r')
        for (bad in listOf(frames.dropLast(1), listOf(frames[0], frames[0]) + frames.drop(1), frames.reversed(), frames.filterIndexed { i, _ -> i != 2 })) {
            assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(bad.joinToString("\r"), 2, false) }
        }
    }

    @Test fun supportMaskSelectsOnlyAdvertisedIdentifiers() {
        assertEquals(setOf(2,4,6,10), EcuIdentity.supportMask("49 00 54 40 00 00"))
        val commands = mutableListOf<String>()
        val lines = EcuIdentity({ cmd ->
            commands += cmd
            when (cmd) { "0900" -> "49 00 40 00 00 00"; "0902" -> can(2, ascii(vin)); else -> "ELM327" }
        }, true).report()
        assertEquals(listOf("ATI","ATDPN","0900","0902"), commands)
        assertTrue(lines.any { it == "VIN: $vin" })
        assertTrue(lines.any { it.contains("CAL ID") && it.contains("nem támogatott") })
    }

    @Test fun absentSupportMaskStillAttemptsEachReadAndReportsIndividualFailure() {
        val commands = mutableListOf<String>()
        val lines = EcuIdentity({ cmd ->
            commands += cmd
            when (cmd) { "0902" -> can(2, ascii(vin)); "ATI" -> "ELM327"; "ATDPN" -> "A6"; else -> "NO DATA" }
        }, true).report()
        assertEquals(listOf("ATI","ATDPN","0900","0902","0904","0906","090A"), commands)
        assertTrue(lines.any { it == "VIN: $vin" })
        assertTrue(lines.any { it.contains("CVN") && it.contains("hibás válasz") })
    }

    @Test fun nullPaddedAcronymIsDecodedSeparatelyFromEcuTextName() {
        val name = ascii("ECM") + listOf(0, 45) + ascii("EngineControl") + listOf(0, 0)
        assertEquals(20, name.size)
        assertEquals(listOf("ECM — EngineControl"), EcuIdentity.decode(can(10, name), 10, true))
        assertEquals(listOf("ECM — EngineControl"), EcuIdentity.decode(legacy(10, name), 10, false))
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.decode(can(10, listOf(69, 0, 67, 77) + name.drop(4)), 10, true) }
    }

    @Test fun legacyMaskRecordIndexIsAcceptedOnlyInItsExactForm() {
        assertEquals(setOf(2,4,6,10), EcuIdentity.supportMask("49 00 01 54 40 00 00", false))
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.supportMask("49 00 02 54 40 00 00", false) }
        assertThrows(IllegalArgumentException::class.java) { EcuIdentity.supportMask("49 00 01 54 40 00 00", true) }
    }

    @Test fun transportLossAndSessionCancellationStopFurtherCommands() {
        var live = true
        val commands = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            EcuIdentity({ cmd -> commands += cmd; live = false; "ELM327" }, true, { live }).report()
        }
        assertEquals(listOf("ATI"), commands)
        assertThrows(IllegalStateException::class.java) { EcuIdentity({ error("must not send") }, true, { false }).report() }
    }
}
