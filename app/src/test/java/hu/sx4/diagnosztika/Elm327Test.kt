package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Elm327Test {
    private class Adapter(private val greeting: String? = null, private val reply: (String, Socket) -> Boolean) : AutoCloseable {
        val server = ServerSocket(0)
        val done = CountDownLatch(1)
        @Volatile var accepted: Socket? = null
        @Volatile var failure: Throwable? = null
        private val thread = Thread {
            try {
                server.accept().use { socket ->
                    accepted = socket
                    greeting?.let { socket.getOutputStream().write(it.toByteArray()); socket.getOutputStream().flush() }
                    val input = socket.getInputStream()
                    val command = StringBuilder()
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) break
                        if (byte != 13) { command.append(byte.toChar()); continue }
                        val cmd = command.toString(); command.setLength(0)
                        if (reply(cmd, socket)) continue
                        val response = when (cmd) {
                            "ATZ", "ATI" -> "ELM327 v1.5"
                            "0100" -> "7E8 06 41 00 00 10 00 00"
                            "ATDPN" -> "A6"
                            else -> if (cmd.startsWith("AT")) "OK" else "NO DATA"
                        }
                        socket.getOutputStream().write((response + "\r>").toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            } catch (e: Throwable) {
                if (!server.isClosed && accepted?.isClosed != true && e !is java.net.SocketException) failure = e
            } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        fun connect(elm: Elm327) = elm.connect("127.0.0.1", server.localPort)
        override fun close() { server.close(); accepted?.close(); thread.join(1000) }
    }

    @Test fun initializesAndDisconnects() {
        Adapter { _, _ -> false }.use { adapter ->
            val elm = Elm327()
            try {
                assertTrue(adapter.connect(elm).contains("ECU válaszolt"))
                assertTrue(elm.isConnected()); assertTrue(elm.canProtocol)
                assertEquals(setOf(12), elm.supported)
                elm.disconnect()
                assertFalse(elm.isConnected())
                assertTrue(adapter.done.await(1, TimeUnit.SECONDS))
                assertNull(adapter.failure)
            } finally { elm.close() }
        }
    }
    @Test fun singleCanResponderUsesTheFirstReplyShortcutOnlyAfterAProbe() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        Adapter { cmd, sock ->
            seen += cmd
            val reply = when (cmd) { "01001" -> "7E8 06 41 00 00 10 00 00"; "010C1" -> "7E8 04 41 0C 0C 30"; else -> null }
            reply?.let { sock.getOutputStream().write("$it\r>".toByteArray()); sock.getOutputStream().flush(); true } ?: false
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                assertTrue(elm.singleResponse)
                assertEquals(listOf(0x0C, 0x30), Obd.bytesFor(elm.command("010C"), 1, 0x0C))
                assertTrue(seen.contains("010C1")); assertFalse(seen.contains("010C"))
                elm.disconnect(); assertFalse(elm.singleResponse)
            } finally { elm.close() }
        }
    }
    @Test fun adapterWithoutTheShortcutKeepsPlainRequests() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        Adapter { cmd, sock ->
            seen += cmd
            val reply = when (cmd) { "01001" -> "?"; "010C" -> "7E8 04 41 0C 0C 30"; else -> null }
            reply?.let { sock.getOutputStream().write("$it\r>".toByteArray()); sock.getOutputStream().flush(); true } ?: false
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                assertFalse(elm.singleResponse); assertTrue(elm.isConnected())
                assertEquals(listOf(0x0C, 0x30), Obd.bytesFor(elm.command("010C"), 1, 0x0C))
                assertFalse(seen.contains("010C1"))
            } finally { elm.close() }
        }
    }
    @Test fun bannerSentOnAcceptDoesNotShiftReplies() {
        Adapter("\r\rELM327 v1.5\r\r>") { _, _ -> false }.use { adapter ->
            val elm = Elm327()
            try {
                assertTrue(adapter.connect(elm).contains("ECU válaszolt"))
                assertEquals(setOf(12), Obd.pids(elm.command("0100")))
            } finally { elm.close() }
        }
    }
    @Test fun supportPagesBeyondFirstAreChained() {
        Adapter { cmd, s ->
            val reply = when (cmd) { "0100" -> "7E8 06 41 00 00 10 00 01"; "0120" -> "7E8 06 41 20 80 02 00 00"; else -> null }
            reply?.let { s.getOutputStream().write("$it\r>".toByteArray()); s.getOutputStream().flush(); true } ?: false
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                assertEquals(setOf(0x0C, 0x20, 0x21, 0x2F), elm.supported)
            } finally { elm.close() }
        }
    }
    @Test fun refusedSupportPageKeepsConnection() {
        Adapter { cmd, s ->
            val reply = when (cmd) { "0100" -> "7E8 06 41 00 00 10 00 01"; "0120" -> "NO DATA"; else -> null }
            reply?.let { s.getOutputStream().write("$it\r>".toByteArray()); s.getOutputStream().flush(); true } ?: false
        }.use { adapter ->
            val elm = Elm327()
            try {
                assertTrue(adapter.connect(elm).contains("ECU válaszolt"))
                assertTrue(elm.isConnected())
                assertEquals(setOf(0x0C, 0x20), elm.supported)
            } finally { elm.close() }
        }
    }
    @Test fun initializationFailureClosesTransport() {
        Adapter { cmd, s ->
            if (cmd == "ATE0") { s.getOutputStream().write("?\r>".toByteArray()); true } else false
        }.use { adapter ->
            val elm = Elm327()
            try {
                assertThrows(Exception::class.java) { adapter.connect(elm) }
                assertFalse(elm.isConnected())
                assertTrue(adapter.done.await(1, TimeUnit.SECONDS))
            } finally { elm.close() }
        }
    }
    @Test fun fragmentedResponseAndEof() {
        Adapter { cmd, s ->
            when (cmd) {
                "0105" -> {
                    for (part in listOf("7E8 03 41 ", "05 ", "80\r", ">")) {
                        s.getOutputStream().write(part.toByteArray()); s.getOutputStream().flush()
                        Thread.sleep(10)
                    }; true
                }
                "010D" -> { s.close(); true }
                else -> false
            }
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                assertEquals(listOf(128), Obd.bytesFor(elm.command("0105"), 1, 5))
                assertThrows(Exception::class.java) { elm.command("010D") }
                assertFalse(elm.isConnected())
            } finally { elm.close() }
        }
    }
    @Test fun absoluteDeadlineRejectsSlowDripEvenWithIncomingBytes() {
        Adapter { cmd, s ->
            if (cmd == "0105") {
                repeat(15) { s.getOutputStream().write('4'.code); s.getOutputStream().flush(); Thread.sleep(40) }
                true
            } else false
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                val start = System.nanoTime()
                assertThrows(Exception::class.java) { elm.command("0105", 180) }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
                assertFalse(elm.isConnected())
            } finally { elm.close() }
        }
    }
    @Test fun disconnectDoesNotWaitForReadLock() {
        val reading = CountDownLatch(1)
        Adapter { cmd, _ -> if (cmd == "0105") { reading.countDown(); true } else false }.use { adapter ->
            val elm = Elm327(); val worker = Executors.newSingleThreadExecutor()
            try {
                adapter.connect(elm)
                val future = worker.submit<String> { elm.command("0105", 5000) }
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                val start = System.nanoTime(); elm.disconnect()
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
                assertThrows(Exception::class.java) { future.get(1, TimeUnit.SECONDS) }
                assertFalse(elm.isConnected())
            } finally { elm.close(); worker.shutdownNow() }
        }
    }
    @Test fun oversizedReplyCannotBeAcceptedWithoutPrompt() {
        Adapter { cmd, s ->
            if (cmd == "0105") { s.getOutputStream().write(("4".repeat(32769) + ">").toByteArray()); true } else false
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                assertThrows(Exception::class.java) { elm.command("0105") }
                assertFalse(elm.isConnected())
            } finally { elm.close() }
        }
    }
    @Test fun writeCommandsAreRejected() {
        val elm = Elm327()
        try { listOf("04", "ATH0", "ATZ", "ATSH7E0", "2E123400", "0908", "090B", "0902\r04", "04\r04").forEach { cmd -> assertThrows(IllegalArgumentException::class.java) { elm.command(cmd) } } }
        finally { elm.close() }
    }

    @Test fun onlyExplicitIdentityReadCommandsReachAdapter() {
        val seen = mutableListOf<String>()
        Adapter { cmd, _ -> seen += cmd; false }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                val reads = listOf("0900", "0902", "0904", "0906", "090A")
                reads.forEach { assertEquals("NO DATA", elm.command(it).trim()) }
                assertEquals(reads, seen.filter { it.startsWith("09") })
                assertThrows(IllegalArgumentException::class.java) { elm.command("2E123400") }
                assertFalse(seen.contains("2E123400"))
            } finally { elm.close() }
        }
    }
    private fun reply(socket: Socket, value: String): Boolean {
        socket.getOutputStream().write((value + "\r>").toByteArray())
        socket.getOutputStream().flush()
        return true
    }

    @Test fun multipleCanEcusConnectButRequireExplicitSelection() {
        Adapter { cmd, socket ->
            when (cmd) {
                "0100" -> reply(socket, "7E8 06 41 00 00 10 00 00\r7E9 06 41 00 08 00 00 00")
                "0105" -> reply(socket, "7E8 03 41 05 80\r7E9 03 41 05 90")
                else -> false
            }
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                assertTrue(elm.isConnected()); assertNull(elm.selectedSource)
                assertThrows(IllegalStateException::class.java) { elm.command("0105") }
                elm.selectEcu("7E9")
                assertEquals(setOf(5), elm.supported)
                assertEquals(listOf(0x90), Obd.bytesFor(elm.command("0105"), 1, 5))
                assertNotNull(elm.clearAvailabilityReason())
                assertThrows(IllegalStateException::class.java) { elm.prepareClearPermit("43 00") }
            } finally { elm.close() }
        }
    }

    @Test fun kLineMultipleEcusKeepIndependentSupportAndData() {
        Adapter { cmd, socket ->
            when (cmd) {
                "ATDPN" -> reply(socket, "A3")
                "0100" -> reply(socket, "48 6B 10 41 00 BE 3E B8 11 C9\r48 6B 18 41 00 80 10 80 00 1C")
                "0120" -> reply(socket, "NO DATA")
                "0105" -> reply(socket, "48 6B 10 41 05 80 89\r48 6B 18 41 05 90 A1")
                else -> false
            }
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm); assertFalse(elm.canProtocol)
                assertEquals(setOf("10", "18"), elm.ecuSources.keys)
                elm.selectEcu("10")
                assertEquals(listOf(0x80), Obd.bytesFor(elm.command("0105"), 1, 5))
                elm.selectEcu("18")
                assertEquals(listOf(0x90), Obd.bytesFor(elm.command("0105"), 1, 5))
            } finally { elm.close() }
        }
    }

    private fun clearAdapter(seen: MutableList<String>, variant: String = "ok") = Adapter { cmd, socket ->
        seen += cmd
        when (cmd) {
            "0100" -> reply(socket, if (variant == "newEcu" && seen.count { it == "0100" } > 1)
                "7E8 06 41 00 00 18 00 00\r7E9 06 41 00 00 10 00 00" else "7E8 06 41 00 00 18 00 00")
            "03" -> reply(socket, if (variant == "newCode") "7E8 04 43 01 01 33" else "7E8 04 43 01 04 20")
            "010C" -> reply(socket, if (variant == "running") "7E8 04 41 0C 0C 30" else "7E8 04 41 0C 00 00")
            "010D" -> reply(socket, if (variant == "moving") "7E8 03 41 0D 05" else "7E8 03 41 0D 00")
            "04" -> if (variant == "lostAck") { socket.close(); true }
                else reply(socket, when(variant) { "wrongAck" -> "7E9 01 44"; "reject" -> "7E8 03 7F 04 22"; "pendingNrc" -> "7E8 03 7F 04 78"; else -> "7E8 01 44" })
            else -> false
        }
    }

    @Test fun dedicatedClearChecksFreshCodesAndStandstillBeforeSending() {
        val seen = mutableListOf<String>()
        clearAdapter(seen).use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                val permit = elm.prepareClearPermit(elm.command("03"))
                elm.clearDtcs(permit)
                assertEquals(listOf("0100", "03", "010C", "010D", "04"), seen.takeLast(5))
                assertThrows(IllegalStateException::class.java) { elm.clearDtcs(permit) }
                assertEquals(1, seen.count { it == "04" })
                assertThrows(IllegalArgumentException::class.java) { elm.command("04") }
            } finally { elm.close() }
        }
    }

    @Test fun changedCodesRunningMovingAndNewResponderNeverSendClear() {
        for (variant in listOf("newCode", "running", "moving", "newEcu")) {
            val seen = mutableListOf<String>()
            clearAdapter(seen, variant).use { adapter ->
                val elm = Elm327()
                try {
                    adapter.connect(elm)
                    val permit = elm.prepareClearPermit("43 01 04 20")
                    assertThrows(Exception::class.java) { elm.clearDtcs(permit) }
                    assertFalse(variant, seen.contains("04"))
                } finally { elm.close() }
            }
        }
    }

    @Test fun cancelledExpiredOrOldSessionPermitNeverSendsClear() {
        val seen = mutableListOf<String>()
        clearAdapter(seen).use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                val permit = elm.prepareClearPermit("43 01 04 20")
                assertThrows(IllegalStateException::class.java) { elm.clearDtcs(permit) { false } }
                assertThrows(IllegalStateException::class.java) { elm.clearDtcs(permit.copy(createdAtNanos = System.nanoTime() - ClearPermit.LIFETIME_NANOS - 1)) }
                elm.disconnect()
                assertThrows(IllegalStateException::class.java) { elm.clearDtcs(permit) }
                assertFalse(seen.contains("04"))
            } finally { elm.close() }
        }
    }

    @Test fun lostOrWrongSourceAcknowledgementReportsUnknownWithoutRetry() {
        for (variant in listOf("lostAck", "wrongAck")) {
            val seen = mutableListOf<String>()
            clearAdapter(seen, variant).use { adapter ->
                val elm = Elm327()
                try {
                    adapter.connect(elm)
                    val permit = elm.prepareClearPermit("43 01 04 20")
                    val failure = assertThrows(ClearOutcomeUnknown::class.java) { elm.clearDtcs(permit) }
                    assertTrue(failure.message!!.contains("BIZONYTALAN"))
                    assertEquals(1, seen.count { it == "04" })
                } finally { elm.close() }
            }
        }
    }

    @Test fun additionalDtcServicesAndReportUseSelectedResponderWithoutWrites() {
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        Adapter { cmd, socket ->
            commands += cmd
            val reply = when(cmd) {
                "03" -> "7E8 04 43 01 04 20"
                "07" -> "7E8 04 47 01 01 71"
                "0A" -> "7E8 02 4A 00"
                else -> null
            }
            if(reply == null) false else {
                socket.getOutputStream().write((reply + "\r>").toByteArray()); socket.getOutputStream().flush(); true
            }
        }.use { adapter ->
            val elm = Elm327()
            try {
                adapter.connect(elm)
                val results = DtcScan({ elm.command(it) }, elm.canProtocol, { elm.isConnected() }).readAll()
                assertEquals(listOf("P0420"), results[0].codes)
                assertEquals(listOf("P0171"), results[1].codes)
                assertEquals(DtcScan.State.OK, results[2].state)
                assertTrue(results[2].codes.isEmpty())
                val report = DiagnosticReport({ elm.command(it) }, elm.supported, elm.canProtocol,
                    { elm.isConnected() }).collect(elm.selectedSource!!, "test")
                assertEquals("7E8", report.source)
                assertTrue(report.text().contains("P0420"))
                assertFalse(commands.contains("04")); assertFalse(commands.any { it.startsWith("08") || it.startsWith("ATSH") })
                assertTrue(elm.isConnected())
            } finally { elm.close() }
        }
    }
    @Test fun terminalRejectionDiffersFromResponsePendingWithoutRetry() {
        for(variant in listOf("reject","pendingNrc")) {
            val seen=mutableListOf<String>()
            clearAdapter(seen,variant).use { adapter ->
                val elm=Elm327()
                try {
                    adapter.connect(elm)
                    val permit=elm.prepareClearPermit("43 01 04 20")
                    if(variant=="reject") assertThrows(ClearRejected::class.java){elm.clearDtcs(permit)}
                    else assertThrows(ClearOutcomeUnknown::class.java){elm.clearDtcs(permit)}
                    assertEquals(1,seen.count{it=="04"})
                } finally {elm.close()}
            }
        }
    }
    @Test fun ambiguousOrOtherSourceNegativeReplyIsNotConclusive() {
        assertNull(EcuChannel.clearRejectionCode("7E9 03 7F 04 22",6,"7E8"))
        assertNull(EcuChannel.clearRejectionCode("7E8 03 7F 04 22\r7E9 01 44",6,"7E8"))
        assertNull(EcuChannel.clearRejectionCode("7E8 03 7F 04 78",6,"7E8"))
        assertEquals(0x22,EcuChannel.clearRejectionCode("7E8 03 7F 04 22",6,"7E8"))
    }
}


