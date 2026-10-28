package hu.sx4.diagnosztika

/** One worker owns this recorder, the command stream and its file (none in live-only mode). Android lifecycle is outside it. */
class DriveRecorder(
    private val send: (String) -> String,
    supported: Set<Int>,
    oxygenOnly: Boolean,
    private val log: MeasurementLog?,
    private val elapsed: () -> Long,
    private val current: () -> Boolean = { true },
) {
    data class Event(val pid: Int, val atMs: Long, val sample: LiveSample?, val error: String?, val metrics: PollingTelemetry.Metrics)
    val telemetry = PollingTelemetry()
    val sequence = if (oxygenOnly) {
        // Context is refreshed in the fast profile too. Unsupported PIDs are never invented.
        listOf(12, 3).filter { it in supported } + List(3) { listOf(20, 21).filter { it in supported } }.flatten()
    } else LivePlan.prioritized(supported) // RPM every third request; 03 stays in the slow group
    private var index = 0
    var limitReached = false
        private set
    init { require(sequence.isNotEmpty()) { "Nincs támogatott mérési PID" } }
    fun step(): Event {
        check(current()) { "Megszakított mérés" }
        val pid = sequence[index]; index = (index + 1) % sequence.size
        val before = elapsed()
        // A storage failure must never be handled as an ECU error or retried as another CSV row.
        val event = try {
            val sample = LiveSample.decode(pid, Obd.bytesFor(send("01%02X".format(pid)), 1, pid))
            check(current()) { "Megszakított mérés" }
            val at = elapsed()
            Event(pid, at, sample, null, telemetry.success(sample, at, at-before))
        } catch (e: Exception) {
            check(current()) { "Megszakított mérés" }
            val at = elapsed()
            Event(pid, at, null, e.message ?: "Ismeretlen válaszhiba", telemetry.failure(pid, at, at-before))
        }
        check(current()) { "Megszakított mérés" }
        val accepted = when {
            log == null -> true
            event.sample != null -> log.append(event.sample, event.atMs, event.metrics)
            else -> log.failure(pid, event.error!!, event.atMs, event.metrics)
        }
        limitReached = !accepted
        return event
    }
}
