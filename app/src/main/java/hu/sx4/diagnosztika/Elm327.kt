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
    @Volatile var ecuSources: Map<String, Set<Int>> = emptyMap()
        private set
    private data class Selection(val source: String, val supported: Set<Int>)
    @Volatile private var selection: Selection? = null
    val selectedSource: String? get() = selection?.source
    val supported: Set<Int> get() = selection?.supported ?: emptySet()
    @Volatile private var protocolNumber = 0
    @Volatile private var session = 0L

    fun selectEcu(source: String) = synchronized(stateLock) {
        check(isConnected()) { "Nincs kapcsolat" }
        selection = Selection(source, ecuSources[source] ?: error("Ismeretlen ECU"))
    }

    fun clearAvailabilityReason(): String? = when {
        !isConnected() || selectedSource == null -> "Előbb válassz csatlakoztatott vezérlőt"
        ecuSources.size != 1 -> "Több ECU válaszol. A jelenlegi funkcionális címzés mellett a törlés tiltva; vezérlőnkénti célzott törlés még nincs beépítve."
        else -> null
    }

    fun prepareClearPermit(storedResponse: String): ClearPermit = synchronized(ioLock) {
        clearAvailabilityReason()?.let { error(it) }
        ClearPermit(session, selectedSource!!, Obd.dtcs(storedResponse, canProtocol).toSet(), System.nanoTime())
    }

    /** The only Mode 04 path; every caller receives the same freshness and standstill checks. */
    fun clearDtcs(permit: ClearPermit, onDispatch: () -> Unit = {}, current: () -> Boolean = { true }) = synchronized(ioLock) {
        fun validate(codes: Set<String>? = null) {
            check(current() && isConnected()) { "Megszakított törlés" }
            clearAvailabilityReason()?.let { error(it) }
            permit.validate(session, selectedSource, System.nanoTime(), codes)
        }
        validate()
        // Functional Mode 04 can affect other responders; refuse if topology is no longer singular.
        val sourcesNow = EcuChannel.supportBySource(exchange("0100", 3500), protocolNumber).keys
        check(sourcesNow == setOf(permit.source)) { "Megváltozott a válaszoló vezérlők köre; törlés tiltva" }
        val codesNow = Obd.dtcs(command("03"), canProtocol).toSet()
        validate(codesNow)
        val rpm = Obd.bytesFor(command("010C"), 1, 0x0C)
        val speedSupported = 0x0D in supported
        val speed = if (speedSupported) Obd.bytesFor(command("010D"), 1, 0x0D) else null
        Obd.clearBlockReason(rpm, speed, speedSupported)?.let { error("$it. A 04 parancs NEM lett elküldve.") }
        validate()
        onDispatch()
        validate()
        permit.markDispatched()
        val response = try { exchange("04", 3500) } catch (e: Exception) { throw ClearOutcomeUnknown(e) }
        if (!EcuChannel.clearAcknowledged(response, protocolNumber, permit.source)) throw ClearOutcomeUnknown()
    }
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
            exchange("ATZ", 5000)
            for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATCAF1")) {
                val reply = exchange(cmd, 3500).replace(cmd, "").trim()
                check(reply == "OK") { "Inicializálás sikertelen ($cmd): $reply" }
            }
            val id = command("ATI")
            // Protocol selection happens only after the first ECU request, not after ATSP0.
            val firstRaw = command("0100", 12000)
            val protocolText = command("ATDPN").trim().uppercase(Locale.ROOT)
            val protocol = (if (protocolText.length == 2) protocolText.removePrefix("A") else protocolText).toIntOrNull(16)
                ?: error("Ismeretlen OBD-protokoll")
            check(protocol in 1..9) { "Ezen a protokollon a forráscím szerinti OBD-kezelés még nem támogatott" }
            protocolNumber = protocol
            canProtocol = protocol in 6..9
            val first = EcuChannel.supportBySource(firstRaw, protocol)
            ecuSources = first.mapValues { (source, support) ->
                Obd.supportChain(support) { base ->
                    try { Obd.pids(EcuChannel.selected(command("01%02X".format(base)), "01%02X".format(base), protocol, source), base) }
                    catch (e: Exception) { if (socket !== s || s.isClosed) throw e; null }
                }
            }
            selection = ecuSources.entries.singleOrNull()?.let { Selection(it.key, it.value) }
            check(!cancelled() && socket === s && !s.isClosed) { "Megszakított kapcsolódás" }
            ready = true
            "$id • protokoll: ${protocol.toString(16).uppercase(Locale.ROOT)} • ${ecuSources.size} ECU válaszolt"
        } catch (e: Exception) {
            disconnect(); throw e
        }
    }

    fun command(cmd: String, timeoutMs: Int = 3500): String = synchronized(ioLock) {
        // Only standard reads and fixed adapter configuration; Mode 04 is private to clearDtcs.
        require(cmd in setOf("ATI", "ATDPN", "ATRV", "03", "07", "0A", "0900", "0902", "0904", "0906", "090A") ||
            cmd.matches(Regex("01[0-9A-F]{2}|02[0-9A-F]{2}00"))) { "Nem engedélyezett parancs" }
        val selected = if (ready && !cmd.startsWith("AT")) selection ?: error("Előbb válassz vezérlőt") else null
        val response = exchange(cmd, timeoutMs)
        if (selected == null) response else EcuChannel.selected(response, cmd, protocolNumber, selected.source)
    }

    private fun exchange(cmd: String, timeoutMs: Int): String {
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
            return response
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
            ready = false; selection = null; ecuSources = emptyMap(); session++
            val old = socket; socket = null; old
        }
        try { previous?.close() } catch (_: IOException) {}
    }
    fun isConnected() = ready && socket?.let { it.isConnected && !it.isClosed } == true
    fun close() { disconnect(); deadlines.shutdownNow() }
}
