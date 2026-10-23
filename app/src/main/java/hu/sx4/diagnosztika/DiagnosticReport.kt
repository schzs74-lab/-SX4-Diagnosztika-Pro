package hu.sx4.diagnosztika

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Fresh read-only scan, not assembled from possibly stale UI labels. */
class DiagnosticReport(
    private val send: (String) -> String,
    private val supported: Set<Int>,
    private val canProtocol: Boolean,
    private val connected: () -> Boolean,
    private val current: () -> Boolean = { true },
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val utc: () -> Long = { System.currentTimeMillis() },
) {
    data class Section(val title: String, val fromMs: Long, val toMs: Long, val lines: List<String>)
    data class Snapshot(val source: String, val version: String, val mode: String, val startedUtcMs: Long,
        val durationMs: Long, val dtcs: List<DtcScan.Result>, val sections: List<Section>, val clearUncertain: Boolean) {
        fun text(): String = buildString {
            appendLine("SUZUKI SX4 – SZABVÁNYOS OBD DIAGNOSZTIKAI JELENTÉS")
            appendLine("${if (mode == "DEMO") "DEMO / SZIMULÁLT – NEM VALÓDI AUTÓADAT" else "VALÓDI ECU"} • kiválasztott forrás: $source")
            appendLine("Megadott járműprofil: Suzuki SX4 2007 • 1.5 benzin (felhasználói adat)")
            appendLine("Alkalmazás: $version • jelentésformátum: 1")
            appendLine("Kezdet (UTC): ${Instant.ofEpochMilli(startedUtcMs)} • időtartam: $durationMs ms")
            appendLine("Egy kiválasztott válaszoló szabványos OBD-adatai; nem teljes jármű- vagy gyári szervizvizsgálat.")
            appendLine("A fejezetek egymás után készültek; az adatok nem egyidejűek. A hibás/hiányzó adat nem negatív hibavizsgálat.")
            if (clearUncertain) appendLine("FIGYELEM: egy korábbi hibakódtörlés eredménye BIZONYTALAN. Ez a jelentés nem igazolja annak sikerét.")
            for (section in sections) {
                appendLine(); appendLine("=== ${section.title} ===")
                appendLine("Olvasási időablak: +${section.fromMs}…+${section.toMs} ms")
                section.lines.forEach { appendLine(it) }
            }
            appendLine(); appendLine(DtcScan.NOTE)
            appendLine("Ez a jelentés nem ad automatikus megfelelt/nem megfelelt minősítést. Katalizátorhibát vagy javítási igényt önmagában nem bizonyít.")
            appendLine("A jelentés VIN-t is tartalmazhat, ha a vezérlő kiadta. Megosztás előtt ellenőrizd a tartalmát.")
        }
    }

    fun collect(source: String, version: String, clearUncertain: Boolean = false,
        progress: (String) -> Unit = {}): Snapshot {
        val start = elapsed(); val wall = utc()
        fun valid() = current() && connected()
        fun ask(command: String): String {
            check(valid()) { "A jelentéskészítés megszakadt; nem készült új jelentés" }
            return send(command).also { check(valid()) { "A jelentéskészítés megszakadt; nem készült új jelentés" } }
        }
        val sections = mutableListOf<Section>()
        fun section(title: String, read: () -> List<String>) {
            check(valid()) { "A jelentéskészítés megszakadt" }
            progress(title)
            val from = elapsed() - start
            val lines = read()
            sections += Section(title, from, elapsed() - start, lines)
        }
        val dtcs = mutableListOf<DtcScan.Result>()
        for (mode in listOf(3, 7, 10)) {
            section(when (mode) { 3 -> "Tárolt hibakódok (03)"; 7 -> "Függő hibakódok (07)"; else -> "Állandó hibakódok (0A)" }) {
                val result = DtcScan(::ask, canProtocol, ::valid).read(mode)
                dtcs += result; result.lines()
            }
        }
        section("Adapter- és vezérlőazonosítók") { EcuIdentity(::ask, canProtocol, ::valid).report() }
        section("Readiness, járműállapot és Freeze Frame 0") {
            VehicleStatus(::ask, supported, ::valid).report().flatMap { listOf(it.title) + it.lines }
        }
        check(valid()) { "A jelentéskészítés megszakadt" }
        return Snapshot(source, version, "REAL", wall, elapsed() - start, dtcs.toList(), sections, clearUncertain)
    }

    companion object {
        fun demo(version: String): Snapshot {
            val codes = listOf(DtcScan.Result(3, DtcScan.State.OK, listOf("P0420"), ""),
                DtcScan.Result(7, DtcScan.State.OK, listOf("P0171"), ""),
                DtcScan.Result(10, DtcScan.State.UNAVAILABLE, emptyList(), "DEMO: nem elérhető funkció példája"))
            return Snapshot("DEMO", version, "DEMO", System.currentTimeMillis(), 0, codes,
                codes.map { Section(it.title, 0, 0, it.lines()) } + listOf(
                    Section("Adapter- és vezérlőazonosítók", 0, 0, listOf("DEMO: nincs valódi VIN vagy vezérlőazonosító")),
                    Section("Readiness és Freeze Frame", 0, 0, listOf("DEMO: MIL BE • katalizátor NEM KÉSZ", "DEMO: Freeze Frame P0420 • 2480 1/perc • 89 °C"))), false)
        }
        fun save(directory: File, report: Snapshot): File {
            check(directory.mkdirs() || directory.isDirectory)
            val file = File(directory, "sx4-report-${report.mode.lowercase(java.util.Locale.ROOT)}-${report.startedUtcMs}-${UUID.randomUUID()}.txt")
            val partial = File(directory, file.name + ".part")
            check(partial.createNewFile())
            try {
                FileOutputStream(partial).use { out -> out.write(report.text().toByteArray(Charsets.UTF_8)); out.fd.sync() }
                check(partial.renameTo(file)) { "A jelentés véglegesítése sikertelen" }
            } catch (e: Exception) { partial.delete(); throw e }
            return file
        }
        fun saved(directory: File): List<File> = directory.listFiles()?.filter { it.isFile && it.name.startsWith("sx4-report-") && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
