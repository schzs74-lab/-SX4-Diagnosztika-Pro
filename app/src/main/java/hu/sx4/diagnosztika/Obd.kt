package hu.sx4.diagnosztika

import java.util.Locale

/** Strict decoding: an unrecognised/truncated reply is never an empty successful reading. */
object Obd {
    data class Message(val source: String, val data: List<Int>, val can: Boolean = false)
    private fun hex(s: String): List<Int> {
        val compact = s.replace(" ", "").replace("\t", "")
        require(compact.isNotEmpty() && compact.length % 2 == 0 && compact.matches(Regex("[0-9A-F]+"))) {
            "Hibás vagy csonka hexadecimális válasz: $s"
        }
        return compact.chunked(2).map { it.toInt(16) }
    }

    /** CAF1 headerless replies, ELM length + numbered lines, and headered 11/29-bit ISO-TP. */
    fun messages(response: String, command: String = ""): List<Message> {
        val result = mutableListOf<Message>()
        data class Assembly(val length: Int, val data: MutableList<Int>, var next: Int = 1)
        val assemblies = mutableMapOf<String, Assembly>()
        var numberedLength: Int? = null
        var numberedNext = 0
        val numbered = mutableListOf<Int>()
        val echo = command.replace(" ", "").uppercase(Locale.ROOT)
        for (raw in response.uppercase(Locale.ROOT).replace('>', '\n').split(Regex("[\r\n]+"))) {
            var line = raw.trim()
            if (line.isEmpty() || (echo.isNotEmpty() && line.replace(" ", "") == echo)) continue
            if (line.startsWith("SEARCHING...")) line = line.removePrefix("SEARCHING...").trim()
            if (line.isEmpty() || line == "BUS INIT: OK") continue
            require(!listOf("NO DATA", "STOPPED", "ERROR", "UNABLE", "BUS ERROR", "CAN ERROR", "BUFFER FULL", "?").any { line.contains(it) }) {
                "Nem értelmezhető ECU-válasz: $line"
            }
            if (line.matches(Regex("[0-9A-F]{3}"))) {
                require(numberedLength == null && numbered.isEmpty()) { "Ismételt ELM hossz" }
                numberedLength = line.toInt(16)
                require(numberedLength!! in 1..4095) { "Hibás ELM hossz" }
                continue
            }
            val indexed = Regex("^([0-9A-F]+):\\s*(.*)$").matchEntire(line)
            if (indexed != null) {
                require(numberedLength != null && indexed.groupValues[1].toInt(16) == numberedNext++) { "Hiányzó ELM keret vagy hossz" }
                numbered += hex(indexed.groupValues[2])
                continue
            }
            val header = Regex("^([0-9A-F]{3}|18D[AB][0-9A-F]{4})\\s+(.+)$").matchEntire(line)
            if (header == null) {
                // Compact CAN headers have an odd nibble count (11-bit) or a known 29-bit prefix.
                val compact = line.replace(" ", "")
                val headerSize = when {
                    compact.length % 2 == 1 && compact.length > 3 -> 3
                    compact.startsWith("18DA") && compact.length > 8 -> 8
                    else -> 0
                }
                if (headerSize == 0) { result += Message("headerless", hex(line)); continue }
                line = compact.take(headerSize) + " " + compact.drop(headerSize)
            }
            val h = header ?: Regex("^([0-9A-F]{3}|18D[AB][0-9A-F]{4})\\s+(.+)$").matchEntire(line)!!
            val source = h.groupValues[1]
            val tokens = h.groupValues[2].trim().split(Regex("\\s+"))
            // Optional one-nibble CAN DLC, emitted by some ELM clones with headers on.
            val bytes = hex(if (tokens.first().length == 1) {
                require(tokens.first().toInt(16) == tokens.size - 1) { "Hibás CAN DLC" }
                tokens.drop(1).joinToString(" ")
            } else h.groupValues[2])
            require(bytes.size in 1..8) { "Hibás CAN kerethossz" }
            when (bytes[0] shr 4) {
                0 -> {
                    val length = bytes[0] and 15
                    require(length in 1..7 && bytes.size >= length + 1 && source !in assemblies) { "Csonka ISO-TP egykeretes válasz" }
                    result += Message(source, bytes.drop(1).take(length), true)
                }
                1 -> {
                    require(bytes.size == 8 && source !in assemblies) { "Hibás ISO-TP kezdőkeret" }
                    val length = ((bytes[0] and 15) shl 8) or bytes[1]
                    require(length in 8..4095) { "Hibás ISO-TP hossz" }
                    assemblies[source] = Assembly(length, bytes.drop(2).toMutableList())
                }
                2 -> {
                    val a = assemblies[source] ?: error("ISO-TP folytatás kezdőkeret nélkül")
                    require((bytes[0] and 15) == a.next) { "Hiányzó vagy ismételt ISO-TP keret" }
                    require(bytes.size >= 1 + minOf(7, a.length - a.data.size)) { "Csonka ISO-TP folytatás" }
                    a.next = (a.next + 1) and 15
                    a.data += bytes.drop(1)
                    if (a.data.size >= a.length) {
                        result += Message(source, a.data.take(a.length), true)
                        assemblies.remove(source)
                    }
                }
                else -> error("Ismeretlen ISO-TP keret")
            }
        }
        require(assemblies.isEmpty()) { "Hiányos ISO-TP válasz" }
        if (numberedLength != null) {
            require(numbered.size == numberedLength) { "Hiányos vagy túl hosszú ELM válasz" }
            result += Message("numbered", numbered)
        }
        require(result.isNotEmpty()) { "Nem érkezett értelmezhető válasz" }
        return result
    }

