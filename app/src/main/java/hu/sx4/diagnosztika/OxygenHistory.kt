package hu.sx4.diagnosztika

import java.util.ArrayDeque

/** UI-thread history. Missing samples are gaps, never zeroes or interpolated simultaneous values. */
class OxygenHistory(private val windowMs: Long = 60_000, private val capacity: Int = 1024) {
    data class Point(val atMs: Long, val volts: Double, val startsSegment: Boolean)
    private val traces = mapOf(0x14 to ArrayDeque<Point>(), 0x15 to ArrayDeque<Point>())
    private val gaps = mutableSetOf<Int>()
    fun clear() { traces.values.forEach { it.clear() }; gaps.clear() }
    fun gap(pid: Int) { if (pid in traces) gaps += pid }
    fun add(pid: Int, atMs: Long, volts: Double) {
        val trace = traces[pid] ?: return
        require(volts.isFinite() && volts in 0.0..1.275) { "Érvénytelen szondafeszültség" }
        val previous = trace.peekLast()
        require(previous == null || atMs >= previous.atMs) { "Nem növekvő mérési idő" }
        trace.addLast(Point(atMs, volts, previous == null || pid in gaps || atMs - previous.atMs > 5_000))
        gaps -= pid
        prune(trace, atMs)
    }
    fun points(pid: Int, nowMs: Long): List<Point> {
        val trace = traces[pid] ?: return emptyList()
        prune(trace, nowMs)
        return trace.toList()
    }
    private fun prune(trace: ArrayDeque<Point>, nowMs: Long) {
        while (trace.isNotEmpty() && (trace.peekFirst().atMs < nowMs - windowMs || trace.size > capacity)) trace.removeFirst()
    }
}
