package hu.sx4.diagnosztika

/**
 * Read-only status report: readiness monitors, vehicle/odometer-type PIDs, adapter voltage and the full
 * decodable freeze frame 0. Every query fails on its own line; only a lost connection ([connected] false)
 * aborts the report. [send] is the serialized ELM command function, so tests can use a scripted fake ECU.
 */
class VehicleStatus(
    private val send: (String) -> String,
    private val supported: Set<Int>,
    private val connected: () -> Boolean = { true },
) {
    data class Section(val title: String, val lines: List<String>)

    private val infoPids = listOf(0x1C, 0x13, 0x21, 0x4D, 0x30, 0x31, 0x4E, 0x1F)

    fun report(): List<Section> = listOf(readiness(), info(), freezeFrame())

    private inline fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) } catch (e: Exception) {
        if (!connected()) throw e
        Result.failure(e)
    }

    private fun readiness(): Section {
        val title = "Readiness monitorok (0101)"
        if (0x01 !in supported) return Section(title, listOf("Az ECU nem jelzi támogatottnak"))
        val lines = attempt { Obd.readinessLines(Obd.readiness(Obd.bytesFor(send("0101"), 1, 1))) }
            .getOrElse { listOf("Nincs érvényes válasz: ${it.message}") }
        return Section(title, lines)
    }

    private fun info(): Section {
        val lines = infoPids.filter { it in supported }.map { pid ->
            attempt { Obd.reading(pid, Obd.bytesFor(send("01%02X".format(pid)), 1, pid)) }
                .getOrElse { "%02X: nincs érvényes válasz • %s".format(pid, it.message) }
        }.toMutableList()
        lines += attempt { send("ATRV").trim() }.getOrNull()
            ?.takeIf { it.matches(Regex("\\d{1,2}(\\.\\d{1,2})?V")) }
            ?.let { "Adapter által mért feszültség: $it" } ?: "Adapterfeszültség (ATRV): nem érhető el"
        return Section("Járműállapot", lines)
    }

    private fun freezeFrame(): Section {
        val title = "Freeze Frame (0. keret)"
        val ffSupported = attempt {
            Obd.supportChain(Obd.freezeFramePids(send("020000"))) { base ->
                attempt { Obd.freezeFramePids(send("02%02X00".format(base)), base) }.getOrNull()
            }
        }.getOrElse { return Section(title, listOf("Nem olvasható: ${it.message}")) }
        if (0x02 !in ffSupported) return Section(title, listOf("Az ECU nem adja meg a Freeze Frame hibakódját"))
        val trigger = attempt { Obd.freezeFrameDtc(Obd.freezeFrameData(send("020200"), 2)) }
            .getOrElse { return Section(title, listOf("A kiváltó hibakód nem olvasható: ${it.message}")) }
            ?: return Section(title, listOf("Nincs tárolt Freeze Frame"))
        val lines = mutableListOf("Kiváltó hibakód: $trigger")
        for (pid in ffSupported.sorted().filter { it != 0x02 && it % 0x20 != 0 }) {
            lines += attempt {
                val data = Obd.freezeFrameData(send("02%02X00".format(pid)), pid)
                if (Obd.decodable(pid)) Obd.reading(pid, data)
                else "%02X: nyers adat %s".format(pid, data.joinToString(" ") { "%02X".format(it) })
            }.getOrElse { "%02X: nincs érvényes válasz • %s".format(pid, it.message) }
        }
        return Section(title, lines)
    }
}