    fun payloads(response: String, mode: Int, pid: Int? = null): List<Message> {
        val command = "%02X".format(mode) + (pid?.let { "%02X".format(it) } ?: "")
        val messages = messages(response, command)
        require(messages.all { it.data.firstOrNull() == mode + 0x40 && (pid == null || it.data.getOrNull(1) == pid) }) {
            "Váratlan vagy negatív OBD-válasz"
        }
        return messages.map { it.copy(data = it.data.drop(if (pid == null) 1 else 2)) }
    }

    fun bytesFor(response: String, mode: Int, pid: Int? = null): List<Int> {
        val all = payloads(response, mode, pid)
        require(all.size == 1) { "Több ECU válaszolt; a mérés nem egyértelmű" }
        return all.single().data
    }

    fun pids(response: String, base: Int = 0): Set<Int> {
        require(base in 0..0xE0 && base % 32 == 0)
        val data = bytesFor(response, 1, base)
        require(data.size == 4) { "Csonka vagy hibás PID-támogatási maszk" }
        val mask = data.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
        return (1..32).filter { mask and (1L shl (32 - it)) != 0L }.map { base + it }.toSet()
    }

    fun dtcs(response: String, canProtocol: Boolean? = null, mode: Int = 3): List<String> =
        payloads(response, mode).flatMap { message ->
            var bytes = message.data
            require(bytes.isNotEmpty()) { "Hiányzó hibakódadat" }
            val hasCount = canProtocol ?: (message.can || bytes.size % 2 == 1)
            if (hasCount) {
                require(bytes.isNotEmpty()) { "Hiányzó CAN hibakódszám" }
                val count = bytes.first()
                bytes = bytes.drop(1)
                require(bytes.size >= count * 2 && bytes.drop(count * 2).all { it == 0 }) { "Csonka vagy hibás CAN DTC-válasz" }
                bytes = bytes.take(count * 2)
                require(bytes.chunked(2).none { it == listOf(0, 0) }) { "Érvénytelen nullakód a CAN számlálóban" }
            }
            require(bytes.size % 2 == 0) { "Csonka hibakód" }
            bytes.chunked(2).filter { it != listOf(0, 0) }.map { (a, b) ->
                "%c%d%X%02X".format("PCBU"[(a shr 6) and 3], (a shr 4) and 3, a and 15, b)
            }
        }.distinct()

    fun clearAcknowledged(response: String): Boolean = try {
        payloads(response, 4).all { it.data.isEmpty() }
    } catch (_: Exception) { false }

    fun reading(pid: Int, data: List<Int>): String {
        val length = when (pid) { 0x0C, 0x14, 0x15 -> 2; 0x05, 0x0D, 0x06, 0x07, 0x11 -> 1; else -> error("Ismeretlen mérési PID") }
        require(data.size == length) { "Csonka vagy hibás mérési adat: %02X".format(pid) }
        val a = data[0]; val b = data.getOrElse(1) { 0 }
        fun f(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", value)
        return when (pid) {
            0x0C -> "Fordulatszám: ${f((256 * a + b) / 4.0, 2)} 1/perc"
            0x05 -> "Hűtőfolyadék: ${a - 40} °C"
            0x0D -> "Sebesség: $a km/h"
            0x06, 0x07 -> "${if (pid == 6) "STFT" else "LTFT"} B1: ${f((a - 128) * 100.0 / 128)} %"
            0x11 -> "Fojtószelep: ${f(a * 100.0 / 255)} %"
            else -> "O2 B1S${if (pid == 0x14) 1 else 2}: ${f(a / 200.0, 3)} V; korrekció: ${if (b == 255) "nem elérhető" else f((b - 128) * 100.0 / 128) + " %"}"
        }
    }
}
