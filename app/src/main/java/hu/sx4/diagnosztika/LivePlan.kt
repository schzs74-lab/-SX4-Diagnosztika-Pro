package hu.sx4.diagnosztika

/**
 * Live-data polling order. On a K-line ECU (typical for a 2007 EU SX4) one request takes roughly
 * 100–300 ms, so plain round-robin over every PID would refresh RPM/trims only every few seconds.
 * Fast PIDs are read every cycle, slow ones one per cycle.
 */
object LivePlan {
    val fast = listOf(0x0C, 0x04, 0x11, 0x06, 0x14, 0x15, 0x0B, 0x10)
    val slow = listOf(0x05, 0x07, 0x0D, 0x0F, 0x0E)

    /** One full polling period; only PIDs the ECU reported in its support mask are included. */
    fun sequence(supported: Set<Int>): List<Int> {
        val f = fast.filter { it in supported }
        val s = slow.filter { it in supported }
        return if (s.isEmpty()) f else s.flatMap { f + it }
    }

    /** Display order: fast group first, then slow. */
    fun displayed(supported: Set<Int>): List<Int> = (fast + slow).filter { it in supported }

    /** A value older than this is shown as stale, never as a current measurement. */
    fun staleAfterMs(pid: Int): Long = if (pid in fast) 4_000 else 30_000
}
