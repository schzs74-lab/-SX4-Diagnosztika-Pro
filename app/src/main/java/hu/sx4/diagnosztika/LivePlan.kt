package hu.sx4.diagnosztika

/**
 * Live-data polling order. On a K-line ECU (typical for a 2007 EU SX4) one request takes roughly
 * 100–300 ms, so plain round-robin over every PID would refresh RPM/trims only every few seconds.
 * Fast PIDs are read every cycle, slow ones one per cycle.
 */
object LivePlan {
    /** Narrowband voltage channels only, each queried at its own timestamp. */
    fun oxygenSequence(supported: Set<Int>): List<Int> = listOf(0x14, 0x15).filter { it in supported }
    /** 24 and 44 only exist with a wideband upstream sensor; then they replace 14 as the fast lambda signal. */
    val fast = listOf(0x0C, 0x04, 0x11, 0x06, 0x14, 0x15, 0x24, 0x44, 0x0B, 0x10)
    val slow = listOf(0x05, 0x07, 0x03, 0x0D, 0x0F, 0x0E, 0x25, 0x43, 0x45, 0x2F, 0x42, 0x33, 0x46, 0x3C)

    /** One full polling period; only PIDs the ECU reported in its support mask are included. */
    fun sequence(supported: Set<Int>): List<Int> {
        val f = fast.filter { it in supported }
        val s = slow.filter { it in supported }
        return if (s.isEmpty()) f else s.flatMap { f + it }
    }

    /**
     * Dashboard plan: the [sequence] with engine speed before every second other request, so the
     * tachometer refreshes every third poll instead of once per fast group.
     */
    fun prioritized(supported: Set<Int>): List<Int> {
        val base = sequence(supported)
        if (0x0C !in base) return base
        val others = base.filter { it != 0x0C }
        return if (others.isEmpty()) listOf(0x0C) else others.chunked(2).flatMap { listOf(0x0C) + it }
    }

    /** Display order: fast group first, then slow. */
    fun displayed(supported: Set<Int>): List<Int> = (fast + slow).filter { it in supported }

    /** Polls between two reads of [pid] in [sequence] (rounded up). */
    fun pollsBetween(pid: Int, sequence: List<Int>): Int {
        val count = sequence.count { it == pid }
        require(count > 0) { "A PID nincs a lekérdezési tervben" }
        return (sequence.size + count - 1) / count
    }

    /**
     * A value is stale when it has missed more than about two of its own refreshes, measured with the
     * observed average poll duration; never less than 2 s.
     */
    fun staleAfterMs(pid: Int, sequence: List<Int>, avgPollMs: Long): Long =
        maxOf(2_000L, (2.5 * pollsBetween(pid, sequence) * avgPollMs).toLong())
}
