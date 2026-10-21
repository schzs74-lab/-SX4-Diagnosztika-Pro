package hu.sx4.diagnosztika

/** Standard OBD Mode 09 information only; no manufacturer-specific sessions or writes. */
class EcuIdentity(
    private val send: (String) -> String,
    private val canProtocol: Boolean,
    private val connected: () -> Boolean = { true },
) {
    private fun ask(command: String): String {
        check(connected()) { "Azonosítás megszakítva" }
        return send(command).also { check(connected()) { "Azonosítás megszakítva" } }
    }

    private fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        if (!connected()) throw e
        Result.failure(e)
    }

    fun report(): List<String> {
        val lines = mutableListOf<String>()
        for ((command, label) in listOf("ATI" to "Adapter saját azonosítója", "ATDPN" to "ELM protokollkód")) {
            val reply = attempt { ask(command).replace('>', ' ').trim().also { require(it.isNotEmpty()) } }
            lines += "$label: " + reply.getOrElse { "nem elérhető (${it.message})" }
        }
        val supported = attempt { supportMask(ask("0900"), canProtocol) }
        if (supported.isFailure) lines += "Mode 09 támogatási maszk nem elérhető; az azonosítókat külön megpróbáljuk kiolvasni."
        for ((pid, label) in listOf(2 to "VIN", 4 to "Kalibrációazonosító (CAL ID)", 6 to "Kalibráció ellenőrzőszáma (CVN)", 10 to "ECU neve")) {
            if (supported.getOrNull()?.let { pid !in it } == true) {
                lines += "$label: az ECU támogatási maszkja szerint nem támogatott"
                continue
            }
            val records = attempt { decode(ask("09%02X".format(pid)), pid, canProtocol) }
            records.fold(
                onSuccess = { entries -> entries.forEach { lines += "$label: $it" } },
                onFailure = { lines += "$label: nem elérhető vagy hibás válasz (${it.message})" },
            )
        }
        lines += "Ezek szabványos OBD-adatok. Gyártói szervizfunkciók és ECU-paraméterírás támogatását nem igazolják."
        return lines
    }

    companion object {
        fun supportMask(response: String, canProtocol: Boolean = true): Set<Int> {
            val payload = Obd.bytesFor(response, 9, 0)
            // Accommodate the single numbered legacy support record, without accepting it on CAN.
            val bytes = if (!canProtocol && payload.size == 5 && payload.first() == 1) payload.drop(1) else payload
            require(bytes.size == 4) { "Hibás Mode 09 támogatási maszk" }
            val mask = bytes.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
            return (1..32).filter { mask and (1L shl (32 - it)) != 0L }.toSet()
        }

        private fun ascii(bytes: List<Int>): String {
            val textBytes = bytes.dropLastWhile { it == 0 || it == 32 }
            require(textBytes.isNotEmpty() && textBytes.all { it in 32..126 }) { "Hibás azonosítószöveg" }
            return textBytes.map { it.toChar() }.joinToString("")
        }

        /** Reject ambiguous headerless multi-ECU replies instead of joining unrelated identities. */
        fun decode(response: String, pid: Int, canProtocol: Boolean): List<String> {
            require(pid in setOf(2, 4, 6, 10))
            val width = when (pid) { 2 -> 17; 4 -> 16; 6 -> 4; else -> 20 }
            val parts = Obd.payloads(response, 9, pid)
            val records: List<Pair<String, List<Int>>> = if (canProtocol) {
                require(parts.size == 1 || parts.all { it.can } && parts.map { it.source }.distinct().size == parts.size) {
                    "Több ECU cím nélküli válasza nem különíthető el"
                }
                parts.flatMap { part ->
                    val count = part.data.firstOrNull() ?: error("Hiányzó azonosítószám")
                    require(count in 1..32 && (pid != 2 || count == 1) && part.data.size == 1 + count * width) {
                        "Csonka vagy hibás azonosítóhossz"
                    }
                    part.data.drop(1).chunked(width).map { part.source to it }
                }
            } else {
                require(parts.all { it.source == "headerless" && it.data.size == 5 }) { "Nem értelmezhető nem-CAN azonosítókeret" }
                require(parts.map { it.data.first() } == (1..parts.size).toList()) { "Hiányzó vagy ismételt azonosítókeret" }
                val bytes = parts.flatMap { it.data.drop(1) }
                if (pid == 2) {
                    require(bytes.size == 20 && bytes.take(3).all { it == 0 }) { "Csonka vagy hibás VIN-keretek" }
                    listOf("headerless" to bytes.drop(3))
                } else {
                    require(bytes.size in width..(width * 32) && bytes.size % width == 0) { "Csonka azonosítókeretek" }
                    bytes.chunked(width).map { "headerless" to it }
                }
            }
            return records.map { (source, bytes) ->
                val value = if (pid == 6) bytes.joinToString("") { "%02X".format(it) } else {
                    val text = if (pid == 10 && bytes[4] == 0x2D) {
                        // The four-byte acronym may be NUL padded before the fixed '-' delimiter.
                        ascii(bytes.take(4)) + " — " + ascii(bytes.drop(5))
                    } else ascii(bytes)
                    text.also {
                        if (pid == 2) require(it.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) { "Érvénytelen VIN" }
                    }
                }
                if (source in setOf("headerless", "numbered")) value else "$value (ECU: $source)"
            }
        }
    }
}
