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
            // K-line (ISO 9141-2 / ISO 14230-4) init status, e.g. "BUS INIT: ...OK"; "...ERROR" is rejected below.
            if (line.isEmpty() || (line.startsWith("BUS INIT") && line.endsWith("OK"))) continue
            require(!listOf("NO DATA", "STOPPED", "ERROR", "UNABLE", "BUS ERROR", "BUS BUSY", "CAN ERROR", "BUFFER FULL",
                    "LV RESET", "ACT ALERT", "?").any { line.contains(it) }) {
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
            val h = header ?: Regex("^([0-9A-F]{3}|18D[AB][0-9A-F]{4})\\s+(.+)$").matchEntire(line)
                ?: error("Nem értelmezhető ECU-válasz: $raw")
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
            bytes.chunked(2).filter { it != listOf(0, 0) }.map { (a, b) -> dtc(a, b) }
        }.distinct()

    private fun dtc(a: Int, b: Int) = "%c%d%X%02X".format("PCBU"[(a shr 6) and 3], (a shr 4) and 3, a and 15, b)

    /**
     * Follows the support pages 0x00 → 0x20 → … → 0xE0 while the "next page supported" bit (PID base+0x20)
     * is set. [fetch] returns null when the ECU refuses a page; the chain then ends with what is known.
     */
    fun supportChain(first: Set<Int>, fetch: (base: Int) -> Set<Int>?): Set<Int> {
        val all = first.toMutableSet()
        var base = 0
        while (base + 0x20 <= 0xE0 && base + 0x20 in all) {
            base += 0x20
            all += fetch(base) ?: break
        }
        return all
    }

    /** Mode 02 support mask of freeze frame 0: reply `42 <base> 00 <4 mask bytes>`. */
    fun freezeFramePids(response: String, base: Int = 0): Set<Int> {
        require(base in 0..0xE0 && base % 32 == 0)
        val data = freezeFrameData(response, base)
        require(data.size == 4) { "Csonka vagy hibás Freeze Frame támogatási maszk" }
        val mask = data.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
        return (1..32).filter { mask and (1L shl (32 - it)) != 0L }.map { base + it }.toSet()
    }

    /** Data bytes of one PID from freeze frame 0, without the frame number. */
    fun freezeFrameData(response: String, pid: Int): List<Int> {
        val data = bytesFor(response, 2, pid)
        require(data.firstOrNull() == 0) { "Nem a 0. Freeze Frame válasza" }
        return data.drop(1)
    }

    /** Freeze frame PID 02: the DTC that stored the frame; null when no frame is stored (`00 00`). */
    fun freezeFrameDtc(data: List<Int>): String? {
        require(data.size == 2) { "Csonka Freeze Frame hibakód" }
        return if (data == listOf(0, 0)) null else dtc(data[0], data[1])
    }

    data class Monitor(val name: String, val supported: Boolean, val complete: Boolean)
    data class Readiness(val mil: Boolean, val dtcCount: Int, val sparkIgnition: Boolean, val monitors: List<Monitor>)

    /** PID 01 (SAE J1979): MIL, ECU DTC count and monitor support / completion. */
    fun readiness(data: List<Int>): Readiness {
        require(data.size == 4) { "Csonka readiness-adat (0101)" }
        val (a, b, c, d) = data
        val continuous = listOf("Gyújtáskimaradás", "Üzemanyag-rendszer", "Alkatrészek").mapIndexed { i, name ->
            Monitor(name, (b shr i) and 1 == 1, (b shr (i + 4)) and 1 == 0)
        }
        val spark = b and 0x08 == 0
        val names = if (spark) listOf("Katalizátor", "Fűtött katalizátor", "EVAP-rendszer", "Szekunder levegő",
            "Klíma hűtőközeg", "Lambdaszonda", "Lambdaszonda-fűtés", "EGR-rendszer")
        else listOf("NMHC-katalizátor", "NOx/SCR", "Fenntartott", "Feltöltőnyomás", "Fenntartott",
            "Kipufogógáz-érzékelő", "Részecskeszűrő", "EGR/VVT")
        val periodic = names.mapIndexed { i, name -> Monitor(name, (c shr i) and 1 == 1, (d shr i) and 1 == 0) }
        return Readiness(a and 0x80 != 0, a and 0x7F, spark, continuous + periodic)
    }

    fun readinessLines(r: Readiness): List<String> =
        listOf("Hibalámpa (MIL): ${if (r.mil) "BE" else "KI"} • ECU szerinti hibakódszám: ${r.dtcCount}") +
            r.monitors.filter { it.supported }.map { "${it.name}: ${if (it.complete) "KÉSZ" else "NEM KÉSZ"}" } +
            r.monitors.filter { !it.supported }.map { it.name }.let {
                if (it.isEmpty()) emptyList() else listOf("Nem támogatott: " + it.joinToString(", "))
            }

    fun clearAcknowledged(response: String): Boolean = try {
        payloads(response, 4).all { it.data.isEmpty() }
    } catch (_: Exception) { false }

    /** Data length of every Mode 01/02 PID [reading] can decode. */
    private val pidLength = mapOf(
        0x03 to 2, 0x04 to 1, 0x05 to 1, 0x06 to 1, 0x07 to 1, 0x0B to 1, 0x0C to 2, 0x0D to 1, 0x0E to 1,
        0x0F to 1, 0x10 to 2, 0x11 to 1, 0x13 to 1, 0x14 to 2, 0x15 to 2, 0x1C to 1, 0x1F to 2, 0x21 to 2,
        0x24 to 4, 0x25 to 4, 0x2F to 1, 0x30 to 1, 0x31 to 2, 0x33 to 1, 0x3C to 2, 0x42 to 2, 0x43 to 2,
        0x44 to 2, 0x45 to 1, 0x46 to 1, 0x4D to 2, 0x4E to 2,
    )
    fun decodable(pid: Int) = pid in pidLength

    private fun fuelSystem(v: Int) = when (v) {
        1 -> "nyitott kör (a zárt kör feltételei még nem teljesülnek)"
        2 -> "zárt kör (lambdaszabályozás)"
        4 -> "nyitott kör (terhelés vagy motorfék miatt)"
        8 -> "nyitott kör (rendszerhiba miatt)"
        16 -> "zárt kör, de visszacsatolási hiba"
        else -> "ismeretlen állapot (%02X)".format(v)
    }

    private val obdStandards = mapOf(1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD és OBD-II", 4 to "OBD-I",
        5 to "nem OBD-kompatibilis", 6 to "EOBD (Európa)", 7 to "EOBD és OBD-II", 8 to "EOBD és OBD",
        9 to "EOBD, OBD és OBD-II", 10 to "JOBD (Japán)", 11 to "JOBD és OBD-II", 12 to "JOBD és EOBD", 13 to "JOBD, EOBD és OBD-II")

    fun reading(pid: Int, data: List<Int>): String {
        val length = pidLength[pid] ?: error("Ismeretlen mérési PID")
        require(data.size == length) { "Csonka vagy hibás mérési adat: %02X".format(pid) }
        val a = data[0]; val b = data.getOrElse(1) { 0 }
        val ab = 256 * a + b
        fun f(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", value)
        return when (pid) {
            0x03 -> "Üzemanyag-rendszer: ${fuelSystem(a)}" + (if (b != 0) " • 2. rendszer: ${fuelSystem(b)}" else "")
            0x13 -> "Lambdaszondák: B1: " + ((0..3).filter { (a shr it) and 1 == 1 }.joinToString(",") { "S${it + 1}" }.ifEmpty { "–" }) +
                " • B2: " + ((0..3).filter { (a shr (it + 4)) and 1 == 1 }.joinToString(",") { "S${it + 1}" }.ifEmpty { "–" })
            0x1C -> "OBD-szabvány: ${obdStandards[a] ?: "egyéb ($a)"}"
            0x1F -> "Motorjárás az indítás óta: $ab s"
            0x21 -> "Megtett út égő hibalámpával: $ab km"
            0x24, 0x25 -> "O2 S${pid - 0x23} (széles sávú): λ ${f(ab * 2.0 / 65536, 3)}; ${f((256 * data[2] + data[3]) * 8.0 / 65536, 3)} V"
            0x2F -> "Üzemanyagszint: ${f(a * 100.0 / 255)} %"
            0x30 -> "Bemelegedések a törlés óta: $a"
            0x31 -> "Megtett út a törlés óta: $ab km"
            0x33 -> "Légköri nyomás: $a kPa"
            0x3C -> "Katalizátor-hőmérséklet B1S1: ${f(ab / 10.0 - 40)} °C"
            0x42 -> "ECU tápfeszültség: ${f(ab / 1000.0, 2)} V"
            0x43 -> "Abszolút terhelés: ${f(ab * 100.0 / 255)} %"
            0x44 -> "Előírt lambda: ${f(ab / 32768.0, 3)}"
            0x45 -> "Relatív fojtószelep: ${f(a * 100.0 / 255)} %"
            0x46 -> "Külső hőmérséklet: ${a - 40} °C"
            0x4D -> "Idő égő hibalámpával: $ab perc"
            0x4E -> "Idő a hibakódtörlés óta: $ab perc"
            0x04 -> "Számított terhelés: ${f(a * 100.0 / 255)} %"
            0x0B -> "Szívócső-nyomás (MAP): $a kPa"
            0x0E -> "Gyújtási előgyújtás: ${f(a / 2.0 - 64)} °"
            0x0F -> "Beszívott levegő: ${a - 40} °C"
            0x10 -> "Légtömegáram (MAF): ${f((256 * a + b) / 100.0, 2)} g/s"
            0x0C -> "Fordulatszám: ${f((256 * a + b) / 4.0, 2)} 1/perc"
            0x05 -> "Hűtőfolyadék: ${a - 40} °C"
            0x0D -> "Sebesség: $a km/h"
            0x06, 0x07 -> "${if (pid == 6) "STFT" else "LTFT"} B1: ${f((a - 128) * 100.0 / 128)} %"
            0x11 -> "Fojtószelep: ${f(a * 100.0 / 255)} %"
            else -> "O2 B1S${if (pid == 0x14) 1 else 2}: ${f(a / 200.0, 3)} V; korrekció: ${if (b == 255) "nem elérhető" else f((b - 128) * 100.0 / 128) + " %"}"
        }
    }

    /**
     * SAE J1979 Mode 04 is intended for ignition on / engine off. Returns a Hungarian reason when the
     * fresh RPM/speed readings do not prove a stationary, stopped engine; null when clearing may proceed.
     */
    fun clearBlockReason(rpmData: List<Int>?, speedData: List<Int>?, speedSupported: Boolean): String? {
        if (rpmData == null || rpmData.size != 2) return "A fordulatszám nem ellenőrizhető; törlés tiltva"
        val rpm = (256 * rpmData[0] + rpmData[1]) / 4.0
        if (rpm > 0.0) return "A motor jár (${String.format(Locale.ROOT, "%.0f", rpm)} 1/perc); törlés csak álló motornál, gyújtás ráadva"
        if (!speedSupported) return null
        if (speedData == null || speedData.size != 1) return "A járműsebesség nem ellenőrizhető; törlés tiltva"
        if (speedData[0] != 0) return "A jármű mozog (${speedData[0]} km/h); törlés tiltva"
        return null
    }
}
