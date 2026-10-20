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
                            "0100" -> "41 00 00 10 00 00"
                            "ATDPN" -> "A6"
                            else -> "OK"
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
            val reply = when (cmd) { "0100" -> "41 00 00 10 00 01"; "0120" -> "41 20 80 02 00 00"; else -> null }
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
            val reply = when (cmd) { "0100" -> "41 00 00 10 00 01"; "0120" -> "NO DATA"; else -> null }
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
                    for (part in listOf("41 ", "05 ", "80\r", ">")) {
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
        try { listOf("ATSH7E0", "2E123400", "0902", "04\r04").forEach { cmd -> assertThrows(IllegalArgumentException::class.java) { elm.command(cmd) } } }
        finally { elm.close() }
    }
}
