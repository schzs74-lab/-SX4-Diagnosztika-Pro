package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class EcuChannelTest {
    private fun kwp(bytes: List<Int>): String = (bytes + (bytes.sum() and 255)).joinToString(" ") { "%02X".format(it) }

    @Test fun canMasksAreKeptPerSourceRatherThanMergedIntoMeasurements() {
        val masks = EcuChannel.supportBySource("7E8 06 41 00 00 10 00 00\r7E9 06 41 00 08 00 00 00", 6)
        assertEquals(mapOf("7E8" to setOf(12), "7E9" to setOf(5)), masks)
        assertEquals("41 05 90", EcuChannel.selected("7E8 03 41 05 80\r7E9 03 41 05 90", "0105", 6, "7E9"))
        assertThrows(IllegalArgumentException::class.java) { EcuChannel.selected("7E9 03 41 05 90", "0105", 6, "7E8") }
    }

    @Test fun legacyPublishedTwoEcuExampleRetainsSourceAddresses() {
        val raw = "48 6B 10 41 00 BE 3E B8 11 FA\r48 6B 18 41 00 80 10 80 00 C0"
        val masks = EcuChannel.supportBySource(raw, 2)
        assertEquals(setOf("10", "18"), masks.keys)
        assertTrue(5 in masks.getValue("10")); assertFalse(5 in masks.getValue("18"))
        val iso9141 = raw.replace("11 FA", "11 C9").replace("00 C0", "00 1C")
        assertEquals(masks, EcuChannel.supportBySource(iso9141, 3))
        assertThrows(IllegalArgumentException::class.java) { EcuChannel.supportBySource(raw, 3) }
    }

    @Test fun kwpShortAndExplicitLengthsAreChecked() {
        val payload = listOf(0x41, 5, 0x80)
        for (frame in listOf(kwp(listOf(0x83, 0xF1, 0x10) + payload), kwp(listOf(0x80, 0xF1, 0x10, 3) + payload))) {
            assertEquals("41 05 80", EcuChannel.selected(frame, "0105", 5, "10"))
        }
        assertThrows(IllegalArgumentException::class.java) { EcuChannel.messages(kwp(listOf(0x84, 0xF1, 0x10) + payload), "0105", 5) }
    }

    @Test fun headersChecksumAndDuplicateSourcesAreRequired() {
        for (raw in listOf("41 00 00 10 00 00", "48 6B 10 41 00 BE 3E B8 11 FB")) {
            assertThrows(IllegalArgumentException::class.java) { EcuChannel.supportBySource(raw, 3) }
        }
        assertThrows(IllegalArgumentException::class.java) { EcuChannel.supportBySource("41 00 00 10 00 00", 6) }
        assertThrows(IllegalArgumentException::class.java) { EcuChannel.supportBySource("7E8 06 41 00 00 10 00 00\r7E8 06 41 00 00 10 00 00", 6) }
    }

    @Test fun acknowledgementMustBeExactlyOneMatchingSource() {
        assertTrue(EcuChannel.clearAcknowledged("7E8 01 44 00 00 00 00 00 00", 6, "7E8"))
        for (raw in listOf("44", "7E9 01 44", "7E8 01 44\r7E9 01 44", "7E8 03 7F 04 22", "NO DATA")) {
            assertFalse(raw, EcuChannel.clearAcknowledged(raw, 6, "7E8"))
        }
    }

    @Test fun isoTpPaddingIsRemovedButUnexplainedElmLengthOverflowIsRejected() {
        assertEquals("41 05 80", EcuChannel.selected("7E8 03 41 05 80 AA AA AA AA", "0105", 6, "7E8"))
        assertThrows(IllegalArgumentException::class.java) { Obd.messages("003\r0: 41 05 80 00") }
    }
}
