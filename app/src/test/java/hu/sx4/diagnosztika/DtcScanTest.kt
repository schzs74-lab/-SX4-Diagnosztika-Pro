package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test

class DtcScanTest {
    @Test fun threeCanServicesKeepTheirCodesSeparate() {
        val commands = mutableListOf<String>()
        val scan = DtcScan({ cmd -> commands += cmd; when(cmd) {
            "03" -> "43 01 04 20"; "07" -> "47 01 01 71"; "0A" -> "4A 01 03 01"; else -> error(cmd)
        } }, true).readAll()
        assertEquals(listOf("03", "07", "0A"), commands)
        assertEquals(listOf(listOf("P0420"), listOf("P0171"), listOf("P0301")), scan.map { it.codes })
        assertTrue(scan.all { it.state == DtcScan.State.OK })
    }
    @Test fun noDataAndNegativeRepliesAreNotEmptySuccess() {
        for (raw in listOf("NO DATA", "7F 0A 11", "4A", "4A 02 04 20", "4A 01 00 00", "47 00")) {
            val result = DtcScan({ raw }, true).read(10)
            assertEquals(raw, DtcScan.State.UNAVAILABLE, result.state)
            assertTrue(result.lines().first().contains("NEM ELLENŐRIZHETŐ"))
        }
        assertEquals(DtcScan.State.OK, DtcScan({ "4A 00" }, true).read(10).state)
    }
    @Test fun optionalFailureDoesNotHideStoredAndPendingResults() {
        val results = DtcScan({ if(it == "03") "43 00" else if(it == "07") "47 01 03 00" else "NO DATA" }, true).readAll()
        assertEquals(DtcScan.State.OK, results[0].state)
        assertEquals(listOf("P0300"), results[1].codes)
        assertEquals(DtcScan.State.UNAVAILABLE, results[2].state)
    }
    @Test fun legacyResponsesHaveNoCanCount() {
        assertEquals(listOf("P0420"), DtcScan({ "47 04 20 00 00 00 00" }, false).read(7).codes)
        assertEquals(listOf("P0171"), DtcScan({ "4A 01 71 00 00" }, false).read(10).codes)
    }
    @Test fun selectedSourceIsPreservedForBothAdditionalServices() {
        for ((cmd, response) in listOf("07" to "47", "0A" to "4A")) {
            val raw = "7E8 04 $response 01 04 20\r7E9 04 $response 01 01 71"
            val selected = EcuChannel.selected(raw, cmd, 6, "7E8")
            assertEquals(listOf("P0420"), DtcScan({ selected }, true).read(cmd.toInt(16)).codes)
        }
    }
    @Test fun lostConnectionStopsBeforeNextRequestAndDescriptionsNeverGuess() {
        var connected = true; var calls = 0
        assertThrows(IllegalStateException::class.java) {
            DtcScan({ calls++; connected = false; "NO DATA" }, true, { connected }).readAll()
        }
        assertEquals(1, calls)
        assertTrue(DtcMeaning.describe("P1234").contains("Nincs ellenőrzött"))
        assertTrue(DtcMeaning.describe("P0420").contains("Katalizátor"))
    }
}
