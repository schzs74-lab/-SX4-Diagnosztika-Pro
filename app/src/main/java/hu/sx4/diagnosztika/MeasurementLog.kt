package hu.sx4.diagnosztika

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Owned by the single polling worker. Only completed .csv files are offered for export. */
class MeasurementLog(
    directory: File,
    val mode: String,
    private val source: String,
    private val profile: String,
    private val version: String,
    private val startedUtcMs: Long,
    private val startedElapsedMs: Long,
    private val maxRows: Int = 100_000,
    private val maxBytes: Long = 10 * 1024 * 1024,
    private val maxDurationMs: Long = 3_600_000,
) {
    private val id = UUID.randomUUID().toString()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(startedUtcMs))
    val file = File(directory, "sx4-${mode.lowercase(java.util.Locale.ROOT)}-$stamp-$id.csv")
    private val partial = File(directory, file.name + ".part")
    private val output: FileOutputStream
    private var written = 0L
    var rows = 0
        private set
    var closed = false
        private set
    init {
        require(mode in setOf("REAL", "DEMO") && maxRows > 0 && maxBytes >= 4096 && maxDurationMs > 0)
        check(directory.mkdirs() || directory.isDirectory) { "A mérésmappa nem hozható létre" }
        check(partial.createNewFile()) { "A mérésfájl már létezik" }
        output = FileOutputStream(partial)
        try {
            write("\uFEFFschema,session_id,mode,ecu,polling_profile,app_version,utc_time,elapsed_ms,pid_hex,value,unit,payload_hex,reading,status\r\n")
            output.fd.sync()
        } catch (e: Exception) { output.close(); throw e }
    }
    fun append(sample: LiveSample, atMs: Long): Boolean = appendRow(atMs, sample.pid,
        sample.value?.toString() ?: "", sample.unit, sample.data.joinToString(" ") { "%02X".format(it) }, sample.reading, "OK")
    fun failure(pid: Int, message: String, atMs: Long): Boolean = appendRow(atMs, pid, "", "", "", message.take(2048), "ERROR")
    private fun appendRow(atMs: Long, pid: Int, value: String, unit: String, bytes: String, reading: String, state: String): Boolean {
        check(!closed) { "A rögzítés már lezárult" }
        require(atMs >= startedElapsedMs)
        val row = row(atMs, "%02X".format(pid), value, unit, bytes, reading, state)
        if (rows >= maxRows || atMs - startedElapsedMs > maxDurationMs || written + row.toByteArray(Charsets.UTF_8).size + 4096 > maxBytes) {
            finish("LIMIT: idő- vagy méretkorlát", atMs)
            return false
        }
        write(row); rows++
        if (rows % 25 == 0) output.fd.sync()
        return true
    }
    fun finish(reason: String, atMs: Long): File {
        if (closed) { check(file.isFile) { "A korábbi mentés sikertelen" }; return file }
        closed = true
        try {
            write(row(atMs.coerceAtLeast(startedElapsedMs), "", "", "", "", reason.take(256), "END"))
            output.fd.sync()
        } finally { output.close() }
        check(partial.renameTo(file)) { "A mérésfájl véglegesítése sikertelen" }
        return file
    }
    fun abort() { closed = true; try { output.close() } catch (_: Exception) {} }
    private fun row(atMs: Long, pid: String, value: String, unit: String, bytes: String, reading: String, state: String): String {
        val elapsed = atMs - startedElapsedMs
        val utc = Instant.ofEpochMilli(startedUtcMs + elapsed).toString()
        return listOf("1", id, mode, source, profile, version, utc, elapsed.toString(), pid, value, unit, bytes, reading, state)
            .mapIndexed { index, item -> csv(item, numeric = index in setOf(0, 7, 9)) }.joinToString(",") + "\r\n"
    }
    private fun write(text: String) { val bytes = text.toByteArray(Charsets.UTF_8); output.write(bytes); written += bytes.size }
    companion object {
        private fun csv(value: String, numeric: Boolean): String {
            val safe = if (!numeric && value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        fun saved(directory: File): List<File> = directory.listFiles()?.filter { it.isFile && it.name.startsWith("sx4-") && it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
