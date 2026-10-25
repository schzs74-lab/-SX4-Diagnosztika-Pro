package hu.sx4.diagnosztika

import kotlin.math.sin

object DemoSamples {
    val supported = setOf(0x03, 0x0C, 0x05, 0x06, 0x07, 0x14, 0x15)
    fun data(pid: Int, atMs: Long): List<Int> = when (pid) {
        0x03 -> listOf(2, 0) // simulated closed-loop context
        0x0C -> listOf(0x0C, 0x30) // 780 rpm, simulated
        0x05 -> listOf(128)
        0x06 -> listOf(132)
        0x07 -> listOf(135)
        0x14 -> listOf(((0.45 + 0.35*sin(atMs/350.0))*200).toInt(), 128)
        0x15 -> listOf(((0.65 + 0.03*sin(atMs/1700.0))*200).toInt(), 128)
        else -> error("Nincs DEMO-adat ehhez a PID-hez")
    }
}

