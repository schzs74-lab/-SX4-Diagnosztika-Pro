package hu.sx4.diagnosztika

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One serialized command stream; disconnect deliberately never acquires the I/O lock. */
class Elm327 {
    private val ioLock = Any()
    private val stateLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var ready = false
    @Volatile var canProtocol: Boolean = false
        private set
    @Volatile var supported: Set<Int> = emptySet()
        private set
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "elm-deadline").apply { isDaemon = true } }

    fun connect(host: String, port: Int, createSocket: () -> Socket = { Socket() }, cancelled: () -> Boolean = { false }): String = synchronized(ioLock) {
        disconnect()
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) && host.split('.').all { it.toInt() in 0..255 }) { "IPv4-cím szükséges" }
        require(port in 1..65535) { "Hibás TCP-port" }
        val s = createSocket()
        synchronized(stateLock) { socket = s }
        try {
            check(!cancelled()) { "Megszakított kapcsolódás" }
            s.connect(InetSocketAddress(host, port), 3500)
            // Some Wi-Fi clones send a banner/prompt on TCP accept; left unread it shifts every reply by one.
            drainUntilQuiet(s)
            command("ATZ", 5000)
            for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATCAF1")) {
                val reply = command(cmd).replace(cmd, "").trim()
                check(reply == "OK") { "Inicializálás sikertelen ($cmd): $reply" }
            }
            val id = command("ATI")
            // Protocol selection happens only after the first ECU request, not after ATSP0.
            supported = Obd.pids(command("0100", 12000))
            val protocolText = command("ATDPN").trim().uppercase(Locale.ROOT)
            val protocol = (if (protocolText.length == 2) protocolText.removePrefix("A") else protocolText).toIntOrNull(16)
                ?: error("Ismeretlen OBD-protokoll")
            check(protocol in 1..12) { "Nem választott OBD-protokollt az adapter" }
            canProtocol = protocol in 6..10
            check(!cancelled() && socket === s && !s.isClosed) { "Megszakított kapcsolódás" }
            ready = true
            "$id • protokoll: ${protocol.toString(16).uppercase(Locale.ROOT)} • ECU válaszolt"
        } catch (e: Exception) {
            disconnect(); throw e
        }
    }

    fun command(cmd: String, timeoutMs: Int = 3500): String = synchronized(ioLock) {
        // Only standard diagnostic reads and the explicitly confirmed Mode 04 are available.
        require(cmd in setOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATCAF1", "ATI", "ATDPN", "03", "04", "07", "0A") ||
            cmd.matches(Regex("01[0-9A-F]{2}|02[0-9A-F]{2}00"))) { "Nem engedélyezett parancs" }
        require(timeoutMs > 0)
        val s = socket ?: error("Nincs kapcsolat")
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val timeout = deadlines.schedule({ try { s.close() } catch (_: IOException) {} }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            val output = s.getOutputStream()
            val input = s.getInputStream()
            output.write((cmd + "\r").toByteArray(Charsets.US_ASCII)); output.flush()
            val buffer = StringBuilder()
            while (true) {
                val left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
                if (left <= 0) throw SocketTimeoutException("A parancs teljes határideje lejárt")
                s.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                val b = input.read()
                if (b < 0) throw IOException("A kapcsolat megszakadt")
                if (b == '>'.code) break
                if (buffer.length >= 32768) throw IOException("Túl hosszú ELM-válasz, prompt nélkül")
                buffer.append(b.toChar())
            }
            val response = buffer.toString().trim()
            check(response.isNotEmpty()) { "Üres adapterválasz" }
            // NO DATA is an explicit per-query failure, but the prompt keeps transport aligned.
            response
        } catch (e: Exception) {
            disconnect(); throw e
        } finally { timeout.cancel(false) }
    }

    private fun drainUntilQuiet(s: Socket, quietMs: Int = 300, maxMs: Long = 1500) {
        val input = s.getInputStream()
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMs)
        s.soTimeout = quietMs
        try {
            while (System.nanoTime() < end) if (input.read() < 0) throw IOException("A kapcsolat megszakadt")
        } catch (_: SocketTimeoutException) { }
    }

    fun disconnect() {
        val previous = synchronized(stateLock) {
            ready = false
            val old = socket; socket = null; old
        }
        try { previous?.close() } catch (_: IOException) {}
    }
    fun isConnected() = ready && socket?.let { it.isConnected && !it.isClosed } == true
    fun close() { disconnect(); deadlines.shutdownNow() }
}
