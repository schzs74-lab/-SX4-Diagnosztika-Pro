package hu.sx4.diagnosztika

import java.net.InetSocketAddress
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class Elm327 {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val busy = AtomicBoolean(false)

    @Synchronized fun connect(host: String, port: Int): String {
        disconnect()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 3500)
        s.soTimeout = 3500
        socket = s; input = s.getInputStream(); output = s.getOutputStream()
        command("ATZ", 5000)
        command("ATE0")
        command("ATL0")
        command("ATS0")
        command("ATH0")
        command("ATSP0", 5000)
        return command("ATI")
    }
    @Synchronized fun command(cmd: String, timeoutMs: Int = 3500): String {
        val s = socket ?: throw IllegalStateException("Nincs kapcsolat")
        require(Regex("[A-Za-z0-9 ]{2,16}").matches(cmd)) { "Érvénytelen parancs" }
        s.soTimeout = timeoutMs
        val o = output ?: error("Nincs kimenet")
        val i = input ?: error("Nincs bemenet")
        while(i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray(Charsets.US_ASCII)); o.flush()
        val buffer = StringBuilder()
        while (buffer.length < 8192) {
            val b = i.read()
            if (b < 0) throw IllegalStateException("A kapcsolat megszakadt")
            if (b == '>'.code) break
            buffer.append(b.toChar())
        }
        val response = buffer.toString().trim()
        if (response.contains("UNABLE TO CONNECT") || response.contains("NO DATA") || response.contains("ERROR"))
            throw IllegalStateException("Adapter válasza: $response")
        return response
    }
    @Synchronized fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }
    fun isConnected() = socket?.isConnected == true && socket?.isClosed == false
}

object Obd {
    // ELM válasz normalizálása: a fejléc nélküli, szóközös és szóköz nélküli formát is kezeli.
    fun bytesFor(response: String, mode: Int, pid: Int? = null): List<Int> {
        val hexLines = response.uppercase().lines().map { it.replace(Regex("[^0-9A-F]"), "") }
        val prefix = "%02X".format(mode + 0x40) + (pid?.let { "%02X".format(it) } ?: "")
        val data = hexLines.firstOrNull { it.startsWith(prefix) } ?: return emptyList()
        return data.drop(prefix.length).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
    }
    fun pids(r: String): Set<Int> {
        val b = bytesFor(r, 1, 0)
        if (b.size < 4) return emptySet()
        val mask = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        return (1..32).filter { (mask and (1L shl (32-it))) != 0L }.toSet()
    }
    fun dtcs(response: String): List<String> {
        val bytes = bytesFor(response, 3)
        val codes = mutableListOf<String>()
        for (n in 0 until bytes.size / 2) {
            val a = bytes[2*n]; val b = bytes[2*n+1]
            if (a == 0 && b == 0) continue
            val prefix = "PCBU"[(a shr 6) and 3]
            codes += "$prefix${(a shr 4) and 3}${a and 15}${(b shr 4) and 15}${b and 15}"
        }
        return codes
    }
}
