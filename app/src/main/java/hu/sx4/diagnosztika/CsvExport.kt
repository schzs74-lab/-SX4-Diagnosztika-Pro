package hu.sx4.diagnosztika

import java.io.*
import java.math.BigDecimal
import java.nio.charset.CodingErrorAction

/** Produces a validated private export before any destination is opened. Source files stay unchanged. */
object CsvExport {
    private val schema1 = listOf("schema", "session_id", "mode", "ecu", "polling_profile", "app_version", "utc_time",
        "elapsed_ms", "pid_hex", "value", "unit", "payload_hex", "reading", "status")
    private val schema2 = schema1 + listOf("rate_hz", "request_ms", "channel_errors", "rpm", "rpm_age_ms", "fuel_status", "fuel_age_ms", "context")
    private val numericColumns = setOf("schema", "elapsed_ms", "value", "rate_hz", "request_ms", "channel_errors",
        "rpm", "rpm_age_ms", "fuel_status", "fuel_age_ms")
    private val decimal = Regex("[+-]?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private const val MAX_SOURCE_BYTES = 10 * 1024 * 1024L
    private const val MAX_OUTPUT_BYTES = 32 * 1024 * 1024L
    private const val MAX_FIELD_CHARS = 16_384
    private const val MAX_COLUMNS = 128
    private const val MAX_RECORDS = 100_002

    fun hungarianName(name: String): String = name.removeSuffix(".csv") + "-excel-hu.csv"
    class Prepared internal constructor(val file: File) : Closeable {
        override fun close() { file.delete() }
    }

    /** Constant-memory production path, including a byte-exact standard copy. */
    fun prepare(source: File, cacheDirectory: File, hungarian: Boolean): Prepared {
        require(source.isFile && source.length() <= MAX_SOURCE_BYTES) { "Hiányzó vagy túl nagy mérésfájl" }
        val directory = File(cacheDirectory, "csv-export")
        check(directory.mkdirs() || directory.isDirectory) { "Az export ideiglenes mappája nem hozható létre" }
        val temporary = File.createTempFile("sx4-export-", ".part", directory)
        try {
            FileOutputStream(temporary).use { output ->
                val limited = LimitedOutput(output, if(hungarian) MAX_OUTPUT_BYTES else MAX_SOURCE_BYTES)
                if (hungarian) {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    source.inputStream().use { input ->
                        InputStreamReader(input, decoder).buffered().use { reader ->
                            val writer = OutputStreamWriter(limited, Charsets.UTF_8).buffered()
                            convert(reader, writer); writer.flush()
                        }
                    }
                } else {
                    val expected = source.length()
                    val copied = source.inputStream().use { it.copyTo(limited) }
                    check(copied == expected && source.length() == expected) { "A forrásfájl megváltozott exportálás közben" }
                }
                output.fd.sync()
            }
            return Prepared(temporary)
        } catch (e: Throwable) { temporary.delete(); throw e }
    }

    /** Small-input convenience/tests. Large files must use prepare(), never readText(). */
    fun toHungarianExcel(text: String): String = StringWriter().also { convert(StringReader(text), it) }.toString()

    private fun convert(reader: Reader, writer: Writer) {
        var header: List<String>? = null
        var numeric = emptySet<Int>()
        var schemaIndex = -1
        var version = ""
        writer.write("\uFEFF")
        records(reader, ',') { record ->
            val isHeader = header == null
            if (isHeader) {
                require(record.distinct().size == record.size) { "Ismétlődő CSV-oszlopnév" }
                version = when(record.toSet()) {
                    schema1.toSet() -> "1"
                    schema2.toSet() -> "2"
                    else -> throw IllegalArgumentException("Ismeretlen vagy hiányos mérésfájl-fejléc")
                }
                header = record
                numeric = record.indices.filter { record[it] in numericColumns }.toSet()
                schemaIndex = record.indexOf("schema")
            } else {
                require(record.size == header!!.size) { "A CSV-sor mezőszáma eltér a fejlécétől" }
                require(record[schemaIndex] == version) { "A sor sémája nem egyezik a mérésfájl fejlécével" }
            }
            record.forEachIndexed { index, field ->
                if(index != 0) writer.write(";")
                val value = if(!isHeader && index in numeric && field.isNotEmpty()) numericText(field) else field
                writer.write("\""); writer.write(value.replace("\"", "\"\"")); writer.write("\"")
            }
            writer.write("\r\n")
        }
        require(header != null) { "Üres mérésfájl" }
    }

    private fun numericText(field: String): String {
        if(field.startsWith("'")) return field // Never turn protected text into a formula.
        require(decimal.matches(field)) { "Hibás számérték a mérésfájlban" }
        val number = BigDecimal(field)
        require(number.precision() <= 128 && number.scale() in -128..128) { "Túl nagy vagy túl pontos számérték" }
        return number.toPlainString().replace('.', ',')
    }

    /** Small-input parser sharing the exact lexer used by the streaming converter. LF is supported too. */
    fun parse(text: String, delimiter: Char = ','): List<List<String>> = mutableListOf<List<String>>().also { result ->
        records(StringReader(text), delimiter) { result.add(it) }
    }

    private enum class FieldState { START, PLAIN, QUOTED, CLOSED }
    private fun records(source: Reader, delimiter: Char, accept: (List<String>) -> Unit) {
        require(delimiter !in listOf('"', '\r', '\n'))
        val reader = PushbackReader(source, 1)
        val first = reader.read(); if(first != -1 && first != 0xFEFF) reader.unread(first)
        var state = FieldState.START
        var dirty = false
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var count = 0
        fun append(c: Char) { require(field.length < MAX_FIELD_CHARS) { "Túl hosszú CSV-mező" }; field.append(c) }
        fun endField() {
            require(record.size < MAX_COLUMNS) { "Túl sok CSV-oszlop" }
            record.add(field.toString()); field.setLength(0); state = FieldState.START
        }
        fun endRecord() {
            endField(); require(++count <= MAX_RECORDS) { "Túl sok CSV-sor" }
            accept(record); record = mutableListOf(); dirty = false
        }
        while(true) {
            val byte = reader.read(); if(byte == -1) break
            val c = byte.toChar(); dirty = true
            when {
                state == FieldState.QUOTED -> if(c == '"') state = FieldState.CLOSED else append(c)
                state == FieldState.CLOSED && c == '"' -> { append('"'); state = FieldState.QUOTED }
                c == delimiter -> endField()
                c == '\r' -> { require(reader.read() == '\n'.code) { "Hibás CSV-sortörés" }; endRecord() }
                c == '\n' -> endRecord()
                state == FieldState.CLOSED -> throw IllegalArgumentException("Váratlan karakter a CSV-mező lezáró idézőjele után")
                c == '"' -> { require(state == FieldState.START) { "Hibás CSV-idézőjel" }; state = FieldState.QUOTED }
                else -> { append(c); state = FieldState.PLAIN }
            }
        }
        require(state != FieldState.QUOTED) { "Lezáratlan CSV-mező" }
        if(dirty || record.isNotEmpty()) endRecord()
    }

    private class LimitedOutput(private val output: OutputStream, private val limit: Long) : OutputStream() {
        private var count = 0L
        override fun write(b: Int) { check(count < limit) { "Az exportfájl túl nagy" }; output.write(b); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(count + length <= limit) { "Az exportfájl túl nagy" }; output.write(bytes,offset,length); count += length
        }
    }
}
