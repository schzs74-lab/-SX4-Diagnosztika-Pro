package hu.sx4.diagnosztika

/** Single polling-thread owner. No inference that an open-loop or maximum-scale voltage is invalid. */
class PollingTelemetry {
    data class Metrics(val hz: Double?, val requestMs: Long, val errors: Int, val rpm: Double?, val rpmAgeMs: Long?,
        val fuelStatus: Int?, val fuelAgeMs: Long?, val context: String) {
        fun csv(): List<String> = listOf(hz?.let { decimal(it) } ?: "", requestMs.toString(), errors.toString(),
            rpm?.let { decimal(it) } ?: "", rpmAgeMs?.toString() ?: "", fuelStatus?.toString() ?: "",
            fuelAgeMs?.toString() ?: "", context)
    }
    private data class Channel(val times: ArrayDeque<Long> = ArrayDeque(), var errors: Int = 0, var requestMs: Long = 0)
    private val channels = mutableMapOf<Int, Channel>()
    private var rpm: Pair<Double, Long>? = null
    private var fuel: Pair<Int, Long>? = null
    @Synchronized fun success(sample: LiveSample, at: Long, requestMs: Long): Metrics {
        val channel = channels.getOrPut(sample.pid) { Channel() }
        if (channel.times.lastOrNull()?.let { at > it } != false) channel.times.addLast(at)
        if (sample.pid == 12) rpm = sample.value!! to at
        if (sample.pid == 3) fuel = sample.data.first() to at
        return metrics(sample.pid, at, requestMs)
    }
    @Synchronized fun failure(pid: Int, at: Long, requestMs: Long): Metrics {
        channels.getOrPut(pid) { Channel() }.errors++
        if (pid == 12) rpm = null
        if (pid == 3) fuel = null
        return metrics(pid, at, requestMs)
    }
    @Synchronized fun metrics(pid: Int, at: Long, requestMs: Long? = null): Metrics {
        val channel = channels.getOrPut(pid) { Channel() }
        while (channel.times.isNotEmpty() && at - channel.times.first() > 10_000) channel.times.removeFirst()
        while (channel.times.size > 1024) channel.times.removeFirst()
        if (requestMs != null) channel.requestMs = requestMs
        val times = channel.times
        val hz = if (times.size >= 2) (times.size - 1) * 1000.0 / (times.last() - times.first()) else null
        val r = rpm?.takeIf { at - it.second in 0..5000 }
        val f = fuel?.takeIf { at - it.second in 0..5000 }
        val context = when {
            r == null -> "FORDULATSZAM_NINCS_FRISS_ADAT"
            r.first == 0.0 -> "MOTOR_ALL"
            f == null -> "SZABALYOZAS_NINCS_FRISS_ADAT"
            f.first == 2 -> "MOTOR_JAR_ZART_KOR"
            f.first in setOf(1, 4, 8) -> "MOTOR_JAR_NYITOTT_KOR"
            f.first == 16 -> "MOTOR_JAR_VISSZACSATOLASI_HIBA"
            else -> "MOTOR_JAR_SZABALYOZAS_NEM_AZONOSITOTT"
        }
        return Metrics(hz, channel.requestMs, channel.errors, r?.first, r?.let { at-it.second }, f?.first, f?.let { at-it.second }, context)
    }
    companion object {
        fun decimal(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
