package hu.sx4.diagnosztika

/**
 * Export variant for spreadsheet programs using Hungarian regional settings: ';' as list separator and
 * decimal comma in numeric columns. The source file stays unchanged; only the exported copy is converted.
 */
object CsvExport {
    /** Columns written as plain decimals by [MeasurementLog]; detected from the header, so schema 1 files work too. */
    private val numericColumns = setOf("schema", "elapsed_ms", "value", "rate_hz", "request_ms", "channel_errors",
        "rpm", "rpm_age_ms", "fuel_status", "fuel_age_ms")
    private val plainDecimal = Regex("-?[0-9]+(\\.[0-9]+)?")

    fun hungarianName(name: String): String = name.removeSuffix(".csv") + "-excel-hu.csv"

    fun toHungarianExcel(text: String): String {
        val records = parse(text.removePrefix("\uFEFF"))
        require(records.isNotEmpty()) { "Üres mérésfájl" }
        val header = records.first()
        val numeric = header.indices.filter { header[it] in numericColumns }.toSet()
        return "\uFEFF" + records.joinToString("") { record ->
            record.mapIndexed { index, field ->
                val value = if (index in numeric && plainDecimal.matches(field)) field.replace('.', ',') else field
                "\"" + value.replace("\"", "\"\"") + "\""
            }.joinToString(";") + "\r\n"
        }
    }

    /** RFC 4180 records; quoted fields may contain separators, doubled quotes and line breaks. */
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var fieldStarted = false
        var i = 0
        fun endField() { record.add(field.toString()); field.setLength(0); fieldStarted = false }
        fun endRecord() { endField(); records.add(record); record = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> { require(!fieldStarted && field.isEmpty()) { "Hibás CSV-idézőjel" }; quoted = true; fieldStarted = true }
                c == delimiter -> endField()
                c == '\r' && text.getOrNull(i + 1) == '\n' -> { endRecord(); i++ }
                c == '\n' -> endRecord()
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        require(!quoted) { "Lezáratlan CSV-mező" }
        if (fieldStarted || field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }
}
