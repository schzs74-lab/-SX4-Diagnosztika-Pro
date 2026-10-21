package hu.sx4.diagnosztika

/** Address-preserving transport decoding. No address is assumed to mean 'engine ECU'. */
object EcuChannel {
    fun messages(raw: String, command: String, protocol: Int): List<Obd.Message> {
        if (protocol in 6..9) return Obd.messages(raw, command).also { messages ->
            require(messages.all { it.can }) { "Az adapter nem küld értelmezhető CAN-forráscímeket ATH1 mellett" }
        }
        require(protocol in 1..5) { "A forráscím szerinti kezelés ezen a protokollon még nem támogatott" }
        // Reuse echo/status/error validation and hex parsing, then decode the legacy envelope.
        return Obd.messages(raw, command).map { message ->
            require(message.source == "headerless") { "Váratlan K-line keret" }
            val bytes = message.data
            val checksum = if (protocol in 1..2) j1850Crc(bytes.dropLast(1)) else bytes.dropLast(1).sum() and 255
            require(bytes.size >= 5 && checksum == bytes.last()) { "Csonka vagy hibás nem-CAN ellenőrzőösszeg" }
            val data = if (protocol in 1..3) {
                if (protocol == 3) require(bytes.take(2) == listOf(0x48, 0x6B)) { "Ismeretlen ISO 9141 fejléc" }
                bytes.drop(3).dropLast(1)
            } else {
                require(bytes[0] and 0x80 != 0 && bytes[1] == 0xF1) { "Ismeretlen KWP címzés" }
                val shortLength = bytes[0] and 0x3F
                val offset = if (shortLength == 0) 4 else 3
                val length = if (shortLength == 0) bytes.getOrNull(3) ?: 0 else shortLength
                require(length > 0 && bytes.size == offset + length + 1) { "Hibás KWP kerethossz" }
                bytes.drop(offset).dropLast(1)
            }
            require(data.isNotEmpty()) { "Üres K-line adat" }
            Obd.Message("%02X".format(bytes[2]), data)
        }
    }

    fun supportBySource(raw: String, protocol: Int): Map<String, Set<Int>> {
        val messages = messages(raw, "0100", protocol)
        require(messages.map { it.source }.distinct().size == messages.size) { "Ismételt ECU-válasz a támogatási maszkban" }
        return messages.associate { message ->
            message.source to Obd.pids(hex(message.data))
        }
    }

    /** The returned text contains only the selected ECU's decoded OBD payloads, not raw bus frames. */
    fun selected(raw: String, command: String, protocol: Int, source: String): String {
        if (raw.trim() == "NO DATA") return raw
        val selected = messages(raw, command, protocol).filter { it.source == source }
        require(selected.isNotEmpty()) { "A kiválasztott ECU ($source) nem válaszolt" }
        return selected.joinToString("\r") { hex(it.data) }
    }

    fun clearAcknowledged(raw: String, protocol: Int, source: String): Boolean = try {
        val messages = messages(raw, "04", protocol)
        messages.size == 1 && messages.single().source == source && messages.single().data == listOf(0x44)
    } catch (_: Exception) { false }

    private fun hex(bytes: List<Int>) = bytes.joinToString(" ") { "%02X".format(it) }

    private fun j1850Crc(bytes: List<Int>): Int {
        var crc = 0xFF
        for (byte in bytes) {
            crc = crc xor byte
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x1D) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc xor 0xFF
    }
}
